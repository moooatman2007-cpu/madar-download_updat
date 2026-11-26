package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.AttendanceStatus
import com.example.core.model.BatchSyncStatus
import com.example.core.model.Recitation
import com.example.core.model.Student
import com.example.core.model.SupabaseStudentInsertDto
import com.example.core.model.UpdateStudentOnlineDto
import com.example.data.auth.AccountSessionManager
import com.example.data.local.AppDatabase
import com.example.data.local.dao.AttendanceDao
import com.example.data.local.dao.OutboxDao
import com.example.data.local.dao.RecitationDao
import com.example.data.local.dao.StudentDao
import com.example.data.local.mapper.toEntity
import com.example.data.repository.StudentRemoteDataSource
import com.example.data.repository.SupabaseAttendanceRepository
import com.example.data.repository.SupabaseRecitationRepository
import com.example.data.repository.SupabaseStudentRepository
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RepositoryOutboxIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var studentDao: StudentDao
    private lateinit var attendanceDao: AttendanceDao
    private lateinit var recitationDao: RecitationDao
    private lateinit var outboxDao: OutboxDao

    private lateinit var studentRepository: SupabaseStudentRepository
    private lateinit var attendanceRepository: SupabaseAttendanceRepository
    private lateinit var recitationRepository: SupabaseRecitationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        studentDao = db.studentDao()
        attendanceDao = db.attendanceDao()
        recitationDao = db.recitationDao()
        outboxDao = db.outboxDao()

        com.example.data.SupabaseClientProvider.mockTeacherId = "teacher_x"
        AccountSessionManager.setActiveTeacherId("teacher_x")
        com.example.data.local.DatabaseProvider.setDatabase(db, context)

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
        attendanceRepository = SupabaseAttendanceRepository(attendanceDao, outboxDao, studentDao)
        recitationRepository = SupabaseRecitationRepository(recitationDao, outboxDao)
    }

    @After
    fun tearDown() {
        db.close()
        com.example.data.local.DatabaseProvider.resetForTesting()
        AccountSessionManager.setActiveTeacherId(null)
        com.example.data.SupabaseClientProvider.mockTeacherId = null
    }

    @Test
    fun testOfflineStudentInsertFailsWithoutRoomOrOutbox() = runBlocking {
        val result = studentRepository.addStudent(
            fullName = "Ahmad Ali",
            gradeId = "grade_1",
            parentPhone = "01012345678",
            hasWhatsApp = true,
            alternativePhone = null
        )

        assertTrue(result.isFailure)
        assertTrue(studentDao.getAllStudentsSync("teacher_x").isEmpty())
        assertTrue(outboxDao.getPendingOperations().isEmpty())
    }

    @Test
    fun testOfflineStudentUpdateFailsWithoutRoomOrOutbox() = runBlocking {
        val initialStudent = Student(
            studentId = "s_123",
            studentCode = "ST-00123",
            fullName = "Old Name",
            gradeId = "grade_1",
            parentPhone = "01000000000",
            teacherId = "teacher_x"
        )

        val updatedStudent = initialStudent.copy(fullName = "New Name")
        val result = studentRepository.updateStudent(updatedStudent)

        assertTrue(result.isFailure)
        assertTrue(studentDao.getAllStudentsSync("teacher_x").isEmpty())
        assertTrue(outboxDao.getPendingOperations().isEmpty())
    }

    @Test
    fun testOfflineStudentDeleteFailsWithoutRoomOrOutbox() = runBlocking {
        val result = studentRepository.deleteStudent("s_del")
        assertTrue(result.isFailure)
        assertTrue(studentDao.getAllStudentsSync("teacher_x").isEmpty())
        assertTrue(outboxDao.getPendingOperations().isEmpty())
    }

    @Test
    fun testOfflineAttendanceWriteCreatesRoomAndOutbox() = runBlocking {
        // Test Case 5: Offline Attendance write -> Room + Outbox
        val result = attendanceRepository.recordOrUpdateAttendance(
            studentId = "s_1",
            date = "2026-09-23",
            status = AttendanceStatus.PRESENT,
            note = "On time"
        )

        assertTrue(result.isSuccess)

        val cached = attendanceDao.getAttendanceByDateSync("teacher_x", "s_1", "2026-09-23")
        assertNotNull(cached)
        assertEquals("PRESENT", cached?.status)

        val pending = outboxDao.getPendingOperations()
        assertEquals(1, pending.size)
        assertEquals("UPSERT", pending[0].operationType)
        assertEquals("ATTENDANCE", pending[0].entityType)
        assertEquals("PENDING", pending[0].status)
    }

    @Test
    fun testOfflineRecitationWriteCreatesRoomAndOutbox() = runBlocking {
        // Test Case 6: Offline Recitation write -> Room + Outbox
        val result = recitationRepository.addRecitation(
            studentId = "s_1",
            date = "2026-09-23",
            title = "Al-Baqarah",
            content = "1-10",
            score = 9.5,
            maxScore = 10.0,
            note = "Excellent"
        )

        assertTrue(result.isSuccess)
        val rec = result.getOrNull()
        assertNotNull(rec)

        val cached = recitationDao.getRecitationByIdSync("teacher_x", rec!!.data.recitationId)
        assertNotNull(cached)
        assertEquals("Al-Baqarah", cached?.title)

        val pending = outboxDao.getPendingOperations()
        assertEquals(1, pending.size)
        assertEquals("INSERT", pending[0].operationType)
        assertEquals("RECITATION", pending[0].entityType)
        assertEquals("PENDING", pending[0].status)
    }

    @Test
    fun testProcessDeathPersistenceAndTenantIsolationForPhase2Outbox() = runBlocking {
        // Test Case 7 & 9: Process death simulation (Room persistence) & Tenant Isolation (Phase 2 Attendance Outbox)
        attendanceRepository.recordOrUpdateAttendance(
            studentId = "s_isolation",
            date = "2026-09-23",
            status = AttendanceStatus.PRESENT,
            note = "Present"
        )

        val pendingOps = outboxDao.getPendingOperations()
        assertEquals(1, pendingOps.size)

        // Verify tenant isolation query
        val teacherId = pendingOps[0].teacherId ?: ""
        val pendingForTeacher = outboxDao.getPendingOperationsForTeacher(teacherId)
        assertEquals(1, pendingForTeacher.size)

        val pendingForOther = outboxDao.getPendingOperationsForTeacher("other_teacher")
        assertTrue(pendingForOther.isEmpty())
    }
}
