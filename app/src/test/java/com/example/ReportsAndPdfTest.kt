package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.Attendance
import com.example.core.model.AttendanceStatus
import com.example.core.model.EducationalStages
import com.example.core.model.Exam
import com.example.core.model.Grade
import com.example.core.model.Group
import com.example.core.model.Homework
import com.example.core.model.HomeworkStatus
import com.example.core.model.NotificationEvent
import com.example.core.model.NotificationEventType
import com.example.core.model.NotificationRecipient
import com.example.core.model.PendingRecitationNotificationDto
import com.example.core.model.Recitation
import com.example.core.model.Student
import com.example.core.model.Teacher
import com.example.data.repository.ApproveRecitationResult
import com.example.data.repository.MockAttendanceRepository
import com.example.data.repository.MockExamRepository
import com.example.data.repository.MockGradeRepository
import com.example.data.repository.MockGroupRepository
import com.example.data.repository.MockHomeworkRepository
import com.example.data.repository.MockRecitationRepository
import com.example.data.repository.MockStudentRepository
import com.example.data.repository.MockTeacherRepository
import com.example.data.repository.NotificationEventCreationResult
import com.example.data.repository.NotificationEventRepository
import com.example.data.repository.NotificationWorkerResult
import com.example.ui.reports.ReportsViewModel
import com.example.ui.reports.TopStudentsMode
import com.example.util.MonthlyReportPdfData
import com.example.util.MonthlyReportPdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.OutputStream

@Implements(PdfDocument::class)
class ReportShadowPdfDocument {
    companion object {
        var lastFinishedPageCount: Int = 0
    }

    private var finishedPages = 0

    @Implementation
    fun __constructor__() {
        finishedPages = 0
    }

    @Implementation
    fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
        val bitmap = Bitmap.createBitmap(pageInfo.pageWidth, pageInfo.pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val constructor = PdfDocument.Page::class.java.getDeclaredConstructor(Canvas::class.java, PdfDocument.PageInfo::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(canvas, pageInfo)
    }

    @Implementation
    fun finishPage(page: PdfDocument.Page) {
        finishedPages++
        lastFinishedPageCount = finishedPages
    }

    @Implementation
    fun writeTo(out: OutputStream) {
        val pagesBody = (1..finishedPages.coerceAtLeast(1)).joinToString("\n") { idx ->
            "$idx 0 obj << /Type /Page /Contents $idx >> endobj"
        }
        out.write("%PDF-1.4\n$pagesBody\n%%EOF".toByteArray())
    }

    @Implementation
    fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [ReportShadowPdfDocument::class])
class ReportsAndPdfTest {

    private val testDispatcher = StandardTestDispatcher()

    private class CapturingNotificationEventRepository : NotificationEventRepository {
        val triggeredEventTypes = mutableListOf<String?>()

        override suspend fun createNotificationEvent(event: NotificationEvent): NotificationEventCreationResult =
            NotificationEventCreationResult.Created(event)

        override suspend fun getPendingRecitations(): Result<List<PendingRecitationNotificationDto>> =
            Result.success(emptyList())

        override suspend fun approveRecitation(recitationId: String): Result<ApproveRecitationResult> =
            Result.success(ApproveRecitationResult.Success("ev_1", recitationId))

        override suspend fun triggerNotificationWorker(eventType: String?): NotificationWorkerResult {
            triggeredEventTypes += eventType
            return NotificationWorkerResult.Success("OK")
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildVm(notifRepo: NotificationEventRepository = CapturingNotificationEventRepository()): ReportsViewModel {
        return ReportsViewModel(
            studentRepository = MockStudentRepository(),
            gradeRepository = MockGradeRepository(),
            attendanceRepository = MockAttendanceRepository(),
            recitationRepository = MockRecitationRepository(),
            examRepository = MockExamRepository(),
            homeworkRepository = MockHomeworkRepository(),
            groupRepository = MockGroupRepository(),
            teacherRepository = MockTeacherRepository(
                Teacher(id = "t_rep", fullName = "المدرس", educationalStage = EducationalStages.PREPARATORY)
            ),
            notificationEventRepository = notifRepo
        )
    }

    @Test
    fun testReportsBatchNotificationPassesActualEventTypeAndNullWhenMixed() = runTest {
        val repo = CapturingNotificationEventRepository()
        val vm = buildVm(repo)
        testDispatcher.scheduler.advanceUntilIdle()

        // Homogeneous ABSENCE batch -> "ABSENCE" (not hardcoded MONTHLY_REPORT)
        vm.sendBatchNotifications(
            listOf(
                NotificationRecipient(
                    studentId = "s1",
                    studentName = "طالب 1",
                    parentPhone = "01011111111",
                    sourceId = "att_1",
                    notificationType = NotificationEventType.ABSENCE
                )
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Homogeneous EXAM batch -> "EXAM"
        vm.sendBatchNotifications(
            listOf(
                NotificationRecipient(
                    studentId = "s2",
                    studentName = "طالب 2",
                    parentPhone = "01022222222",
                    sourceId = "ex_1",
                    notificationType = NotificationEventType.EXAM
                )
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        // Mixed ABSENCE + RECITATION -> null
        vm.sendBatchNotifications(
            listOf(
                NotificationRecipient(
                    studentId = "s1",
                    studentName = "طالب 1",
                    parentPhone = "01011111111",
                    sourceId = "att_2",
                    notificationType = NotificationEventType.ABSENCE
                ),
                NotificationRecipient(
                    studentId = "s2",
                    studentName = "طالب 2",
                    parentPhone = "01022222222",
                    sourceId = "rec_1",
                    notificationType = NotificationEventType.RECITATION
                )
            )
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(3, repo.triggeredEventTypes.size)
        assertEquals("ABSENCE", repo.triggeredEventTypes[0])
        assertEquals("EXAM", repo.triggeredEventTypes[1])
        assertNull(repo.triggeredEventTypes[2])
    }

    @Test
    fun testCalculateTopStudentsDoesNotLeakHistoricalRecordsWhenDateRangeEmpty() {
        val vm = buildVm()
        val grade = Grade("g1", "الأول الإعدادي", 1)
        val group = Group("grp1", "t_rep", "g1", "مجموعة 1", true, "16:00", "17:00")
        val student = Student("s1", "ST-1", "أحمد", "g1", groupId = "grp1")

        // All records are in January 2026
        val recs = (1..4).map {
            Recitation(
                recitationId = "r$it",
                studentId = "s1",
                date = "2026-01-0$it",
                title = "تسميع",
                content = "آيات",
                score = 10.0,
                maxScore = 10.0
            )
        }
        val exams = (1..4).map {
            Exam(
                examId = "e$it",
                studentId = "s1",
                date = "2026-01-0$it",
                examName = "امتحان",
                subject = "قرآن",
                score = 10.0,
                maxScore = 10.0
            )
        }
        val hws = (1..4).map {
            Homework(
                homeworkId = "h$it",
                studentId = "s1",
                date = "2026-01-0$it",
                title = "واجب",
                status = HomeworkStatus.COMPLETED
            )
        }
        val atts = (1..4).map {
            Attendance(
                attendanceId = "a$it",
                studentId = "s1",
                groupId = "grp1",
                date = "2026-01-0$it",
                status = AttendanceStatus.PRESENT
            )
        }

        // Query November 2026 -> ZERO records in range, MUST NOT leak January data!
        val emptyPeriodReport = vm.calculateTopStudents(
            allStudents = listOf(student),
            allRecitations = recs,
            allExams = exams,
            allHomework = hws,
            allAttendance = atts,
            gradeId = "g1",
            groupId = "grp1",
            mode = TopStudentsMode.CLASS,
            allGrades = listOf(grade),
            allGroups = listOf(group),
            startDateStr = "2026-11-01",
            endDateStr = "2026-11-30"
        )

        assertFalse(emptyPeriodReport.isDataSufficient)
        assertEquals(0, emptyPeriodReport.totalRecitationsCount)
        assertEquals(0, emptyPeriodReport.totalExamsCount)
        assertEquals(0, emptyPeriodReport.totalHomeworkCount)
        assertTrue(emptyPeriodReport.students.isEmpty())
    }

    @Test
    fun testEstimatePageCountAndMultiPagePdfGeneration() = runTest {
        assertEquals(1, MonthlyReportPdfExporter.estimatePageCount(listOf(100f, 200f)))
        assertEquals(2, MonthlyReportPdfExporter.estimatePageCount(listOf(350f, 350f, 400f)))
        assertEquals(3, MonthlyReportPdfExporter.estimatePageCount(listOf(350f, 350f, 350f, 350f, 350f)))
        assertEquals(4, MonthlyReportPdfExporter.estimatePageCount(listOf(400f, 400f, 400f, 400f)))

        val context: Context = ApplicationProvider.getApplicationContext()
        val student = Student("s1", "ST-01", "محمد إبراهيم علي", "grade_1")
        val manyRecitations = (1..25).map { idx ->
            Recitation(
                recitationId = "r_$idx",
                studentId = "s1",
                date = String.format("2026-09-%02d", (idx % 28) + 1),
                title = "تسميع جزء $idx - سورة البقرة من الآية 1 إلى 20",
                content = "الآيات كاملة",
                score = 9.5,
                maxScore = 10.0
            )
        }
        val manyExams = (1..15).map { idx ->
            Exam(
                examId = "e_$idx",
                studentId = "s1",
                date = String.format("2026-09-%02d", (idx % 28) + 1),
                examName = "اختبار دوري رقم $idx",
                subject = "قرآن وتجويد",
                score = 19.0,
                maxScore = 20.0
            )
        }
        val longNote = List(20) { "ملاحظة تفصيلية حول مستوى الطالب في الحفظ والمراجعة والتجويد رقم $it." }.joinToString("\n")

        val data = MonthlyReportPdfData(
            student = student,
            month = 9,
            year = 2026,
            monthNameAr = "سبتمبر",
            attendanceRate = 92.5f,
            attendancePresent = 18,
            attendanceAbsent = 2,
            attendanceTotal = 20,
            absenceList = listOf(
                Attendance("a1", "s1", date = "2026-09-04", status = AttendanceStatus.ABSENT, note = "ظرف عائلي"),
                Attendance("a2", "s1", date = "2026-09-12", status = AttendanceStatus.ABSENT, note = null)
            ),
            recitationAverage = 95.0f,
            recitationCount = manyRecitations.size,
            recitationList = manyRecitations,
            examAverage = 95.0f,
            examCount = manyExams.size,
            examList = manyExams,
            teacherNote = longNote
        )

        val pdfFile = MonthlyReportPdfExporter.generateMonthlyReportPdf(context, data)
        assertNotNull(pdfFile)
        assertTrue(pdfFile!!.exists())
        assertTrue(pdfFile.length() > 0L)

        // Verify valid multi-page PDF structure in generated bytes (> 1 page object)
        assertTrue("Expected multi-page finishPage calls > 1, got ${ReportShadowPdfDocument.lastFinishedPageCount}", ReportShadowPdfDocument.lastFinishedPageCount > 1)
        val rawPdf = pdfFile.readText(Charsets.ISO_8859_1)
        assertTrue(rawPdf.startsWith("%PDF-"))
        val pageMatches = Regex("/Type\\s*/Page\\b").findAll(rawPdf).count()
        assertTrue("Expected multi-page PDF (>1 pages), got $pageMatches", pageMatches > 1)
    }
}
