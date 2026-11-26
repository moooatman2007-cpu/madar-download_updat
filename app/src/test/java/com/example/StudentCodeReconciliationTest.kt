package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.core.model.BatchSyncStatus
import com.example.core.model.Student
import com.example.core.model.SupabaseStudentInsertDto
import com.example.core.model.UpdateStudentOnlineDto
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.local.AppDatabase
import com.example.data.local.DatabaseProvider
import com.example.data.local.dao.AttendanceDao
import com.example.data.local.dao.ExamDao
import com.example.data.local.dao.OutboxDao
import com.example.data.local.dao.StudentDao
import com.example.data.local.entity.OutboxEntity
import com.example.data.repository.StudentRemoteDataSource
import com.example.data.repository.SupabaseStudentRepository
import com.example.data.sync.OutboxSyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class StudentCodeReconciliationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var studentDao: StudentDao
    private lateinit var outboxDao: OutboxDao
    private lateinit var attendanceDao: AttendanceDao
    private lateinit var examDao: ExamDao

    private val teacherA = "teacher_alpha_test"
    private val teacherB = "teacher_beta_test"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DatabaseProvider.setDatabase(db, context)

        studentDao = db.studentDao()
        outboxDao = db.outboxDao()
        attendanceDao = db.attendanceDao()
        examDao = db.examDao()

        SupabaseClientProvider.mockTeacherId = teacherA
        AccountSessionManager.setActiveTeacherId(teacherA)
    }

    @After
    fun tearDown() {
        OutboxSyncWorker.operationProcessor = null
        AccountSessionManager.setActiveTeacherId(null)
        SupabaseClientProvider.mockTeacherId = null
        db.close()
        DatabaseProvider.resetForTesting()
    }

    @Test
    fun testOnlineStudentCreation_returnsAuthoritativeServerCodeWithoutTmpOrRoom() = runBlocking {
        var nextCodeSeq = 1
        val fakeRemote = object : StudentRemoteDataSource {
            private val stored = mutableListOf<Student>()
            override suspend fun fetchStudents(teacherId: String) = stored.filter { it.teacherId == teacherId }
            override suspend fun fetchStudentById(teacherId: String, studentId: String) = stored.firstOrNull { it.studentId == studentId && it.teacherId == teacherId }
            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String) = stored.firstOrNull { it.studentCode == studentCode && it.teacherId == teacherId }
            override suspend fun insertStudent(dto: SupabaseStudentInsertDto): Student {
                val created = Student(
                    studentId = dto.id,
                    studentCode = "ST-%05d".format(nextCodeSeq++),
                    fullName = dto.fullName,
                    gradeId = dto.gradeId,
                    parentPhone = dto.parentPhone,
                    hasWhatsApp = dto.hasWhatsApp,
                    alternativePhone = dto.alternativePhone,
                    teacherId = dto.teacherId
                )
                stored.add(created)
                return created
            }
            override suspend fun updateStudent(teacherId: String, studentId: String, request: UpdateStudentOnlineDto) = error("unused")
            override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) {}
        }

        val repo = SupabaseStudentRepository(
            remoteDataSource = fakeRemote,
            teacherIdProvider = { teacherA },
            sessionActiveChecker = { true }
        )

        val result = repo.addStudent(
            fullName = "ياسين أحمد",
            gradeId = "grade_1",
            parentPhone = "01011112222",
            hasWhatsApp = true,
            alternativePhone = null
        )

        assertTrue(result.isSuccess)
        val syncResult = result.getOrNull()!!
        assertEquals(BatchSyncStatus.SAVED_TO_CLOUD, syncResult.syncStatus)
        assertEquals("ST-00001", syncResult.data.studentCode)
        assertFalse(syncResult.data.studentCode.contains("TMP"))

        // Verify lookup by server code succeeds in Online-Only repo and Room/Outbox stay empty
        val byCode = repo.getStudentByCode("ST-00001")
        assertNotNull(byCode)
        assertEquals(syncResult.data.studentId, byCode?.studentId)
        assertTrue(studentDao.getAllStudentsSync(teacherA).isEmpty())
        assertTrue(outboxDao.getPendingOperationsForTeacher(teacherA).isEmpty())
    }

    @Test
    fun testTwoOnlineStudentsCreatedCloseTogether_haveDistinctServerCodes() = runBlocking {
        var nextCodeSeq = 1
        val fakeRemote = object : StudentRemoteDataSource {
            private val stored = mutableListOf<Student>()
            override suspend fun fetchStudents(teacherId: String) = stored.toList()
            override suspend fun fetchStudentById(teacherId: String, studentId: String) = stored.firstOrNull { it.studentId == studentId }
            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String) = stored.firstOrNull { it.studentCode == studentCode }
            override suspend fun insertStudent(dto: SupabaseStudentInsertDto): Student {
                val created = Student(
                    studentId = dto.id,
                    studentCode = "ST-%05d".format(nextCodeSeq++),
                    fullName = dto.fullName,
                    gradeId = dto.gradeId,
                    parentPhone = dto.parentPhone,
                    hasWhatsApp = dto.hasWhatsApp,
                    alternativePhone = dto.alternativePhone,
                    teacherId = dto.teacherId
                )
                stored.add(created)
                return created
            }
            override suspend fun updateStudent(teacherId: String, studentId: String, request: UpdateStudentOnlineDto) = error("unused")
            override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) {}
        }

        val repo = SupabaseStudentRepository(
            remoteDataSource = fakeRemote,
            teacherIdProvider = { teacherA },
            sessionActiveChecker = { true }
        )

        val s1 = repo.addStudent("طالب أول", "grade_1", "01000000001", true, null).getOrNull()!!.data
        val s2 = repo.addStudent("طالب ثان", "grade_1", "01000000002", false, null).getOrNull()!!.data

        assertNotEquals(s1.studentId, s2.studentId)
        assertNotEquals(s1.studentCode, s2.studentCode)
        assertEquals("ST-00001", s1.studentCode)
        assertEquals("ST-00002", s2.studentCode)
        assertFalse(s1.studentCode.contains("TMP"))
        assertFalse(s2.studentCode.contains("TMP"))
    }

    @Test
    fun testOfflineStudentCreation_failsWithoutTemporaryCodeOrOutbox() = runBlocking {
        val offlineRemote = object : StudentRemoteDataSource {
            override suspend fun fetchStudents(teacherId: String) = throw UnknownHostException("offline")
            override suspend fun fetchStudentById(teacherId: String, studentId: String) = throw UnknownHostException("offline")
            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String) = throw UnknownHostException("offline")
            override suspend fun insertStudent(dto: SupabaseStudentInsertDto) = throw UnknownHostException("offline")
            override suspend fun updateStudent(teacherId: String, studentId: String, request: UpdateStudentOnlineDto) = throw UnknownHostException("offline")
            override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) = throw UnknownHostException("offline")
        }

        val repo = SupabaseStudentRepository(
            remoteDataSource = offlineRemote,
            teacherIdProvider = { teacherA },
            sessionActiveChecker = { true }
        )

        val addResult = repo.addStudent("طارق محمود", "grade_2", "01122334455", true, null)
        assertTrue(addResult.isFailure)
        assertTrue(repo.getStudents().first().isEmpty())
        assertTrue(studentDao.getAllStudentsSync(teacherA).isEmpty())
        assertTrue(outboxDao.getPendingOperationsForTeacher(teacherA).isEmpty())
    }

    @Test
    fun testExistingStudentCode_remainsStableDuringOnlineUpdates() = runBlocking {
        var serverStudent = Student(
            studentId = "s_stable_code",
            studentCode = "ST-00045",
            fullName = "الاسم القديم",
            gradeId = "grade_1",
            parentPhone = "01011111111",
            teacherId = teacherA
        )
        val fakeRemote = object : StudentRemoteDataSource {
            override suspend fun fetchStudents(teacherId: String) = listOf(serverStudent)
            override suspend fun fetchStudentById(teacherId: String, studentId: String) = serverStudent
            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String) = serverStudent
            override suspend fun insertStudent(dto: SupabaseStudentInsertDto) = serverStudent
            override suspend fun updateStudent(teacherId: String, studentId: String, request: UpdateStudentOnlineDto): Student {
                serverStudent = serverStudent.copy(
                    fullName = request.fullName,
                    gradeId = request.gradeId,
                    parentPhone = request.parentPhone,
                    hasWhatsApp = request.hasWhatsApp,
                    alternativePhone = request.alternativePhone
                )
                return serverStudent
            }
            override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) {}
        }

        val repo = SupabaseStudentRepository(
            remoteDataSource = fakeRemote,
            teacherIdProvider = { teacherA },
            sessionActiveChecker = { true }
        )

        val updateCandidate = serverStudent.copy(
            fullName = "الاسم المعدل",
            studentCode = ""
        )
        val updateResult = repo.updateStudent(updateCandidate)
        assertTrue(updateResult.isSuccess)
        val updated = updateResult.getOrNull()!!
        assertEquals("الاسم المعدل", updated.fullName)
        assertEquals("ST-00045", updated.studentCode)
    }

    @Test
    fun testPhase2OutboxWorkerRetry_andTenantIsolationStillWorkForRemainingOutboxEntities() = runBlocking {
        val opB = OutboxEntity(
            id = UUID.randomUUID().toString(),
            operationType = "UPSERT",
            entityType = "ATTENDANCE",
            entityId = "att_teacher_b",
            payload = "{}",
            createdAt = System.currentTimeMillis(),
            status = "PENDING",
            teacherId = teacherB
        )
        outboxDao.insertOperation(opB)

        val opA = OutboxEntity(
            id = UUID.randomUUID().toString(),
            operationType = "UPSERT",
            entityType = "ATTENDANCE",
            entityId = "att_teacher_a",
            payload = "{}",
            createdAt = System.currentTimeMillis(),
            status = "PENDING",
            teacherId = teacherA
        )
        outboxDao.insertOperation(opA)

        // Attempt 1 fails -> PENDING + retryCount=1, Teacher B untouched
        OutboxSyncWorker.operationProcessor = { throw IOException("503") }
        val worker1 = TestListenableWorkerBuilder<OutboxSyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), worker1.doWork())
        assertEquals(1, outboxDao.getOperationById(opA.id)?.retryCount)
        assertEquals("PENDING", outboxDao.getOperationById(opB.id)?.status)

        // Attempt 2 succeeds -> SYNCED for A, Teacher B untouched
        OutboxSyncWorker.operationProcessor = { }
        val worker2 = TestListenableWorkerBuilder<OutboxSyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker2.doWork())
        assertEquals("SYNCED", outboxDao.getOperationById(opA.id)?.status)
        assertEquals("PENDING", outboxDao.getOperationById(opB.id)?.status)
    }
}
