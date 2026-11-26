package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.Teacher
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.repository.DataStoreSettingsRepository
import com.example.data.repository.MockGradeRepository
import com.example.data.repository.MockSettingsRepository
import com.example.data.repository.MockTeacherRepository
import com.example.data.repository.NotificationPreferences
import com.example.data.repository.ThemeMode
import com.example.ui.more.MoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsAndLogoutTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        AccountSessionManager.setActiveTeacherId(null)
        SupabaseClientProvider.mockTeacherId = null
        Dispatchers.resetMain()
    }

    @Test
    fun testDataStoreSettingsRepositoryPersistsFeeThemeAndNotifications() = runTest {
        val repo = DataStoreSettingsRepository(context)
        repo.setThemeMode(ThemeMode.DARK)
        repo.setDefaultMonthlyFee(325.0)
        repo.setNotificationPreferences(
            NotificationPreferences(
                attendanceRemindersEnabled = false,
                absenceAlertsEnabled = true,
                monthlyReportAlertsEnabled = false
            )
        )

        assertEquals(ThemeMode.DARK, repo.getThemeMode().first())
        assertEquals(325.0, repo.getDefaultMonthlyFee().first(), 0.01)
        val prefs = repo.getNotificationPreferences().first()
        assertFalse(prefs.attendanceRemindersEnabled)
        assertTrue(prefs.absenceAlertsEnabled)
        assertFalse(prefs.monthlyReportAlertsEnabled)
    }

    @Test
    fun testMoreViewModelSavesNotificationPrefsDefaultFeeAndLoadsTeacher() = runTest {
        val teacherId = "teacher_more_1"
        SupabaseClientProvider.mockTeacherId = teacherId
        AccountSessionManager.setActiveTeacherId(teacherId)

        val settingsRepo = MockSettingsRepository()
        val vm = MoreViewModel(
            settingsRepository = settingsRepo,
            gradeRepository = MockGradeRepository(),
            teacherRepository = MockTeacherRepository(Teacher(id = teacherId, fullName = "المدرس")),
            ioDispatcher = testDispatcher,
            runInitialCheck = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("المدرس", vm.uiState.value.teacherName)

        // Save notification & fee settings directly via DataStore/SettingsRepository
        val newPrefs = NotificationPreferences(false, true, false)
        vm.saveNotificationAndFeeSettings(newPrefs, 180.0)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(180.0, vm.uiState.value.defaultMonthlyFee, 0.01)
        assertEquals(newPrefs, vm.uiState.value.notificationPrefs)
    }

    @Test
    fun testMoreViewModelOnlineOnlyLogoutClearsSessionAndDialog() = runTest {
        val teacherId = "teacher_logout_1"
        SupabaseClientProvider.mockTeacherId = teacherId
        AccountSessionManager.setActiveTeacherId(teacherId)
        val initialEpoch = AccountSessionManager.currentSessionEpoch()

        val vm = MoreViewModel(
            settingsRepository = MockSettingsRepository(),
            gradeRepository = MockGradeRepository(),
            teacherRepository = MockTeacherRepository(Teacher(id = teacherId, fullName = "المدرس")),
            ioDispatcher = testDispatcher,
            runInitialCheck = false
        )
        testDispatcher.scheduler.advanceUntilIdle()

        var logoutSucceeded = false
        vm.openLogoutDialog()
        assertTrue(vm.uiState.value.showLogoutDialog)

        vm.performLogout { logoutSucceeded = true }
        testDispatcher.scheduler.advanceUntilIdle()

        var waited = 0
        while (vm.uiState.value.isLoggingOut && waited < 100) {
            Thread.sleep(20)
            testDispatcher.scheduler.advanceUntilIdle()
            waited++
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoggingOut)
        assertTrue(logoutSucceeded)
        assertFalse(vm.uiState.value.showLogoutDialog)
        assertNull(vm.uiState.value.logoutError)
        assertNull(SupabaseClientProvider.mockTeacherId)
        assertFalse(AccountSessionManager.isSessionActive(teacherId))
        assertTrue(AccountSessionManager.currentSessionEpoch() > initialEpoch)
    }
}
