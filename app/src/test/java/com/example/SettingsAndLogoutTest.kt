package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.Teacher
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.local.AppDatabase
import com.example.data.local.DatabaseProvider
import com.example.data.local.entity.OutboxEntity
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
import org.junit.Assert.assertNotNull
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
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        DatabaseProvider.setDatabase(database, context)
    }

    @After
    fun tearDown() {
        database.close()
        DatabaseProvider.resetForTesting()
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
    fun testMoreViewModelSavesNotificationPrefsDefaultFeeAndObservesOutbox() = runTest {
        val teacherId = "teacher_more_1"
        SupabaseClientProvider.mockTeacherId = teacherId
        AccountSessionManager.setActiveTeacherId(teacherId)

        val settingsRepo = MockSettingsRepository()
        val vm = MoreViewModel(
            settingsRepository = settingsRepo,
            gradeRepository = MockGradeRepository(),
            teacherRepository = MockTeacherRepository(Teacher(id = teacherId, fullName = "المدرس")),
            ioDispatcher = testDispatcher,
            runInitialCheck = false,
            outboxDao = database.outboxDao()
        )

        // Seed 1 transient failed op (network timeout) + 1 permanent constraint failed op
        database.outboxDao().insertOperations(
            listOf(
                OutboxEntity(
                    id = "op_transient",
                    operationType = "INSERT",
                    entityType = "STUDENT",
                    entityId = "s1",
                    payload = "{}",
                    createdAt = 100L,
                    retryCount = 5,
                    status = "FAILED",
                    lastError = "SocketTimeoutException",
                    teacherId = teacherId
                ),
                OutboxEntity(
                    id = "op_permanent",
                    operationType = "INSERT",
                    entityType = "ATTENDANCE",
                    entityId = "a1",
                    payload = "{}",
                    createdAt = 200L,
                    retryCount = 1,
                    status = "FAILED",
                    lastError = "ERROR: insert violates foreign key constraint (23503)",
                    teacherId = teacherId
                )
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, vm.uiState.value.failedOutboxCount)
        assertEquals(0, vm.uiState.value.pendingOutboxCount)

        // Save notification & fee settings
        val newPrefs = NotificationPreferences(false, true, false)
        vm.saveNotificationAndFeeSettings(newPrefs, 180.0)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(180.0, vm.uiState.value.defaultMonthlyFee, 0.01)
        assertEquals(newPrefs, vm.uiState.value.notificationPrefs)

        // Retry Outbox resets only transient failed op to PENDING, keeps permanent error FAILED
        vm.retryPendingOutbox()
        testDispatcher.scheduler.advanceUntilIdle()

        val transientOp = database.outboxDao().getOperationById("op_transient")
        val permanentOp = database.outboxDao().getOperationById("op_permanent")
        assertEquals("PENDING", transientOp?.status)
        assertEquals(0, transientOp?.retryCount)
        assertEquals("FAILED", permanentOp?.status)
        assertNotNull(vm.uiState.value.outboxMessage)
    }

    @Test
    fun testMoreViewModelTwoStepForceLogoutConfirmationWhenOutboxBlocked() = runTest {
        val teacherId = "teacher_logout_1"
        SupabaseClientProvider.mockTeacherId = teacherId
        AccountSessionManager.setActiveTeacherId(teacherId)

        database.outboxDao().insertOperation(
            OutboxEntity(
                id = "op_block",
                operationType = "INSERT",
                entityType = "STUDENT",
                entityId = "s_block",
                payload = "{}",
                createdAt = 100L,
                retryCount = 0,
                status = "PENDING",
                teacherId = teacherId
            )
        )

        val vm = MoreViewModel(
            settingsRepository = MockSettingsRepository(),
            gradeRepository = MockGradeRepository(),
            teacherRepository = MockTeacherRepository(Teacher(id = teacherId, fullName = "المدرس")),
            ioDispatcher = testDispatcher,
            runInitialCheck = false,
            outboxDao = database.outboxDao()
        )
        testDispatcher.scheduler.advanceUntilIdle()

        var logoutSucceeded = false
        vm.openLogoutDialog()
        vm.performLogout { logoutSucceeded = true }
        testDispatcher.scheduler.advanceUntilIdle()
        // AccountSessionManager.logout runs on Dispatchers.IO; wait for its completion
        var waited = 0
        while (vm.uiState.value.isLoggingOut && waited < 100) {
            Thread.sleep(20)
            testDispatcher.scheduler.advanceUntilIdle()
            waited++
        }
        testDispatcher.scheduler.advanceUntilIdle()

        // Normal logout must block and require force-logout confirmation
        assertFalse(vm.uiState.value.isLoggingOut)
        assertFalse(logoutSucceeded)
        assertTrue(vm.uiState.value.requireForceLogoutConfirm)
        assertNotNull(vm.uiState.value.logoutError)

        // Explicit confirmForceLogout proceeds cleanly
        vm.confirmForceLogout { logoutSucceeded = true }
        testDispatcher.scheduler.advanceUntilIdle()
        waited = 0
        while (vm.uiState.value.isLoggingOut && waited < 100) {
            Thread.sleep(20)
            testDispatcher.scheduler.advanceUntilIdle()
            waited++
        }
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.isLoggingOut)
        assertTrue(logoutSucceeded)
        assertFalse(vm.uiState.value.showLogoutDialog)
        assertFalse(vm.uiState.value.requireForceLogoutConfirm)
    }
}
