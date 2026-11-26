package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.AttendanceStatus
import com.example.core.model.BatchSyncStatus
import com.example.core.model.Student
import com.example.core.model.SupabaseStudentInsertDto
import com.example.core.model.UpdateStudentOnlineDto
import com.example.data.auth.AccountSessionManager
import com.example.data.repository.StudentRemoteDataSource
import com.example.data.repository.SupabaseStudentRepository
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryOutboxIntegrationTest {

    private lateinit var studentRepository: SupabaseStudentRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.data.SupabaseClientProvider.mockTeacherId = "teacher_x"
        AccountSessionManager.setActiveTeacherId("teacher_x")

        val offlineStudentRemote = object : StudentRemoteDataSource {
            override suspend fun fetchStudents(teacherId: String): List<Student> = throw UnknownHostException("offline")
            override suspend fun fetchStudentById(teacherId: String, studentId: String): Student? = throw UnknownHostException("offline")
            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String): Student? = throw UnknownHostException("offline")
            override suspend fun insertStudent(dto: SupabaseStudentInsertDto): Student = throw UnknownHostException("offline")
            override suspend fun updateStudent(teacherId: String, studentId: String, request: UpdateStudentOnlineDto): Student = throw UnknownHostException("offline")
            override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) = throw UnknownHostException("offline")
        }

        studentRepository = SupabaseStudentRepository(
            remoteDataSource = offlineStudentRemote,
            teacherIdProvider = { "teacher_x" },
            sessionActiveChecker = { true }
        )
    }

    @After
    fun tearDown() {
        AccountSessionManager.setActiveTeacherId(null)
        com.example.data.SupabaseClientProvider.mockTeacherId = null
    }

    @Test
    fun testOfflineStudentInsertFailsDirectly() = runBlocking {
        val result = studentRepository.addStudent(
            fullName = "Ahmad Ali",
            gradeId = "grade_1",
            parentPhone = "01012345678",
            hasWhatsApp = true,
            alternativePhone = null
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun testOfflineStudentUpdateFailsDirectly() = runBlocking {
        val initialStudent = Student(
            studentId = "s_123",
            studentCode = "ST-00123",
            fullName = "Old Name",
            gradeId = "grade_1",
            parentPhone = "01000000000",
            teacherId = "teacher_x"
        )

        val result = studentRepository.updateStudent(initialStudent)
        assertTrue(result.isFailure)
    }

    @Test
    fun testOfflineStudentDeleteFailsDirectly() = runBlocking {
        val result = studentRepository.deleteStudent("s_del")
        assertTrue(result.isFailure)
    }
}
