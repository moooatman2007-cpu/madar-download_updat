package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.Student
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.auth.AuthActionContext
import com.example.data.auth.SafeAuthErrorMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SafeLogoutOutboxProtectionTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setup() {
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
    fun testConcurrencyWriteStartsFirstLogoutWaitsAndCompletes() = runTest {
        val teacherId = "teacher_race_1"
        SupabaseClientProvider.mockTeacherId = teacherId
        AccountSessionManager.setActiveTeacherId(teacherId)

        val writeStarted = CompletableDeferred<Unit>()
        val logoutAttempted = CompletableDeferred<Unit>()

        val writeJob = launch(Dispatchers.IO) {
            AccountSessionManager.runIfSessionActive(teacherId) {
                writeStarted.complete(Unit)
                logoutAttempted.await()
                true
            }
        }

        val logoutDeferred = async(Dispatchers.IO) {
            writeStarted.await()
            logoutAttempted.complete(Unit)
            AccountSessionManager.logout()
        }

        writeJob.join()
        val logoutResult = logoutDeferred.await()
        assertTrue(logoutResult.isSuccess)
    }

    @Test
    fun testConcurrencyLogoutStartsFirstNewWriteRejected() = runTest {
        val teacherId = "teacher_race_2"
        SupabaseClientProvider.mockTeacherId = teacherId
        AccountSessionManager.setActiveTeacherId(teacherId)

        val logoutResult = AccountSessionManager.logout()
        assertTrue(logoutResult.isSuccess)

        val writeResult = AccountSessionManager.runIfSessionActive(teacherId) {
            true
        }
        assertNull(writeResult)
    }

    @Test
    fun testLogoutSucceedsWithActiveSession() = runTest {
        val teacherId = "teacher_clean"
        SupabaseClientProvider.mockTeacherId = teacherId
        AccountSessionManager.setActiveTeacherId(teacherId)

        val result = AccountSessionManager.logout()
        assertTrue(result.isSuccess)
        assertFalse(AccountSessionManager.isSessionActive(teacherId))
    }

    @Test
    fun testArabicErrorMessageReachesUI() = runTest {
        val mappedMsg = SafeAuthErrorMapper.getArabicErrorMessage(RuntimeException("Invalid"), AuthActionContext.SIGN_OUT)
        assertNotNull(mappedMsg)
        assertTrue(mappedMsg.isNotBlank())
    }
}
