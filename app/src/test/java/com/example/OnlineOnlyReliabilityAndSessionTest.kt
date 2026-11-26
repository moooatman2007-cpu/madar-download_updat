package com.example

import com.example.core.model.AttendanceStatus
import com.example.core.model.LessonPayment
import com.example.core.model.Student
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.auth.AuthActionContext
import com.example.data.auth.SafeAuthErrorMapper
import com.example.util.toArabicErrorMessage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class OnlineOnlyReliabilityAndSessionTest {

    @Before
    fun setUp() {
        SupabaseClientProvider.mockTeacherId = "teacher_test_123"
        AccountSessionManager.setActiveTeacherId("teacher_test_123")
    }

    @After
    fun tearDown() {
        AccountSessionManager.setActiveTeacherId(null)
        SupabaseClientProvider.mockTeacherId = null
    }

    @Test
    fun testSessionActiveGating() {
        assertTrue(AccountSessionManager.isSessionActive("teacher_test_123"))
        assertFalse(AccountSessionManager.isSessionActive("other_teacher"))
    }

    @Test
    fun testSafeAuthErrorMappingWithoutLeakingSecrets() {
        val ex = IOException("Failed to connect to https://xyz.supabase.co with Authorization: Bearer secret_jwt_token_123")
        val arabic = SafeAuthErrorMapper.getArabicErrorMessage(ex, AuthActionContext.SIGN_IN)
        assertNotNull(arabic)
        // Must sanitize sensitive tokens
        assertFalse(arabic.contains("Bearer"))
        assertFalse(arabic.contains("secret_jwt"))
    }

    @Test
    fun testNetworkErrorMappingToArabic() {
        val ex = IOException("unable to resolve host")
        val arabic = ex.toArabicErrorMessage()
        assertNotNull(arabic)
        assertTrue(arabic.contains("إنترنت") || arabic.contains("الاتصال"))
    }
}
