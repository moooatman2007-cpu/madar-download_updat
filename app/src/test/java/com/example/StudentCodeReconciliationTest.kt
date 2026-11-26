package com.example

import com.example.core.model.BatchSyncStatus
import com.example.core.model.Student
import com.example.core.model.SupabaseStudentInsertDto
import com.example.core.model.UpdateStudentOnlineDto
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.repository.StudentRemoteDataSource
import com.example.data.repository.SupabaseStudentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StudentCodeReconciliationTest {

    private val teacherA = "teacher_alpha_test"
    private val teacherB = "teacher_beta_test"
    private var activeTeacherId: String? = teacherA

    @Before
    fun setUp() {
        activeTeacherId = teacherA
        SupabaseClientProvider.mockTeacherId = teacherA
        AccountSessionManager.setActiveTeacherId(teacherA)
    }

    @After
    fun tearDown() {
        AccountSessionManager.setActiveTeacherId(null)
        SupabaseClientProvider.mockTeacherId = null
    }

    @Test
    fun testOnlineStudentCreation_returnsAuthoritativeServerCodeWithoutTmpOrRoom() = runBlocking {
        var nextCodeSeq = 1
        val fakeRemote = object : StudentRemoteDataSource {
            private val stored = mutableListOf<Student>()
            override suspend fun fetchStudents(teacherId: String) = stored.filter { it.teacherId == teacherId }
            override suspend fun fetchStudentById(teacherId: String, studentId: String) =
                stored.firstOrNull { it.studentId == studentId && it.teacherId == teacherId }
            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String) =
                stored.firstOrNull { it.studentCode == studentCode && it.teacherId == teacherId }
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
            teacherIdProvider = { activeTeacherId },
            sessionActiveChecker = { id -> activeTeacherId == id }
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

        // Verify lookup by server code succeeds directly via Supabase remote source
        val byCode = repo.getStudentByCode("ST-00001")
        assertNotNull(byCode)
        assertEquals(syncResult.data.studentId, byCode?.studentId)

        // Clearing in-memory session cache leaves no persistent store behind
        repo.clearCache()
        assertTrue(repo.getStudents().first().isEmpty())
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
            teacherIdProvider = { activeTeacherId },
            sessionActiveChecker = { id -> activeTeacherId == id }
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
    fun testOfflineStudentCreation_failsWithoutTemporaryCodeOrLocalQueue() = runBlocking {
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
            teacherIdProvider = { activeTeacherId },
            sessionActiveChecker = { id -> activeTeacherId == id }
        )

        val addResult = repo.addStudent("طارق محمود", "grade_2", "01122334455", true, null)
        assertTrue(addResult.isFailure)
        assertTrue(repo.getStudents().first().isEmpty())
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
            teacherIdProvider = { activeTeacherId },
            sessionActiveChecker = { id -> activeTeacherId == id }
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
    fun testOnlineRetryAfterTransientFailure_andTenantIsolation() = runBlocking {
        var failInsert = true
        val remoteStorage = linkedMapOf<String, Student>()
        var seq = 1

        val fakeRemote = object : StudentRemoteDataSource {
            override suspend fun fetchStudents(teacherId: String) = remoteStorage.values.filter { it.teacherId == teacherId }
            override suspend fun fetchStudentById(teacherId: String, studentId: String) =
                remoteStorage[studentId]?.takeIf { it.teacherId == teacherId }
            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String) =
                remoteStorage.values.firstOrNull { it.teacherId == teacherId && it.studentCode == studentCode }
            override suspend fun insertStudent(dto: SupabaseStudentInsertDto): Student {
                if (failInsert) throw IOException("503")
                val s = Student(
                    studentId = dto.id,
                    studentCode = "ST-%05d".format(seq++),
                    fullName = dto.fullName,
                    gradeId = dto.gradeId,
                    parentPhone = dto.parentPhone,
                    teacherId = dto.teacherId
                )
                remoteStorage[s.studentId] = s
                return s
            }
            override suspend fun updateStudent(teacherId: String, studentId: String, request: UpdateStudentOnlineDto) = error("unused")
            override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) {}
        }

        // Seed Teacher B student on server
        remoteStorage["s_b"] = Student(
            studentId = "s_b",
            studentCode = "ST-B001",
            fullName = "طالب ب",
            gradeId = "g_1",
            parentPhone = "01022223333",
            teacherId = teacherB
        )

        val repo = SupabaseStudentRepository(
            remoteDataSource = fakeRemote,
            teacherIdProvider = { activeTeacherId },
            sessionActiveChecker = { id -> activeTeacherId == id }
        )

        // Attempt 1 fails -> zero local queue or phantom students; Teacher B untouched
        val attempt1 = repo.addStudent("طالب أ", "g_1", "01011112222", true, null)
        assertTrue(attempt1.isFailure)
        assertTrue(repo.getStudents().first().isEmpty())
        assertEquals(1, remoteStorage.size)

        // Attempt 2 succeeds cleanly -> authoritative server code, Teacher B still isolated
        failInsert = false
        val attempt2 = repo.addStudent("طالب أ", "g_1", "01011112222", true, null)
        assertTrue(attempt2.isSuccess)
        assertEquals("ST-00001", attempt2.getOrNull()?.data?.studentCode)
        assertEquals(listOf("طالب أ"), repo.getStudents().first().map { it.fullName })
        assertTrue(repo.updateStudent(remoteStorage["s_b"]!!.copy(fullName = "اختراق")).exceptionOrNull() is SecurityException)
    }
}
