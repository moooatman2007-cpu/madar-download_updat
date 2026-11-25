package com.example

import com.example.core.model.Group
import com.example.core.model.Student
import com.example.core.model.Teacher
import com.example.data.SupabaseClientProvider
import com.example.data.repository.MockAttendanceRepository
import com.example.data.repository.MockGradeRepository
import com.example.data.repository.MockGroupRepository
import com.example.data.repository.MockPaymentRepository
import com.example.data.repository.MockStudentRepository
import com.example.data.repository.MockTeacherRepository
import com.example.ui.attendance.FastAttendanceViewModel
import com.example.ui.attendance.ScanResultType
import com.example.util.BarcodeGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrAndBarcodeFixTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        SupabaseClientProvider.mockTeacherId = null
        Dispatchers.resetMain()
    }

    @Test
    fun testBlankStudentCodeReturnsNullQrAndCode128() {
        assertNull(BarcodeGenerator.generateQRCodeBitmap(""))
        assertNull(BarcodeGenerator.generateQRCodeBitmap("   "))
        assertNull(BarcodeGenerator.generateCode128Bitmap(""))
        assertNull(BarcodeGenerator.generateCode128Bitmap("   "))

        assertNotNull(BarcodeGenerator.generateQRCodeBitmap("ST-001", size = 128))
        assertNotNull(BarcodeGenerator.generateCode128Bitmap("ST-001", width = 200, height = 80))
    }

    @Test
    fun testGenerateStudentQrCardBitmapWithBlankCodeDoesNotFallbackToSt000() {
        val blankCodeStudent = Student(
            studentId = "s_blank",
            studentCode = "   ",
            fullName = "طالب بدون كود",
            gradeId = "grade_1"
        )
        // Must succeed rendering card without throwing or encoding "ST-000"
        val card = BarcodeGenerator.generateStudentQrCardBitmap(
            student = blankCodeStudent,
            gradeName = "الأول الإعدادي",
            width = 400,
            height = 500
        )
        assertEquals(400, card.width)
        assertEquals(500, card.height)
    }

    @Test
    fun testFastAttendanceExcludesBlankStudentCodeFromScannerLookup() = runTest {
        val teacherId = "teacher_qr_1"
        SupabaseClientProvider.mockTeacherId = teacherId

        val group = Group(id = "grp_1", teacherId = teacherId, gradeId = "grade_1", name = "مجموعة أ", startTime = "10:00", endTime = "11:00")
        val blankStudent = Student(studentId = "s_blank", studentCode = "", fullName = "بدون كود", gradeId = "grade_1", groupId = "grp_1")
        val validStudent = Student(studentId = "s_valid", studentCode = "ST-900", fullName = "بكود صحيح", gradeId = "grade_1", groupId = "grp_1")

        val vm = FastAttendanceViewModel(
            studentRepository = MockStudentRepository(customStudents = listOf(blankStudent, validStudent)),
            gradeRepository = MockGradeRepository(),
            attendanceRepository = MockAttendanceRepository(),
            paymentRepository = MockPaymentRepository(),
            groupRepository = MockGroupRepository(initialGroups = listOf(group)),
            teacherRepository = MockTeacherRepository(Teacher(id = teacherId, fullName = "المدرس"))
        )
        vm.setInitialGroupId("grp_1", "مجموعة أ")
        testDispatcher.scheduler.advanceUntilIdle()

        // Blank scan must be ignored
        vm.processScannedBarcode("")
        assertNull(vm.uiState.value.scanFeedback)

        // Unknown / blank-code student must not match
        vm.processScannedBarcode("ST-000")
        assertEquals(ScanResultType.NOT_FOUND, vm.uiState.value.scanFeedback?.type)

        // Valid code works
        vm.processScannedBarcode("st-900")
        assertEquals(ScanResultType.SUCCESS_PRESENT, vm.uiState.value.scanFeedback?.type)
        assertEquals("s_valid", vm.uiState.value.scanFeedback?.student?.studentId)
    }
}
