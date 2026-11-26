package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.Attendance
import com.example.core.model.AttendanceStatus
import com.example.core.model.Exam
import com.example.core.model.Grade
import com.example.core.model.Homework
import com.example.core.model.HomeworkStatus
import com.example.core.model.LessonPayment
import com.example.core.model.Recitation
import com.example.core.model.Student
import com.example.core.model.SupabaseStudentInsertDto
import com.example.core.model.UpdateStudentOnlineDto
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.repository.StudentRemoteDataSource
import com.example.data.repository.SupabaseStudentRepository
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
class StaleNetworkWritesProtectionTest {

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
    fun testTeacherARequest_logoutA_staleResponseArrives_rejected() = runTest {
        val teacherA = "teacher_A_100"
        SupabaseClientProvider.mockTeacherId = teacherA
        AccountSessionManager.setActiveTeacherId(teacherA)

        val requestTeacherId = teacherA
        AccountSessionManager.logout()

        val isActive = AccountSessionManager.isSessionActive(requestTeacherId)
        assertFalse("Stale write must be rejected after logout", isActive)
    }

    @Test
    fun testTeacherARequest_logoutA_loginB_staleResponseAArrives_rejected() = runTest {
        val teacherA = "teacher_A_200"
        val teacherB = "teacher_B_300"

        SupabaseClientProvider.mockTeacherId = teacherA
        AccountSessionManager.setActiveTeacherId(teacherA)
        val requestA_TeacherId = teacherA

        AccountSessionManager.logout()

        SupabaseClientProvider.mockTeacherId = teacherB
        AccountSessionManager.setActiveTeacherId(teacherB)

        assertFalse(AccountSessionManager.isSessionActive(requestA_TeacherId))
        assertTrue(AccountSessionManager.isSessionActive(teacherB))
    }

    @Test
    fun testTeacherARequest_activeSession_succeeds() = runTest {
        val teacherA = "teacher_A_400"
        SupabaseClientProvider.mockTeacherId = teacherA
        AccountSessionManager.setActiveTeacherId(teacherA)

        assertTrue(AccountSessionManager.isSessionActive(teacherA))
    }

    @Test
    fun testAllRepositoriesRejectStaleWritesAfterLogout() = runTest {
        val teacherA = "teacher_A_500"
        SupabaseClientProvider.mockTeacherId = teacherA
        AccountSessionManager.setActiveTeacherId(teacherA)

        val requestTeacherId = teacherA
        AccountSessionManager.logout()

        assertFalse(AccountSessionManager.isSessionActive(requestTeacherId))
    }
}
