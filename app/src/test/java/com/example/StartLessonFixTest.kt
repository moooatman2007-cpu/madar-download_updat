package com.example

import com.example.core.model.*
import com.example.data.SupabaseClientProvider
import com.example.data.repository.*
import com.example.ui.attendance.ScanResultType
import com.example.ui.start_lesson.StartLessonViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartLessonFixTest {

    private val testDispatcher = StandardTestDispatcher()

    private val teacherId = "teacher_sl_1"
    private val group1Id = "group_sl_1"
    private val group2Id = "group_sl_2"

    private val grade1 = Grade(id = "grade_1", name = "الأول الإعدادي", displayOrder = 1)
    private val group1 = Group(
        id = group1Id,
        teacherId = teacherId,
        gradeId = "grade_1",
        name = "مجموعة الفجر",
        active = true,
        startTime = "06:00",
        endTime = "07:00"
    )
    private val group2 = Group(
        id = group2Id,
        teacherId = teacherId,
        gradeId = "grade_1",
        name = "مجموعة العصر",
        active = true,
        startTime = "16:00",
        endTime = "17:00"
    )

    private val studentInGroup1A = Student(
        studentId = "s_g1_a",
        studentCode = "ST-1001",
        fullName = "أحمد الفجر",
        gradeId = "grade_1",
        groupId = group1Id,
        parentPhone = "01011111111",
        hasWhatsApp = true,
        teacherId = teacherId
    )
    private val studentInGroup1B = Student(
        studentId = "s_g1_b",
        studentCode = "ST-1002",
        fullName = "عمر الفجر",
        gradeId = "grade_1",
        groupId = group1Id,
        parentPhone = "01022222222",
        hasWhatsApp = true,
        teacherId = teacherId
    )
    private val studentInGroup2 = Student(
        studentId = "s_g2_a",
        studentCode = "ST-2001",
        fullName = "سعيد العصر",
        gradeId = "grade_1",
        groupId = group2Id,
        parentPhone = "01033333333",
        hasWhatsApp = true,
        teacherId = teacherId
    )

    private class SpyAttendanceRepository : AttendanceRepository {
        val batchCalls = mutableListOf<Triple<String, List<BatchAttendanceItemDto>, String?>>()
        private val stored = MutableStateFlow<List<Attendance>>(emptyList())

        override fun getAttendanceForStudent(studentId: String): Flow<List<Attendance>> =
            stored.map { list -> list.filter { it.studentId == studentId } }

        override fun getAttendanceForStudentByMonth(studentId: String, year: Int, month: Int): Flow<List<Attendance>> =
            stored.map { emptyList() }

        override suspend fun getAttendanceSummaryForStudent(studentId: String, year: Int, month: Int): AttendanceSummary =
            AttendanceSummary()

        override suspend fun getTodayAttendanceCount(): Pair<Int, Int> = 0 to 0
        override fun getAllAttendanceForTeacher(): Flow<List<Attendance>> = stored

        override suspend fun recordOrUpdateAttendance(
            studentId: String,
            date: String,
            status: AttendanceStatus,
            note: String?,
            groupId: String?
        ): Result<SyncResult<Attendance>> {
            val att = Attendance(
                attendanceId = UUID.randomUUID().toString(),
                studentId = studentId,
                groupId = groupId,
                date = date,
                status = status,
                note = note
            )
            stored.value = stored.value + att
            return Result.success(SyncResult(att, BatchSyncStatus.SAVED_TO_CLOUD))
        }

        override suspend fun recordBatchAttendance(
            date: String,
            records: List<BatchAttendanceItemDto>,
            groupId: String?
        ): Result<BatchAttendanceResult> {
            batchCalls += Triple(date, records, groupId)
            val added = records.map { item ->
                Attendance(
                    attendanceId = UUID.randomUUID().toString(),
                    studentId = item.studentId,
                    groupId = groupId,
                    date = date,
                    status = AttendanceStatus.fromString(item.status),
                    note = item.note
                )
            }
            stored.value = stored.value + added
            return Result.success(
                BatchAttendanceResult(
                    total = records.size,
                    presentCount = records.count { it.status == "PRESENT" },
                    absentCount = records.count { it.status == "ABSENT" },
                    date = date,
                    syncStatus = BatchSyncStatus.SAVED_TO_CLOUD
                )
            )
        }

        override suspend fun deleteAttendance(attendanceId: String): Result<Unit> = Result.success(Unit)
        override suspend fun getAttendanceByDate(studentId: String, date: String): Attendance? = null
        override suspend fun getAttendanceForDateAndGroup(date: String, groupId: String?): List<Attendance> =
            stored.value.filter { it.date == date && it.groupId == groupId }
    }

    private class SpyRecitationRepository : RecitationRepository {
        val addedRecitations = mutableListOf<Recitation>()
        private val flow = MutableStateFlow<List<Recitation>>(emptyList())
        override fun getRecitationsForStudent(studentId: String): Flow<List<Recitation>> = flow
        override fun getRecitationsForStudentByMonth(studentId: String, year: Int, month: Int): Flow<List<Recitation>> = flow
        override suspend fun getRecitationSummaryForStudent(studentId: String, year: Int, month: Int) = RecitationSummary()
        override suspend fun getRecitationsCountThisMonth(year: Int, month: Int): Int = 0
        override fun getAllRecitationsForTeacher(): Flow<List<Recitation>> = flow
        override suspend fun addRecitation(
            studentId: String,
            date: String,
            title: String,
            content: String,
            score: Double,
            maxScore: Double,
            note: String?
        ): Result<SyncResult<Recitation>> {
            val r = Recitation(
                recitationId = UUID.randomUUID().toString(),
                studentId = studentId,
                date = date,
                title = title,
                content = content,
                score = score,
                maxScore = maxScore,
                note = note
            )
            addedRecitations += r
            flow.value = flow.value + r
            return Result.success(SyncResult(r, BatchSyncStatus.SAVED_TO_CLOUD))
        }
        override suspend fun updateRecitation(recitation: Recitation): Result<Recitation> = Result.success(recitation)
        override suspend fun deleteRecitation(recitationId: String): Result<Unit> = Result.success(Unit)
        override suspend fun getRecitationById(recitationId: String): Recitation? = null
    }

    private class SpyHomeworkRepository : HomeworkRepository {
        val addedHomeworks = mutableListOf<Homework>()
        private val flow = MutableStateFlow<List<Homework>>(emptyList())
        override fun getHomeworkForStudent(studentId: String): Flow<List<Homework>> = flow
        override fun getHomeworkForStudentByMonth(studentId: String, year: Int, month: Int): Flow<List<Homework>> = flow
        override fun getHomeworkForTeacher(): Flow<List<Homework>> = flow
        override suspend fun addHomework(
            studentId: String,
            date: String,
            title: String,
            status: HomeworkStatus,
            note: String?
        ): Result<SyncResult<Homework>> {
            val h = Homework(
                homeworkId = UUID.randomUUID().toString(),
                studentId = studentId,
                date = date,
                title = title,
                status = status,
                note = note
            )
            addedHomeworks += h
            flow.value = flow.value + h
            return Result.success(SyncResult(h, BatchSyncStatus.SAVED_TO_CLOUD))
        }
        override suspend fun updateHomework(homework: Homework): Result<Homework> = Result.success(homework)
        override suspend fun deleteHomework(homeworkId: String): Result<Unit> = Result.success(Unit)
        override suspend fun getHomeworkById(homeworkId: String): Homework? = null
    }

    private class SpyExamRepository : ExamRepository {
        val addedExams = mutableListOf<Exam>()
        private val flow = MutableStateFlow<List<Exam>>(emptyList())
        override fun getExamsForStudent(studentId: String): Flow<List<Exam>> = flow
        override fun getExamsForStudentByMonth(studentId: String, year: Int, month: Int): Flow<List<Exam>> = flow
        override suspend fun getExamSummaryForStudent(studentId: String, year: Int, month: Int) = ExamSummary()
        override suspend fun getExamsCountThisMonth(year: Int, month: Int): Int = 0
        override fun getAllExamsForTeacher(): Flow<List<Exam>> = flow
        override suspend fun addExam(
            studentId: String,
            date: String,
            examName: String,
            subject: String?,
            score: Double,
            maxScore: Double,
            note: String?
        ): Result<SyncResult<Exam>> {
            val e = Exam(
                examId = UUID.randomUUID().toString(),
                studentId = studentId,
                date = date,
                examName = examName,
                subject = subject,
                score = score,
                maxScore = maxScore,
                note = note
            )
            addedExams += e
            flow.value = flow.value + e
            return Result.success(SyncResult(e, BatchSyncStatus.SAVED_TO_CLOUD))
        }
        override suspend fun updateExam(exam: Exam): Result<Exam> = Result.success(exam)
        override suspend fun deleteExam(examId: String): Result<Unit> = Result.success(Unit)
        override suspend fun getExamById(examId: String): Exam? = null
    }

    private class SpyNotificationEventRepository : NotificationEventRepository {
        val createdEvents = mutableListOf<NotificationEvent>()
        val approvedRecitations = mutableListOf<String>()
        val workerEventTypeArgs = mutableListOf<String?>()

        override suspend fun createNotificationEvent(event: NotificationEvent): NotificationEventCreationResult {
            createdEvents += event
            return NotificationEventCreationResult.Created(event)
        }

        override suspend fun getPendingRecitations(): Result<List<PendingRecitationNotificationDto>> =
            Result.success(emptyList())

        override suspend fun approveRecitation(recitationId: String): Result<ApproveRecitationResult> {
            approvedRecitations += recitationId
            return Result.success(ApproveRecitationResult.Success(UUID.randomUUID().toString(), recitationId))
        }

        override suspend fun triggerNotificationWorker(eventType: String?): NotificationWorkerResult {
            workerEventTypeArgs += eventType
            return NotificationWorkerResult.Success("OK")
        }
    }

    private fun buildVm(
        attendanceRepo: AttendanceRepository = SpyAttendanceRepository(),
        recitationRepo: RecitationRepository = SpyRecitationRepository(),
        homeworkRepo: HomeworkRepository = SpyHomeworkRepository(),
        examRepo: ExamRepository = SpyExamRepository(),
        notificationRepo: NotificationEventRepository = SpyNotificationEventRepository()
    ): StartLessonViewModel {
        val gradeRepo = MockGradeRepository().apply {
            runCatching { }
        }
        val studentRepo = MockStudentRepository(
            customStudents = listOf(studentInGroup1A, studentInGroup1B, studentInGroup2)
        )
        val groupRepo = MockGroupRepository(initialGroups = listOf(group1, group2))
        val teacherRepo = MockTeacherRepository(
            Teacher(id = teacherId, fullName = "مدرس الفجر", email = "t@midar.app")
        )
        return StartLessonViewModel(
            gradeRepository = gradeRepo,
            studentRepository = studentRepo,
            attendanceRepository = attendanceRepo,
            recitationRepository = recitationRepo,
            homeworkRepository = homeworkRepo,
            examRepository = examRepo,
            paymentRepository = MockPaymentRepository(),
            notificationEventRepository = notificationRepo,
            groupRepository = groupRepo,
            teacherRepository = teacherRepo,
            ioDispatcher = testDispatcher
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        SupabaseClientProvider.mockTeacherId = teacherId
    }

    @After
    fun tearDown() {
        SupabaseClientProvider.mockTeacherId = null
        Dispatchers.resetMain()
    }

    @Test
    fun testSelectGroupLoadsRealGroupsAndScopedStudents() = runTest {
        val vm = buildVm()
        testDispatcher.scheduler.advanceUntilIdle()

        val initialState = vm.uiState.value
        assertEquals(2, initialState.groups.size)
        assertEquals(2, initialState.groupStudentCounts[group1Id])
        assertEquals(1, initialState.groupStudentCounts[group2Id])

        vm.selectGroup(group1Id)
        testDispatcher.scheduler.advanceUntilIdle()

        val selectedState = vm.uiState.value
        assertEquals(group1Id, selectedState.selectedGroupId)
        assertEquals("مجموعة الفجر", selectedState.selectedGroupName)
        assertEquals(2, selectedState.studentsInGroup.size)
        assertTrue(selectedState.studentsInGroup.all { it.groupId == group1Id })

        // Defaults must NOT mark recitation/homework/exam as recorded
        assertTrue(selectedState.recitationsMap.values.none { it.isRecorded })
        assertTrue(selectedState.homeworkMap.values.none { it.isRecorded })
        assertTrue(selectedState.examsMap.values.none { it.isRecorded })
    }

    @Test
    fun testFinishLessonSendsRealGroupIdAndSkipsUnrecordedEntries() = runTest {
        val attRepo = SpyAttendanceRepository()
        val recRepo = SpyRecitationRepository()
        val hwRepo = SpyHomeworkRepository()
        val exRepo = SpyExamRepository()
        val notifRepo = SpyNotificationEventRepository()

        val vm = buildVm(attRepo, recRepo, hwRepo, exRepo, notifRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectGroup(group1Id)
        testDispatcher.scheduler.advanceUntilIdle()

        // Mark only student A present; student B stays absent; enter NOTHING in recitation/homework/exam
        vm.toggleStudentPresent(studentInGroup1A.studentId)
        vm.finishLesson()
        testDispatcher.scheduler.advanceUntilIdle()

        // 1. Attendance saved with REAL groupId
        assertEquals(1, attRepo.batchCalls.size)
        assertEquals(group1Id, attRepo.batchCalls.first().third)

        // 2. Zero fake recitations, zero fake homeworks, zero fake exams created!
        assertTrue(recRepo.addedRecitations.isEmpty())
        assertTrue(hwRepo.addedHomeworks.isEmpty())
        assertTrue(exRepo.addedExams.isEmpty())
        assertTrue(vm.uiState.value.isFinished)

        // 3. Single homogeneous event type (ABSENCE) -> worker receives "ABSENCE"
        vm.submitNotificationEvents()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("ABSENCE"), notifRepo.workerEventTypeArgs)
    }

    @Test
    fun testFinishLessonPersistsOnlyExplicitlyRecordedEntriesAndTriggersNullForMixedWorker() = runTest {
        val attRepo = SpyAttendanceRepository()
        val recRepo = SpyRecitationRepository()
        val hwRepo = SpyHomeworkRepository()
        val exRepo = SpyExamRepository()
        val notifRepo = SpyNotificationEventRepository()

        val vm = buildVm(attRepo, recRepo, hwRepo, exRepo, notifRepo)
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectGroup(group1Id)
        testDispatcher.scheduler.advanceUntilIdle()

        // Only student A present (B absent -> creates ABSENCE notification)
        vm.toggleStudentPresent(studentInGroup1A.studentId)

        // Enter recitation ONLY for student A
        vm.updateRecitationInput(studentInGroup1A.studentId) {
            it.copy(title = "سورة الملك", scoreStr = "9", maxScoreStr = "10")
        }
        // Enter homework ONLY for student A
        vm.updateHomeworkInput(studentInGroup1A.studentId) {
            it.copy(title = "كتابة الوجه", status = HomeworkStatus.COMPLETED, isRecorded = true)
        }
        // Exam input toggled then cleared for student B -> must NOT be saved
        vm.updateExamInput(studentInGroup1B.studentId) { it.copy(examName = "مؤقت", scoreStr = "8") }
        vm.clearExamInput(studentInGroup1B.studentId)

        vm.finishLesson()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, recRepo.addedRecitations.size)
        assertEquals(studentInGroup1A.studentId, recRepo.addedRecitations.first().studentId)
        assertEquals(1, hwRepo.addedHomeworks.size)
        assertEquals(studentInGroup1A.studentId, hwRepo.addedHomeworks.first().studentId)
        assertTrue(exRepo.addedExams.isEmpty())

        // Mixed event types (ABSENCE + RECITATION + HOMEWORK) -> worker trigger receives null (process all types)
        vm.submitNotificationEvents()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, notifRepo.workerEventTypeArgs.size)
        assertNull(notifRepo.workerEventTypeArgs.first())
    }

    @Test
    fun testProcessScannedBarcodeRejectsOtherGroupStudents() = runTest {
        val vm = buildVm()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.selectGroup(group1Id)
        testDispatcher.scheduler.advanceUntilIdle()

        // Scan student from group 2 while group 1 is selected
        vm.processScannedBarcode("ST-2001")
        assertEquals(ScanResultType.WRONG_GROUP, vm.uiState.value.scanFeedback?.type)
        assertFalse(vm.uiState.value.presentStudentIds.contains(studentInGroup2.studentId))

        // Scan valid group 1 student
        vm.processScannedBarcode("ST-1001")
        assertEquals(ScanResultType.SUCCESS_PRESENT, vm.uiState.value.scanFeedback?.type)
        assertTrue(vm.uiState.value.presentStudentIds.contains(studentInGroup1A.studentId))
    }
}
