package com.example

import com.example.core.model.Attendance
import com.example.core.model.AttendanceStatus
import com.example.core.model.BatchSyncStatus
import com.example.core.model.EducationalStages
import com.example.core.model.Group
import com.example.core.model.Student
import com.example.core.model.Teacher
import com.example.core.model.UpsertAttendanceRequest
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.data.repository.AttendanceRemoteDataSource
import com.example.data.repository.AttendanceRepository
import com.example.data.repository.MockAttendanceRepository
import com.example.data.repository.MockGradeRepository
import com.example.data.repository.MockGroupRepository
import com.example.data.repository.MockPaymentRepository
import com.example.data.repository.MockStudentRepository
import com.example.data.repository.MockTeacherRepository
import com.example.data.repository.SupabaseAttendanceRepository
import com.example.ui.attendance.FastAttendanceViewModel
import com.example.ui.attendance.ScanResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.robolectric.annotation.Config
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceByGroupTest {

    private val testDispatcher = StandardTestDispatcher()
    private val currentTeacherId = "teacher_owner_123"
    private val otherTeacherId = "teacher_other_999"

    private val groupA = Group(
        id = "grp_A",
        teacherId = currentTeacherId,
        gradeId = "grade_1",
        name = "نجوم المستقبل",
        startTime = "10:00",
        endTime = "11:30"
    )

    private val groupB = Group(
        id = "grp_B",
        teacherId = currentTeacherId,
        gradeId = "grade_1",
        name = "رواد الغد",
        startTime = "12:00",
        endTime = "13:30"
    )

    private val groupForeign = Group(
        id = "grp_Foreign",
        teacherId = otherTeacherId,
        gradeId = "grade_1",
        name = "مجموعة مدرس آخر",
        startTime = "14:00",
        endTime = "15:30"
    )

    private lateinit var teacherRepo: MockTeacherRepository
    private lateinit var groupRepo: MockGroupRepository
    private lateinit var gradeRepo: MockGradeRepository
    private lateinit var studentRepo: MockStudentRepository
    private lateinit var attendanceRepo: MockAttendanceRepository
    private lateinit var paymentRepo: MockPaymentRepository

    private val studentA1 = Student(
        studentId = "std_a1",
        teacherId = currentTeacherId,
        gradeId = "grade_1",
        groupId = "grp_A",
        fullName = "أحمد محمد (مجموعة أ)",
        studentCode = "BAR_A1"
    )

    private val studentA2 = Student(
        studentId = "std_a2",
        teacherId = currentTeacherId,
        gradeId = "grade_1",
        groupId = "grp_A",
        fullName = "إبراهيم حسن (مجموعة أ)",
        studentCode = "BAR_A2"
    )

    private val studentB1 = Student(
        studentId = "std_b1",
        teacherId = currentTeacherId,
        gradeId = "grade_1",
        groupId = "grp_B",
        fullName = "خالد عمر (مجموعة ب)",
        studentCode = "BAR_B1"
    )

    private val studentForeign = Student(
        studentId = "std_foreign",
        teacherId = otherTeacherId,
        gradeId = "grade_1",
        groupId = "grp_Foreign",
        fullName = "طالب غريب لمدرس آخر",
        studentCode = "BAR_FOR"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        SupabaseClientProvider.mockTeacherId = currentTeacherId
        AccountSessionManager.setActiveTeacherId(currentTeacherId)

        val currentTeacher = Teacher(id = currentTeacherId, email = "owner@test.com", fullName = "الأستاذ أحمد")
        teacherRepo = MockTeacherRepository(initialTeacher = currentTeacher)
        groupRepo = MockGroupRepository(initialGroups = listOf(groupA, groupB, groupForeign))
        gradeRepo = MockGradeRepository(initialStage = EducationalStages.SECONDARY)
        studentRepo = MockStudentRepository(gradeRepository = gradeRepo)
        attendanceRepo = MockAttendanceRepository()
        paymentRepo = MockPaymentRepository()
    }

    @After
    fun tearDown() {
        AccountSessionManager.setActiveTeacherId(null)
        SupabaseClientProvider.mockTeacherId = null
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        customAttendanceRepo: AttendanceRepository = attendanceRepo
    ): FastAttendanceViewModel {
        return FastAttendanceViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            attendanceRepository = customAttendanceRepo,
            paymentRepository = paymentRepo,
            groupRepository = groupRepo,
            teacherRepository = teacherRepo
        )
    }

    @Test
    fun test1_openAttendanceFromGroupA_setsGroupContext() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setStudentsForScope(listOf(studentA1, studentA2), listOf(studentA1, studentA2, studentB1))
        vm.setInitialGroupId("grp_A", "نجوم المستقبل")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("grp_A", state.selectedGroupId)
        assertEquals("نجوم المستقبل", state.groupName)
        assertNotNull(state.currentDate)
    }

    @Test
    fun test2_onlyGroupAStudentsAppear() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val allStudents = listOf(studentA1, studentA2, studentB1)
        vm.setStudentsForScope(listOf(studentA1, studentA2), allStudents)
        vm.setInitialGroupId("grp_A", "نجوم المستقبل")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.allStudentsInScope.size)
        assertTrue(state.allStudentsInScope.any { it.studentId == "std_a1" })
        assertTrue(state.allStudentsInScope.any { it.studentId == "std_a2" })
    }

    @Test
    fun test3_groupBStudentDoesNotExistInGroupAScope() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val allStudents = listOf(studentA1, studentA2, studentB1)
        vm.setStudentsForScope(listOf(studentA1, studentA2), allStudents)
        vm.setInitialGroupId("grp_A", "نجوم المستقبل")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.allStudentsInScope.any { it.studentId == "std_b1" })
        assertFalse(state.filteredStudents.any { it.studentId == "std_b1" })
    }

    @Test
    fun test4_barcodeScan_groupAStudent_marksPresent() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val allStudents = listOf(studentA1, studentA2, studentB1)
        vm.setStudentsForScope(listOf(studentA1, studentA2), allStudents)
        vm.setInitialGroupId("grp_A", "نجوم المستقبل")
        vm.toggleScanner(true)
        advanceUntilIdle()

        vm.processScannedBarcode("BAR_A1")
        val state = vm.uiState.value
        assertEquals(ScanResultType.SUCCESS_PRESENT, state.scanFeedback?.type)
        assertTrue(state.presentStudentIds.contains("std_a1"))
        assertEquals(1, state.presentStudentIds.size)
    }

    @Test
    fun test5_barcodeScan_groupBStudent_rejectedWithWrongGroup() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val allStudents = listOf(studentA1, studentA2, studentB1)
        vm.setStudentsForScope(listOf(studentA1, studentA2), allStudents)
        vm.setInitialGroupId("grp_A", "نجوم المستقبل")
        vm.toggleScanner(true)
        advanceUntilIdle()

        vm.processScannedBarcode("BAR_B1")
        val state = vm.uiState.value
        assertEquals(ScanResultType.SUCCESS_PRESENT, state.scanFeedback?.type)
        assertTrue(state.presentStudentIds.contains("std_b1"))
        assertTrue(state.extraSessionStudents.any { it.studentId == "std_b1" })
        assertEquals(1, state.presentStudentIds.size)

        val student = allStudents.find { it.studentId == "std_b1" }
        assertEquals("grp_B", student?.groupId)
    }

    @Test
    fun test6_manualAttendance_togglePresentAndAbsent() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val allStudents = listOf(studentA1, studentA2)
        vm.setStudentsForScope(allStudents, allStudents)
        vm.setInitialGroupId("grp_A", "نجوم المستقبل")
        advanceUntilIdle()

        vm.toggleStudentPresent("std_a1")
        assertTrue(vm.uiState.value.presentStudentIds.contains("std_a1"))

        vm.toggleStudentPresent("std_a1")
        assertFalse(vm.uiState.value.presentStudentIds.contains("std_a1"))

        vm.toggleStudentPresent("std_a1")
        vm.finishAttendance()
        advanceUntilIdle()

        val summary = vm.uiState.value.saveSummary
        assertNotNull(summary)
        assertEquals(1, summary?.presentCount)
        assertEquals(1, summary?.absentCount)
        assertEquals(2, summary?.totalCount)
    }

    @Test
    fun test7_offlineAttendance_failsGracefullyWithoutOfflineQueueOrSummary() = runTest {
        val offlineRemote = object : AttendanceRemoteDataSource {
            override suspend fun fetchAttendanceForStudent(teacherId: String, studentId: String) = throw UnknownHostException("offline")
            override suspend fun fetchAttendanceForStudentByMonth(teacherId: String, studentId: String, startDate: String, endDate: String) = throw UnknownHostException("offline")
            override suspend fun fetchAllAttendanceForTeacher(teacherId: String) = throw UnknownHostException("offline")
            override suspend fun fetchTodayCountByStatus(teacherId: String, date: String, status: AttendanceStatus) = throw UnknownHostException("offline")
            override suspend fun fetchStudentGroupId(teacherId: String, studentId: String): String? = null
            override suspend fun upsertAttendance(request: UpsertAttendanceRequest) = throw UnknownHostException("offline")
            override suspend fun upsertBatchAttendance(requests: List<UpsertAttendanceRequest>) = throw UnknownHostException("offline")
            override suspend fun deleteAttendanceByStudentAndDate(teacherId: String, studentId: String, date: String) = throw UnknownHostException("offline")
            override suspend fun deleteAttendanceById(teacherId: String, attendanceId: String) = throw UnknownHostException("offline")
            override suspend fun fetchAttendanceByDate(teacherId: String, studentId: String, date: String) = throw UnknownHostException("offline")
            override suspend fun fetchAttendanceForDate(teacherId: String, date: String) = emptyList<Attendance>()
        }

        val onlineOnlyRepo = SupabaseAttendanceRepository(
            remoteDataSource = offlineRemote,
            teacherIdProvider = { currentTeacherId },
            sessionActiveChecker = { true },
            ioDispatcher = testDispatcher
        )

        val vm = createViewModel(customAttendanceRepo = onlineOnlyRepo)
        advanceUntilIdle()

        val allStudents = listOf(studentA1, studentA2)
        vm.setStudentsForScope(allStudents, allStudents)
        vm.setInitialGroupId("grp_A", "نجوم المستقبل")
        advanceUntilIdle()

        vm.toggleStudentPresent("std_a1")
        vm.toggleStudentPresent("std_a2")
        vm.finishAttendance()
        advanceUntilIdle()

        assertNull("Online-Only mode must not show save summary on offline failure", vm.uiState.value.saveSummary)
        assertNotNull(vm.uiState.value.errorMessage)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun test8_onlineAttendance_preservesGroupIdSnapshot() = runTest {
        val capturedRequests = mutableListOf<UpsertAttendanceRequest>()
        val fakeRemote = object : AttendanceRemoteDataSource {
            override suspend fun fetchAttendanceForStudent(teacherId: String, studentId: String) = emptyList<Attendance>()
            override suspend fun fetchAttendanceForStudentByMonth(teacherId: String, studentId: String, startDate: String, endDate: String) = emptyList<Attendance>()
            override suspend fun fetchAllAttendanceForTeacher(teacherId: String) = emptyList<Attendance>()
            override suspend fun fetchTodayCountByStatus(teacherId: String, date: String, status: AttendanceStatus) = 0
            override suspend fun fetchStudentGroupId(teacherId: String, studentId: String): String? = "grp_A"
            override suspend fun upsertAttendance(request: UpsertAttendanceRequest): Attendance {
                capturedRequests.add(request)
                return Attendance(
                    attendanceId = "${request.studentId}_${request.date}",
                    studentId = request.studentId,
                    teacherId = request.teacherId,
                    groupId = request.groupId,
                    date = request.date,
                    status = AttendanceStatus.fromString(request.status),
                    note = request.note
                )
            }
            override suspend fun upsertBatchAttendance(requests: List<UpsertAttendanceRequest>): List<Attendance> {
                capturedRequests.addAll(requests)
                return requests.map {
                    Attendance(
                        attendanceId = "${it.studentId}_${it.date}",
                        studentId = it.studentId,
                        teacherId = it.teacherId,
                        groupId = it.groupId,
                        date = it.date,
                        status = AttendanceStatus.fromString(it.status),
                        note = it.note
                    )
                }
            }
            override suspend fun deleteAttendanceByStudentAndDate(teacherId: String, studentId: String, date: String) {}
            override suspend fun deleteAttendanceById(teacherId: String, attendanceId: String) {}
            override suspend fun fetchAttendanceByDate(teacherId: String, studentId: String, date: String): Attendance? = null
            override suspend fun fetchAttendanceForDate(teacherId: String, date: String) = emptyList<Attendance>()
        }

        val repo = SupabaseAttendanceRepository(
            remoteDataSource = fakeRemote,
            teacherIdProvider = { currentTeacherId },
            sessionActiveChecker = { true },
            ioDispatcher = testDispatcher
        )

        val date = "2026-09-25"
        val result = repo.recordOrUpdateAttendance(
            studentId = studentA1.studentId,
            date = date,
            status = AttendanceStatus.PRESENT,
            note = null,
            groupId = "grp_A"
        )

        assertTrue(result.isSuccess)
        assertEquals(BatchSyncStatus.SAVED_TO_CLOUD, result.getOrNull()?.syncStatus)
        assertEquals(1, capturedRequests.size)
        assertEquals("grp_A", capturedRequests[0].groupId)
        assertEquals(currentTeacherId, capturedRequests[0].teacherId)
        assertEquals(studentA1.studentId, capturedRequests[0].studentId)
        assertEquals(AttendanceStatus.PRESENT.name, capturedRequests[0].status)
    }

    @Test
    fun test9_duplicateAttendancePrevention_returnsAlreadyPresent() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val allStudents = listOf(studentA1, studentA2)
        vm.setStudentsForScope(allStudents, allStudents)
        vm.setInitialGroupId("grp_A", "نجوم المستقبل")
        vm.toggleScanner(true)
        advanceUntilIdle()

        vm.processScannedBarcode("BAR_A1")
        assertEquals(ScanResultType.SUCCESS_PRESENT, vm.uiState.value.scanFeedback?.type)
        assertEquals(1, vm.uiState.value.presentStudentIds.size)

        vm.processScannedBarcode("BAR_A1")
        assertEquals(ScanResultType.ALREADY_PRESENT, vm.uiState.value.scanFeedback?.type)
        assertEquals(1, vm.uiState.value.presentStudentIds.size)
    }

    @Test
    fun test10_teacherIsolation_foreignGroupOrStudentRejected() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setInitialGroupId("grp_Foreign", "مجموعة مدرس آخر")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("لا يمكن الوصول لهذه المجموعة: غير مصرح أو غير موجودة.", state.loadError)
        assertEquals(0, state.allStudentsInScope.size)

        val vm2 = createViewModel()
        advanceUntilIdle()
        vm2.setStudentsForScope(listOf(studentA1), listOf(studentA1, studentForeign))
        vm2.setInitialGroupId("grp_A", "نجوم المستقبل")
        vm2.toggleScanner(true)
        advanceUntilIdle()

        vm2.processScannedBarcode("BAR_FOR")
        assertEquals(ScanResultType.NOT_FOUND, vm2.uiState.value.scanFeedback?.type)
        assertFalse(vm2.uiState.value.presentStudentIds.contains("std_foreign"))
    }
}
