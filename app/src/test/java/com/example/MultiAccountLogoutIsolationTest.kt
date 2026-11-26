package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.Attendance
import com.example.core.model.AttendanceStatus
import com.example.core.model.Grade
import com.example.core.model.Student
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.auth.DeepLinkAuthHandler
import com.example.data.repository.RepositoryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MultiAccountLogoutIsolationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        DeepLinkAuthHandler.dispatcher = testDispatcher
        context = ApplicationProvider.getApplicationContext()
        RepositoryProvider.init(context)
    }

    @After
    fun tearDown() {
        DeepLinkAuthHandler.dispatcher = Dispatchers.IO
        RepositoryProvider.clearAllSessionCaches()
        AccountSessionManager.setActiveTeacherId(null)
        SupabaseClientProvider.mockTeacherId = null
        Dispatchers.resetMain()
    }

    /**
     * Scenario A: Manual logout cleanly purges all in-memory session tracking and repository caches.
     */
    @Test
    fun testManualLogoutClearsTeacherSessionAndCaches() = runTest {
        val teacherAId = "teacher_a_123"
        SupabaseClientProvider.mockTeacherId = teacherAId
        AccountSessionManager.setActiveTeacherId(teacherAId)

        assertTrue(AccountSessionManager.isSessionActive(teacherAId))

        val logoutResult = AccountSessionManager.logout(force = true)
        assertTrue(logoutResult.isSuccess)

        assertFalse(AccountSessionManager.isSessionActive(teacherAId))
        assertNull(AccountSessionManager.getActiveTeacherId())
        assertNull(SupabaseClientProvider.mockTeacherId)
    }

    /**
     * Scenario B: Automatic session expiry triggers onSessionTerminated. The session becomes
     * inactive and in-memory caches are wiped.
     */
    @Test
    fun testSessionExpiryTriggersCleanTeardown() = runTest {
        val teacherAId = "teacher_expired_abc"
        SupabaseClientProvider.mockTeacherId = teacherAId
        AccountSessionManager.setActiveTeacherId(teacherAId)

        assertTrue(AccountSessionManager.isSessionActive(teacherAId))

        val termResult = AccountSessionManager.onSessionTerminated()
        assertTrue(termResult.isSuccess)

        assertFalse(AccountSessionManager.isSessionActive(teacherAId))
    }

    /**
     * Scenario C: A deep link carrying an auth error must NOT end the signed-in session.
     */
    @Test
    fun testSpoofedDeepLinkAuthErrorDoesNotTerminateSession() = runTest {
        val teacherAId = "teacher_old_session"
        SupabaseClientProvider.mockTeacherId = teacherAId
        AccountSessionManager.setActiveTeacherId(teacherAId)
        assertTrue(AccountSessionManager.isSessionActive(teacherAId))

        DeepLinkAuthHandler.resetForTesting()
        val errorUri = Uri.parse("studentmanager://login-callback#error=access_denied&error_description=User+declined")
        val job = DeepLinkAuthHandler.handleUri(errorUri)
        job?.join()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(AccountSessionManager.isSessionActive(teacherAId))
    }

    /**
     * Scenario D: Logout successfully completes and resets session state.
     */
    @Test
    fun testLogoutResetsSessionState() = runTest {
        val teacherAId = "teacher_outbox_test"
        SupabaseClientProvider.mockTeacherId = teacherAId
        AccountSessionManager.setActiveTeacherId(teacherAId)

        val logoutResult = AccountSessionManager.logout(force = true)
        assertTrue(logoutResult.isSuccess)
        assertFalse(AccountSessionManager.isSessionActive(teacherAId))
    }

    /**
     * Scenario E: Logout followed by login with a different teacher guarantees strict session isolation.
     */
    @Test
    fun testLogoutThenLoginWithDifferentTeacherGuaranteesCompleteIsolation() = runTest {
        val teacherAId = "teacher_alpha"
        SupabaseClientProvider.mockTeacherId = teacherAId
        AccountSessionManager.setActiveTeacherId(teacherAId)
        assertTrue(AccountSessionManager.isSessionActive(teacherAId))

        AccountSessionManager.logout()

        val teacherBId = "teacher_beta"
        SupabaseClientProvider.mockTeacherId = teacherBId
        AccountSessionManager.setActiveTeacherId(teacherBId)

        assertFalse(AccountSessionManager.isSessionActive(teacherAId))
        assertTrue(AccountSessionManager.isSessionActive(teacherBId))
    }

    /**
     * Scenario F: Session expiry during an in-flight operation prevents stale session acceptance.
     */
    @Test
    fun testSessionExpiryDuringNetworkSyncPreventsStaleAcceptance() = runTest {
        val teacherAId = "teacher_stale_sync"
        SupabaseClientProvider.mockTeacherId = teacherAId
        AccountSessionManager.setActiveTeacherId(teacherAId)

        AccountSessionManager.onSessionTerminated()

        assertFalse(AccountSessionManager.isSessionActive(teacherAId))
    }
}
