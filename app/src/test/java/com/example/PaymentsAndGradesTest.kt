package com.example

import com.example.core.model.Attendance
import com.example.core.model.AttendanceStatus
import com.example.core.model.EducationalStages
import com.example.core.model.Grade
import com.example.core.model.LessonPayment
import com.example.core.model.Student
import com.example.core.model.Teacher
import com.example.data.repository.MockAttendanceRepository
import com.example.data.repository.MockExamRepository
import com.example.data.repository.MockGradeRepository
import com.example.data.repository.MockGroupRepository
import com.example.data.repository.MockHomeworkRepository
import com.example.data.repository.MockMonthlyReportRepository
import com.example.data.repository.MockNotificationEventRepository
import com.example.data.repository.MockPaymentRepository
import com.example.data.repository.MockRecitationRepository
import com.example.data.repository.MockSettingsRepository
import com.example.data.repository.MockStudentRepository
import com.example.data.repository.MockTeacherRepository
import com.example.ui.grades.GradesEvent
import com.example.ui.grades.GradesViewModel
import com.example.ui.student_detail.StudentDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PaymentsAndGradesTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testStudentDetailMonthNavigationDoesNotSnapBackOnAttendanceMutation() = runTest {
        val student = Student(
            studentId = "s_01",
            studentCode = "ST-00001",
            fullName = "أحمد علي",
            gradeId = "grade_1"
        )
        val studentRepo = MockStudentRepository(customStudents = listOf(student))
        val gradeRepo = MockGradeRepository()
        val attRepo = MockAttendanceRepository()
        val recRepo = MockRecitationRepository()
        val exRepo = MockExamRepository()
        val hwRepo = MockHomeworkRepository()
        val payRepo = MockPaymentRepository()
        val settingsRepo = MockSettingsRepository()
        val monthlyRepo = MockMonthlyReportRepository(studentRepo, gradeRepo, attRepo, recRepo, exRepo)

        val vm = StudentDetailViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            attendanceRepository = attRepo,
            recitationRepository = recRepo,
            examRepository = exRepo,
            monthlyReportRepository = monthlyRepo,
            homeworkRepository = hwRepo,
            paymentRepository = payRepo,
            groupRepository = MockGroupRepository(),
            notificationEventRepository = MockNotificationEventRepository(),
            settingsRepository = settingsRepo
        )

        vm.loadStudent("s_01")
        testDispatcher.scheduler.advanceUntilIdle()

        // Navigate explicitly to October 2026 (even if September 2026 has mock data)
        vm.setMonthYear(month = 10, year = 2026)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(10, vm.uiState.value.selectedMonth)
        assertEquals(2026, vm.uiState.value.selectedYear)

        // Mutate attendance in October -> flow re-emits, month MUST stay at 10/2026!
        attRepo.recordOrUpdateAttendance("s_01", "2026-10-05", AttendanceStatus.PRESENT)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(10, vm.uiState.value.selectedMonth)
        assertEquals(2026, vm.uiState.value.selectedYear)
    }

    @Test
    fun testStudentDetailToggleAndSavePaymentUsesDefaultFeeAndYearFilter() = runTest {
        val student = Student(studentId = "s_01", studentCode = "ST-00001", fullName = "أحمد", gradeId = "grade_1")
        val studentRepo = MockStudentRepository(customStudents = listOf(student))
        val gradeRepo = MockGradeRepository()
        val attRepo = MockAttendanceRepository()
        val recRepo = MockRecitationRepository()
        val exRepo = MockExamRepository()
        val hwRepo = MockHomeworkRepository()
        val payRepo = MockPaymentRepository()
        val settingsRepo = MockSettingsRepository().apply {
            setDefaultMonthlyFee(250.0)
        }
        val monthlyRepo = MockMonthlyReportRepository(studentRepo, gradeRepo, attRepo, recRepo, exRepo)

        val vm = StudentDetailViewModel(
            studentRepository = studentRepo,
            gradeRepository = gradeRepo,
            attendanceRepository = attRepo,
            recitationRepository = recRepo,
            examRepository = exRepo,
            monthlyReportRepository = monthlyRepo,
            homeworkRepository = hwRepo,
            paymentRepository = payRepo,
            groupRepository = MockGroupRepository(),
            notificationEventRepository = MockNotificationEventRepository(),
            settingsRepository = settingsRepo
        )

        vm.loadStudent("s_01")
        vm.setMonthYear(month = 11, year = 2026)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(250.0, vm.uiState.value.defaultMonthlyFee, 0.01)

        // Toggling unpaid month picks up defaultMonthlyFee (250.0)
        vm.toggleMonthlyPayment()
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.isPaymentPaid)
        assertEquals(250.0, vm.uiState.value.monthlyPayment?.amount ?: 0.0, 0.01)
        assertEquals(1, vm.uiState.value.studentPaymentHistory.size)

        // Save explicit custom fee for another month and test year filter
        vm.saveMonthlyPayment(year = 2025, month = 12, isPaid = true, amount = 300.0)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.studentPaymentHistory.size)

        vm.setPaymentYearFilter(2025)
        assertEquals(2025, vm.uiState.value.selectedPaymentYearFilter)
    }

    @Test
    fun testGradesViewModelCrudDuplicateAndNonEmptyDeleteGuard() = runTest {
        val gradeRepo = MockGradeRepository(EducationalStages.PREPARATORY)
        val student = Student(studentId = "s_g1", studentCode = "ST-1", fullName = "طالب", gradeId = "grade_prep_1")
        val studentRepo = MockStudentRepository(gradeRepository = gradeRepo, customStudents = listOf(student))
        val teacherRepo = MockTeacherRepository(
            Teacher(id = "t_grades", fullName = "المدرس", educationalStage = EducationalStages.PREPARATORY)
        )

        val vm = GradesViewModel(
            gradeRepository = gradeRepo,
            studentRepository = studentRepo,
            teacherRepository = teacherRepo
        )
        val messages = mutableListOf<String>()
        val collectJob = launch {
            vm.events.collect { ev ->
                if (ev is GradesEvent.ShowMessage) messages += ev.message
            }
        }
        testDispatcher.scheduler.advanceUntilIdle()

        val gradeWithStudent = vm.uiState.value.grades.first { it.id == "grade_prep_1" }
        assertEquals(1, gradeWithStudent.studentCount)

        // Trying to delete a grade with students must be blocked
        vm.requestDeleteGrade(gradeWithStudent)
        testDispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.uiState.value.gradeToDelete)
        assertTrue(messages.any { it.contains("لا يمكن حذف") })

        // Duplicate name rejected
        vm.openAddDialog()
        vm.onGradeNameChange(gradeWithStudent.name)
        vm.saveGrade()
        assertTrue(vm.uiState.value.inputError?.contains("بنفس الاسم") == true)

        // Add new grade
        vm.onGradeNameChange("تمهيدي")
        vm.onGradeOrderChange("4")
        vm.saveGrade()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.showAddEditDialog)
        val addedGrade = vm.uiState.value.grades.firstOrNull { it.name == "تمهيدي" }
        assertTrue(addedGrade != null)

        // Edit added grade
        vm.openEditDialog(addedGrade!!)
        vm.onGradeNameChange("تمهيدي متقدم")
        vm.saveGrade()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.grades.any { it.id == addedGrade.id && it.name == "تمهيدي متقدم" })

        // Delete empty grade succeeds
        val updatedAdded = vm.uiState.value.grades.first { it.id == addedGrade.id }
        vm.requestDeleteGrade(updatedAdded)
        assertEquals(updatedAdded.id, vm.uiState.value.gradeToDelete?.id)
        vm.confirmDeleteGrade()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.uiState.value.grades.any { it.id == addedGrade.id })

        collectJob.cancel()
    }
}
