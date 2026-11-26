package com.example

import com.example.core.model.EducationalStages
import com.example.core.model.Grade
import com.example.core.model.OnlineLoadState
import com.example.core.model.Student
import com.example.core.model.SupabaseStudentInsertDto
import com.example.core.model.Teacher
import com.example.core.model.UpdateStudentOnlineDto
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.repository.GradeRemoteDataSource
import com.example.data.repository.StudentRemoteDataSource
import com.example.data.repository.SupabaseGradeRepository
import com.example.data.repository.SupabaseStudentRepository
import com.example.ui.students.StudentListViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.UnknownHostException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GradeOfflineCacheTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private var currentTeacherId: String? = "teacher_1"

    private class FakeGradeRemoteDataSource : GradeRemoteDataSource {
        val gradesByTeacher = mutableMapOf<String, MutableList<Grade>>()
        val teachers = mutableMapOf<String, Teacher>()
        val linkedStudentsByGrade = mutableSetOf<String>()
        val linkedGroupsByGrade = mutableSetOf<String>()
        var failFetch: Throwable? = null
        var failWrite: Throwable? = null

        override suspend fun fetchTeacherProfile(teacherId: String): Teacher? {
            failFetch?.let { throw it }
            return teachers[teacherId]
        }

        override suspend fun fetchGrades(teacherId: String): List<Grade> {
            failFetch?.let { throw it }
            return gradesByTeacher[teacherId].orEmpty().sortedBy { it.displayOrder }
        }

        override suspend fun fetchGradeById(teacherId: String, gradeId: String): Grade? {
            failFetch?.let { throw it }
            return gradesByTeacher[teacherId].orEmpty().firstOrNull { it.id == gradeId }
        }

        override suspend fun insertGrade(teacherId: String, id: String, name: String, displayOrder: Int): Grade {
            failWrite?.let { throw it }
            val created = Grade(id = id, name = name, displayOrder = displayOrder, teacherId = teacherId)
            val list = gradesByTeacher.getOrPut(teacherId) { mutableListOf() }
            list.removeAll { it.id == id }
            list.add(created)
            return created
        }

        override suspend fun updateGrade(teacherId: String, id: String, name: String, displayOrder: Int): Grade {
            failWrite?.let { throw it }
            val updated = Grade(id = id, name = name, displayOrder = displayOrder, teacherId = teacherId)
            val list = gradesByTeacher.getOrPut(teacherId) { mutableListOf() }
            list.removeAll { it.id == id }
            list.add(updated)
            return updated
        }

        override suspend fun updateGradeName(teacherId: String, id: String, name: String) {
            failWrite?.let { throw it }
            val list = gradesByTeacher[teacherId] ?: return
            val idx = list.indexOfFirst { it.id == id }
            if (idx >= 0) {
                list[idx] = list[idx].copy(name = name)
            }
        }

        override suspend fun hasStudentsInGrade(teacherId: String, gradeId: String): Boolean =
            linkedStudentsByGrade.contains("$teacherId:$gradeId")

        override suspend fun hasGroupsInGrade(teacherId: String, gradeId: String): Boolean =
            linkedGroupsByGrade.contains("$teacherId:$gradeId")

        override suspend fun deleteGrade(teacherId: String, id: String) {
            failWrite?.let { throw it }
            gradesByTeacher[teacherId]?.removeAll { it.id == id }
        }
    }

    private lateinit var gradeRemote: FakeGradeRemoteDataSource

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        currentTeacherId = "teacher_1"
        SupabaseClientProvider.mockTeacherId = "teacher_1"
        AccountSessionManager.setActiveTeacherId("teacher_1")
        gradeRemote = FakeGradeRemoteDataSource()
        gradeRemote.teachers["teacher_1"] = Teacher(
            id = "teacher_1",
            email = "t1@midar.app",
            fullName = "أستاذ 1",
            educationalStage = EducationalStages.PREPARATORY
        )
    }

    @After
    fun tearDown() {
        AccountSessionManager.setActiveTeacherId(null)
        SupabaseClientProvider.mockTeacherId = null
        Dispatchers.resetMain()
    }

    private fun buildGradeRepo(teacherProvider: () -> String? = { currentTeacherId }): SupabaseGradeRepository {
        return SupabaseGradeRepository(
            remoteDataSource = gradeRemote,
            teacherIdProvider = teacherProvider,
            teacherStageMetadataProvider = { null },
            sessionActiveChecker = { id -> teacherProvider() == id },
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun test1_onlineGradesAreFetchedDirectlyIntoSessionMemory() = runTest {
        gradeRemote.gradesByTeacher["teacher_1"] = mutableListOf(
            Grade(id = "grade-1", name = "الصف الأول الإعدادي", displayOrder = 1, teacherId = "teacher_1"),
            Grade(id = "grade-2", name = "الصف الثاني الإعدادي", displayOrder = 2, teacherId = "teacher_1"),
            Grade(id = "grade-3", name = "الصف الثالث الإعدادي", displayOrder = 3, teacherId = "teacher_1")
        )
        val repo = buildGradeRepo()

        val result = repo.refreshGradesResult()
        assertTrue(result.isSuccess)

        val emitted = repo.getGrades().first()
        assertEquals(3, emitted.size)
        assertEquals("grade-1", emitted[0].id)
        assertEquals("الصف الأول الإعدادي", emitted[0].name)
        assertEquals("grade-2", emitted[1].id)
    }

    @Test
    fun test2_offlineOrUnauthenticatedSessionStartsEmptyWithoutDiskCache() = runTest {
        gradeRemote.failFetch = UnknownHostException("Unable to resolve host")
        val repo = buildGradeRepo()

        val result = repo.refreshGradesResult()
        assertTrue(result.isFailure)
        assertTrue(repo.getGrades().first().isEmpty())

        currentTeacherId = null
        assertTrue(repo.getGrades().first().isEmpty())
    }

    @Test
    fun test3_networkFailurePreservesInMemorySessionUntilSignOut() = runTest {
        gradeRemote.gradesByTeacher["teacher_1"] = mutableListOf(
            Grade(id = "g1", name = "الصف الأول الثانوي", displayOrder = 1, teacherId = "teacher_1"),
            Grade(id = "g2", name = "الصف الثاني الثانوي", displayOrder = 2, teacherId = "teacher_1"),
            Grade(id = "g3", name = "الصف الثالث الثانوي", displayOrder = 3, teacherId = "teacher_1")
        )
        gradeRemote.teachers["teacher_1"] = Teacher(
            id = "teacher_1",
            educationalStage = EducationalStages.SECONDARY
        )
        val repo = buildGradeRepo()

        assertTrue(repo.refreshGradesResult(EducationalStages.SECONDARY).isSuccess)
        assertEquals(3, repo.getGrades().first().size)

        gradeRemote.failFetch = UnknownHostException("offline")
        val failedRefresh = repo.refreshGradesResult(EducationalStages.SECONDARY)
        assertTrue(failedRefresh.isFailure)
        assertEquals(3, repo.getGrades().first().size)

        // On session end / clearCache(), in-memory state is wiped immediately
        repo.clearCache()
        assertTrue(repo.getGrades().first().isEmpty())
    }

    @Test
    fun test4_offlineGradesAndStudentsBothShowOnlineOnlyFailureState() = runTest {
        currentTeacherId = "teacher_100"
        SupabaseClientProvider.mockTeacherId = "teacher_100"
        AccountSessionManager.setActiveTeacherId("teacher_100")

        gradeRemote.failFetch = UnknownHostException("offline")
        val offlineStudentRemote = object : StudentRemoteDataSource {
            override suspend fun fetchStudents(teacherId: String): List<Student> = throw UnknownHostException("offline")
            override suspend fun fetchStudentById(teacherId: String, studentId: String): Student? = throw UnknownHostException("offline")
            override suspend fun fetchStudentByCode(teacherId: String, studentCode: String): Student? = throw UnknownHostException("offline")
            override suspend fun insertStudent(dto: SupabaseStudentInsertDto): Student = throw UnknownHostException("offline")
            override suspend fun updateStudent(teacherId: String, studentId: String, request: UpdateStudentOnlineDto): Student = throw UnknownHostException("offline")
            override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) = throw UnknownHostException("offline")
        }

        val gradeRepo = buildGradeRepo()
        val studentRepo = SupabaseStudentRepository(
            remoteDataSource = offlineStudentRemote,
            teacherIdProvider = { currentTeacherId },
            sessionActiveChecker = { id -> currentTeacherId == id },
            ioDispatcher = testDispatcher
        )

        val viewModel = StudentListViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            defaultDispatcher = testDispatcher
        )
        advanceUntilIdle()

        assertTrue(gradeRepo.getGrades().first().isEmpty())
        assertTrue(studentRepo.getStudents().first().isEmpty())
        assertTrue(viewModel.uiState.value.loadState is OnlineLoadState.Offline)
        assertTrue(viewModel.uiState.value.isOfflineError)
    }

    @Test
    fun test5_teacherTenantIsolation() = runTest {
        gradeRemote.teachers["teacher_A"] = Teacher(id = "teacher_A", educationalStage = EducationalStages.PREPARATORY)
        gradeRemote.teachers["teacher_B"] = Teacher(id = "teacher_B", educationalStage = EducationalStages.PRIMARY)
        gradeRemote.gradesByTeacher["teacher_A"] = mutableListOf(
            Grade(id = "g_a1", name = "الصف الأول الإعدادي", displayOrder = 1, teacherId = "teacher_A"),
            Grade(id = "g_a2", name = "الصف الثاني الإعدادي", displayOrder = 2, teacherId = "teacher_A"),
            Grade(id = "g_a3", name = "الصف الثالث الإعدادي", displayOrder = 3, teacherId = "teacher_A")
        )
        gradeRemote.gradesByTeacher["teacher_B"] = mutableListOf(
            Grade(id = "g_b1", name = "الصف الأول الابتدائي", displayOrder = 1, teacherId = "teacher_B")
        )

        val repo = buildGradeRepo()

        currentTeacherId = "teacher_A"
        assertTrue(repo.refreshGradesResult(EducationalStages.PREPARATORY).isSuccess)
        assertTrue(repo.getGrades().first().all { it.teacherId == "teacher_A" })
        assertTrue(repo.getGrades().first().any { it.id == "g_a1" })

        // Switching active teacher immediately isolates the flow and wipes old session data
        currentTeacherId = "teacher_B"
        assertTrue(repo.getGrades().first().isEmpty())

        assertTrue(repo.refreshGradesResult(EducationalStages.PRIMARY).isSuccess)
        val bGrades = repo.getGrades().first()
        assertFalse(bGrades.any { it.id == "g_a1" })
        assertTrue(bGrades.any { it.id == "g_b1" })
    }

    @Test
    fun test6_onlineGradeNormalizationAndCrudPreserveConsistency() = runTest {
        gradeRemote.teachers["t-1"] = Teacher(id = "t-1", educationalStage = EducationalStages.SECONDARY)
        gradeRemote.gradesByTeacher["t-1"] = mutableListOf(
            Grade(id = "g-pres", name = "الصف الثالث الثانوي", displayOrder = 3, teacherId = "t-1")
        )
        currentTeacherId = "t-1"
        val repo = buildGradeRepo()

        val grades = repo.refreshGradesResult(EducationalStages.SECONDARY).getOrThrow()
        assertTrue(grades.any { it.id == "g-pres" })

        val fetchedGrade = repo.getGradeById("g-pres")
        assertNotNull(fetchedGrade)
        assertEquals("الصف الثالث الثانوي", fetchedGrade?.name)

        // Update name in place
        assertTrue(repo.updateGradeResult(fetchedGrade!!.copy(name = "الصف الثالث الثانوي - متقدم")).isSuccess)
        assertEquals("الصف الثالث الثانوي - متقدم", repo.getGradeById("g-pres")?.name)
    }

    @Test
    fun test7_processRestartStartsWithEmptySessionMemoryInOnlineOnlyMode() = runTest {
        gradeRemote.gradesByTeacher["t_restart"] = mutableListOf(
            Grade(id = "grade-restart", name = "الصف التجريبي", displayOrder = 1, teacherId = "t_restart")
        )
        currentTeacherId = "t_restart"
        val repo1 = buildGradeRepo()
        assertTrue(repo1.addGradeResult(Grade(id = "g-added", name = "صف مضاف", displayOrder = 4, teacherId = "t_restart")).isSuccess)
        assertTrue(repo1.getGrades().first().isNotEmpty())

        // Simulate app restart with a fresh repository instance before any remote fetch completes
        val repo2 = buildGradeRepo()
        assertTrue(repo2.getGrades().first().isEmpty())

        // Once offline on restart, no stale grade cache appears
        gradeRemote.failFetch = UnknownHostException("offline")
        assertTrue(repo2.refreshGradesResult().isFailure)
        assertTrue(repo2.getGrades().first().isEmpty())
    }
}
