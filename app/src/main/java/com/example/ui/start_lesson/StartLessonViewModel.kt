package com.example.ui.start_lesson

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.AttendanceStatus
import com.example.core.model.BatchAttendanceItemDto
import com.example.core.model.BatchSyncStatus
import com.example.core.model.Grade
import com.example.core.model.Group
import com.example.core.model.HomeworkStatus
import com.example.core.model.NotificationEvent
import com.example.core.model.NotificationEventType
import com.example.core.model.Student
import com.example.data.SupabaseClientProvider
import com.example.data.repository.AttendanceRepository
import com.example.data.repository.ExamRepository
import com.example.data.repository.HomeworkRepository
import com.example.data.repository.GradeRepository
import com.example.data.repository.GroupRepository
import com.example.data.repository.NotificationEventCreationResult
import com.example.data.repository.NotificationEventRepository
import com.example.data.repository.NotificationWorkerResult
import com.example.data.repository.PaymentRepository
import com.example.data.repository.RecitationRepository
import com.example.data.repository.RepositoryProvider
import com.example.data.repository.StudentRepository
import com.example.data.repository.TeacherRepository
import com.example.ui.attendance.ScanFeedback
import com.example.ui.attendance.ScanResultType
import com.example.util.isOfflineOrNetworkError
import com.example.util.toArabicErrorMessage
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class StartLessonStep {
    SELECT_GROUP,
    ATTENDANCE,
    ATTENDANCE_SUMMARY,
    RECITATION_CHOICE,
    RECITATION,
    HOMEWORK_CHOICE,
    HOMEWORK,
    EXAM_CHOICE,
    EXAM,
    REVIEW,
    NOTIFICATION_REVIEW
}

data class StudentRecitationInput(
    val title: String = "",
    val content: String = "",
    val scoreStr: String = "",
    val maxScoreStr: String = "10",
    val note: String = "",
    val isRecorded: Boolean = false
)

data class StudentHomeworkInput(
    val title: String = "",
    val status: HomeworkStatus = HomeworkStatus.COMPLETED,
    val note: String = "",
    val isRecorded: Boolean = false
)

data class StudentExamInput(
    val examName: String = "",
    val scoreStr: String = "",
    val maxScoreStr: String = "10",
    val note: String = "",
    val isRecorded: Boolean = false
)

data class LessonSavedItem(
    val studentId: String,
    val sourceId: String,
    val eventType: NotificationEventType,
    val isConfirmedOnline: Boolean = true
)

data class LessonSavedContext(
    val absenceItems: List<LessonSavedItem> = emptyList(),
    val recitationItems: List<LessonSavedItem> = emptyList(),
    val homeworkItems: List<LessonSavedItem> = emptyList(),
    val examItems: List<LessonSavedItem> = emptyList()
) {
    val totalItemsCount: Int
        get() = absenceItems.size + recitationItems.size + homeworkItems.size + examItems.size
}

data class NotificationReviewItem(
    val studentId: String,
    val sourceId: String,
    val eventType: NotificationEventType,
    val studentName: String,
    val parentPhone: String,
    val whatsappEnabled: Boolean,
    val isConfirmedOnline: Boolean,
    val eligibleForNotification: Boolean,
    val ineligibilityReason: String? = null
)

data class NotificationReviewState(
    val absenceItems: List<NotificationReviewItem> = emptyList(),
    val recitationItems: List<NotificationReviewItem> = emptyList(),
    val homeworkItems: List<NotificationReviewItem> = emptyList(),
    val examItems: List<NotificationReviewItem> = emptyList(),
    val totalStudents: Int = 0,
    val presentStudents: Int = 0,
    val absentStudents: Int = 0,
    val recitationCount: Int = 0,
    val homeworkCount: Int = 0,
    val examCount: Int = 0
) {
    val totalReviewItems: Int
        get() = absenceItems.size + recitationItems.size + homeworkItems.size + examItems.size

    val totalEligibleItems: Int
        get() = (absenceItems + recitationItems + homeworkItems + examItems).count { it.eligibleForNotification }
}

data class FailedNotificationItem(
    val studentId: String,
    val sourceId: String,
    val eventType: NotificationEventType,
    val message: String
)

data class NotificationEventCreationSummary(
    val createdCount: Int = 0,
    val alreadyExistsCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedItems: List<FailedNotificationItem> = emptyList()
) {
    val totalProcessed: Int
        get() = createdCount + alreadyExistsCount + failedCount + skippedCount
}

data class FailedLessonItem(
    val studentId: String,
    val studentName: String,
    val message: String
)

data class LessonSaveReport(
    val attendanceSaved: Boolean = false,
    val attendanceError: String? = null,
    val savedRecitationCount: Int = 0,
    val failedRecitations: List<FailedLessonItem> = emptyList(),
    val savedHomeworkCount: Int = 0,
    val failedHomework: List<FailedLessonItem> = emptyList(),
    val savedExamCount: Int = 0,
    val failedExams: List<FailedLessonItem> = emptyList()
) {
    val hasFailures: Boolean
        get() = !attendanceSaved ||
            failedRecitations.isNotEmpty() ||
            failedHomework.isNotEmpty() ||
            failedExams.isNotEmpty()
}

data class StartLessonUiState(
    val isLoading: Boolean = false,
    val loadError: String? = null,
    val isOfflineError: Boolean = false,
    val currentStep: StartLessonStep = StartLessonStep.SELECT_GROUP,
    val grades: List<Grade> = emptyList(),
    val groups: List<Group> = emptyList(),
    val groupStudentCounts: Map<String, Int> = emptyMap(),
    val selectedGradeId: String? = null,
    val selectedGradeName: String = "",
    val selectedGroupId: String? = null,
    val selectedGroupName: String = "",
    val studentsInGroup: List<Student> = emptyList(),
    val presentStudentIds: Set<String> = emptySet(),
    val absentStudentIds: Set<String> = emptySet(),
    val lateStudentIds: Set<String> = emptySet(),
    val excusedStudentIds: Set<String> = emptySet(),
    val attendanceStatusMap: Map<String, AttendanceStatus> = emptyMap(),
    val isLoadingAttendance: Boolean = false,
    val statusChangeMessage: String? = null,
    val currentDate: String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
    val currentYear: Int = LocalDate.now().year,
    val currentMonth: Int = LocalDate.now().monthValue,
    val paymentsMap: Map<String, com.example.core.model.LessonPayment> = emptyMap(),
    val togglingPaymentStudentIds: Set<String> = emptySet(),
    
    // Step inputs per studentId
    val recitationsMap: Map<String, StudentRecitationInput> = emptyMap(),
    val homeworkMap: Map<String, StudentHomeworkInput> = emptyMap(),
    val examsMap: Map<String, StudentExamInput> = emptyMap(),
    
    // Skip flags
    val skipRecitation: Boolean = false,
    val skipHomework: Boolean = false,
    val skipExam: Boolean = false,

    // Scanner
    val isScannerActive: Boolean = false,
    val scanFeedback: ScanFeedback? = null,
    val recentScannedStudents: List<Student> = emptyList(),

    // Saving / dialogs / idempotent checkpoints
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val showUnrecordedWarning: Boolean = false,
    val isFinished: Boolean = false,
    val attendanceSaved: Boolean = false,
    val savedAbsenceItems: List<LessonSavedItem> = emptyList(),
    val savedRecitationItemsByStudent: Map<String, LessonSavedItem> = emptyMap(),
    val savedHomeworkItemsByStudent: Map<String, LessonSavedItem> = emptyMap(),
    val savedExamItemsByStudent: Map<String, LessonSavedItem> = emptyMap(),
    val partialSaveReport: LessonSaveReport? = null,
    val savedContext: LessonSavedContext? = null,
    val reviewState: NotificationReviewState? = null,
    val isSubmittingNotifications: Boolean = false,
    val notificationCreationSummary: NotificationEventCreationSummary? = null
)

class StartLessonViewModel(
    private val gradeRepository: GradeRepository = RepositoryProvider.gradeRepository,
    private val studentRepository: StudentRepository = RepositoryProvider.studentRepository,
    private val attendanceRepository: AttendanceRepository = RepositoryProvider.attendanceRepository,
    private val recitationRepository: RecitationRepository = RepositoryProvider.recitationRepository,
    private val homeworkRepository: HomeworkRepository = RepositoryProvider.homeworkRepository,
    private val examRepository: ExamRepository = RepositoryProvider.examRepository,
    private val paymentRepository: PaymentRepository = RepositoryProvider.paymentRepository,
    private val notificationEventRepository: NotificationEventRepository = RepositoryProvider.notificationEventRepository,
    private val groupRepository: GroupRepository = RepositoryProvider.groupRepository,
    private val teacherRepository: TeacherRepository = RepositoryProvider.teacherRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(StartLessonUiState())
    val uiState: StateFlow<StartLessonUiState> = _uiState.asStateFlow()

    private var allStudentsList: List<Student> = emptyList()
    private var studentCodeMap: Map<String, Student> = emptyMap()

    init {
        observeDataFlows()
        loadMonthlyPayments()
        refreshOnlineData()
    }

    private suspend fun resolveTeacherId(): String? {
        return SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
            ?: try { teacherRepository.fetchCurrentTeacher()?.id } catch (_: Exception) { null }
    }

    fun retry() {
        refreshOnlineData()
    }

    fun refreshOnlineData() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update { it.copy(isLoading = true, loadError = null, isOfflineError = false) }
            try {
                val teacherId = resolveTeacherId()
                coroutineScope {
                    val gradesDef = async(ioDispatcher) { gradeRepository.refreshGradesResult() }
                    val studentsDef = async(ioDispatcher) { studentRepository.refreshStudents() }
                    val groupsDef = async(ioDispatcher) {
                        if (!teacherId.isNullOrBlank()) {
                            groupRepository.refreshGroups(teacherId)
                        } else {
                            Result.success(Unit)
                        }
                    }
                    val paymentsDef = async(ioDispatcher) {
                        paymentRepository.fetchMonthlyPaymentsResult(state.currentYear, state.currentMonth)
                    }

                    val gradesRes = gradesDef.await()
                    val studentsRes = studentsDef.await()
                    val groupsRes = groupsDef.await()
                    val paymentsRes = paymentsDef.await()

                    val firstError = gradesRes.exceptionOrNull()
                        ?: studentsRes.exceptionOrNull()
                        ?: groupsRes.exceptionOrNull()
                        ?: paymentsRes.exceptionOrNull()

                    if (firstError != null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                loadError = firstError.toArabicErrorMessage(),
                                isOfflineError = firstError.isOfflineOrNetworkError()
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                loadError = null,
                                isOfflineError = false
                            )
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadError = e.toArabicErrorMessage(),
                        isOfflineError = e.isOfflineOrNetworkError()
                    )
                }
            }
        }
    }

    private fun observeDataFlows() {
        viewModelScope.launch {
            val teacherId = resolveTeacherId()
            val groupsFlow = if (!teacherId.isNullOrBlank()) {
                groupRepository.observeGroups(teacherId)
                    .map { list -> list.filter { it.active } }
                    .onStart { emit(emptyList()) }
                    .catch { e ->
                        _uiState.update {
                            it.copy(
                                loadError = e.toArabicErrorMessage(),
                                isOfflineError = e.isOfflineOrNetworkError()
                            )
                        }
                        emit(emptyList())
                    }
            } else {
                flowOf(emptyList())
            }

            combine(
                gradeRepository.getGrades(),
                studentRepository.getStudents(),
                groupsFlow
            ) { grades, students, groups ->
                val active = students.filter { it.deletedAt == null }
                Triple(grades, active, groups)
            }
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            loadError = e.toArabicErrorMessage(),
                            isOfflineError = e.isOfflineOrNetworkError()
                        )
                    }
                }
                .collect { (grades, active, groups) ->
                    allStudentsList = active
                    studentCodeMap = active
                        .filter { it.studentCode.isNotBlank() }
                        .associateBy { it.studentCode.trim().lowercase() }
                    val countsByGroup = active
                        .filter { !it.groupId.isNullOrBlank() }
                        .groupingBy { it.groupId!! }
                        .eachCount()

                    _uiState.update { state ->
                        val selectedGroup = state.selectedGroupId?.let { id -> groups.firstOrNull { it.id == id } }
                        val resolvedGradeId = selectedGroup?.gradeId ?: state.selectedGradeId
                        val gradeName = resolvedGradeId?.let { id -> grades.firstOrNull { it.id == id }?.name } ?: state.selectedGradeName
                        val groupName = selectedGroup?.name ?: state.selectedGroupName
                        val updatedStudents = when {
                            state.selectedGroupId != null -> active.filter { it.groupId == state.selectedGroupId }
                            state.selectedGradeId != null -> active.filter { it.gradeId == state.selectedGradeId }
                            else -> state.studentsInGroup
                        }
                        state.copy(
                            grades = grades,
                            groups = groups,
                            groupStudentCounts = countsByGroup,
                            selectedGradeId = resolvedGradeId,
                            selectedGradeName = gradeName,
                            selectedGroupName = groupName,
                            studentsInGroup = updatedStudents
                        )
                    }
                }
        }
    }

    private fun loadMonthlyPayments() {
        viewModelScope.launch {
            val state = _uiState.value
            paymentRepository.getMonthlyPayments(state.currentYear, state.currentMonth)
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            loadError = it.loadError ?: e.toArabicErrorMessage(),
                            isOfflineError = it.isOfflineError || e.isOfflineOrNetworkError()
                        )
                    }
                }
                .collect { payments ->
                    _uiState.update { it.copy(paymentsMap = payments.associateBy { p -> p.studentId }) }
                }
        }
    }

    fun selectGroup(groupId: String) {
        val state = _uiState.value
        val group = state.groups.firstOrNull { it.id == groupId }
        val gradeId = group?.gradeId
        val gradeName = gradeId?.let { gid -> state.grades.firstOrNull { it.id == gid }?.name } ?: ""
        val students = allStudentsList.filter { it.groupId == groupId }
        val isSameGroup = state.selectedGroupId == groupId

        val recMap = if (isSameGroup && state.recitationsMap.isNotEmpty()) {
            students.associate { it.studentId to (state.recitationsMap[it.studentId] ?: StudentRecitationInput()) }
        } else {
            students.associate { it.studentId to StudentRecitationInput() }
        }
        val hwMap = if (isSameGroup && state.homeworkMap.isNotEmpty()) {
            students.associate { it.studentId to (state.homeworkMap[it.studentId] ?: StudentHomeworkInput()) }
        } else {
            students.associate { it.studentId to StudentHomeworkInput() }
        }
        val exMap = if (isSameGroup && state.examsMap.isNotEmpty()) {
            students.associate { it.studentId to (state.examsMap[it.studentId] ?: StudentExamInput()) }
        } else {
            students.associate { it.studentId to StudentExamInput() }
        }

        _uiState.update {
            it.copy(
                selectedGroupId = groupId,
                selectedGroupName = group?.name ?: "المجموعة",
                selectedGradeId = gradeId,
                selectedGradeName = gradeName,
                studentsInGroup = students,
                recitationsMap = recMap,
                homeworkMap = hwMap,
                examsMap = exMap,
                presentStudentIds = if (isSameGroup) it.presentStudentIds else emptySet(),
                absentStudentIds = if (isSameGroup) it.absentStudentIds else emptySet(),
                lateStudentIds = if (isSameGroup) it.lateStudentIds else emptySet(),
                excusedStudentIds = if (isSameGroup) it.excusedStudentIds else emptySet(),
                attendanceStatusMap = if (isSameGroup) it.attendanceStatusMap else emptyMap(),
                recentScannedStudents = if (isSameGroup) it.recentScannedStudents else emptyList(),
                attendanceSaved = if (isSameGroup) it.attendanceSaved else false,
                savedAbsenceItems = if (isSameGroup) it.savedAbsenceItems else emptyList(),
                savedRecitationItemsByStudent = if (isSameGroup) it.savedRecitationItemsByStudent else emptyMap(),
                savedHomeworkItemsByStudent = if (isSameGroup) it.savedHomeworkItemsByStudent else emptyMap(),
                savedExamItemsByStudent = if (isSameGroup) it.savedExamItemsByStudent else emptyMap(),
                partialSaveReport = if (isSameGroup) it.partialSaveReport else null,
                isFinished = if (isSameGroup) it.isFinished else false,
                savedContext = if (isSameGroup) it.savedContext else null,
                reviewState = if (isSameGroup) it.reviewState else null,
                scanFeedback = null
            )
        }
        if (!isSameGroup) {
            loadExistingAttendanceForGroup(groupId)
        }
    }

    fun selectGrade(gradeId: String) {
        val state = _uiState.value
        val grade = state.grades.firstOrNull { it.id == gradeId }
        val students = allStudentsList.filter { it.gradeId == gradeId }
        val isSameGrade = state.selectedGroupId == null && state.selectedGradeId == gradeId
        val recMap = if (isSameGrade && state.recitationsMap.isNotEmpty()) {
            students.associate { it.studentId to (state.recitationsMap[it.studentId] ?: StudentRecitationInput()) }
        } else {
            students.associate { it.studentId to StudentRecitationInput() }
        }
        val hwMap = if (isSameGrade && state.homeworkMap.isNotEmpty()) {
            students.associate { it.studentId to (state.homeworkMap[it.studentId] ?: StudentHomeworkInput()) }
        } else {
            students.associate { it.studentId to StudentHomeworkInput() }
        }
        val exMap = if (isSameGrade && state.examsMap.isNotEmpty()) {
            students.associate { it.studentId to (state.examsMap[it.studentId] ?: StudentExamInput()) }
        } else {
            students.associate { it.studentId to StudentExamInput() }
        }

        _uiState.update {
            it.copy(
                selectedGroupId = null,
                selectedGroupName = "",
                selectedGradeId = gradeId,
                selectedGradeName = grade?.name ?: "المجموعة",
                studentsInGroup = students,
                recitationsMap = recMap,
                homeworkMap = hwMap,
                examsMap = exMap,
                presentStudentIds = if (isSameGrade) it.presentStudentIds else emptySet(),
                absentStudentIds = if (isSameGrade) it.absentStudentIds else emptySet(),
                lateStudentIds = if (isSameGrade) it.lateStudentIds else emptySet(),
                excusedStudentIds = if (isSameGrade) it.excusedStudentIds else emptySet(),
                attendanceStatusMap = if (isSameGrade) it.attendanceStatusMap else emptyMap(),
                recentScannedStudents = if (isSameGrade) it.recentScannedStudents else emptyList(),
                attendanceSaved = if (isSameGrade) it.attendanceSaved else false,
                savedAbsenceItems = if (isSameGrade) it.savedAbsenceItems else emptyList(),
                savedRecitationItemsByStudent = if (isSameGrade) it.savedRecitationItemsByStudent else emptyMap(),
                savedHomeworkItemsByStudent = if (isSameGrade) it.savedHomeworkItemsByStudent else emptyMap(),
                savedExamItemsByStudent = if (isSameGrade) it.savedExamItemsByStudent else emptyMap(),
                partialSaveReport = if (isSameGrade) it.partialSaveReport else null,
                isFinished = if (isSameGrade) it.isFinished else false,
                savedContext = if (isSameGrade) it.savedContext else null,
                reviewState = if (isSameGrade) it.reviewState else null,
                scanFeedback = null
            )
        }
        if (!isSameGrade) {
            loadExistingAttendanceForGrade(gradeId)
        }
    }

    fun loadExistingAttendanceForGroup(groupId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAttendance = true) }
            try {
                val state = _uiState.value
                val date = state.currentDate
                val res = attendanceRepository.fetchAttendanceForDateAndGroupResult(date, groupId)
                if (res.isFailure) {
                    val err = res.exceptionOrNull()
                    _uiState.update {
                        it.copy(
                            isLoadingAttendance = false,
                            errorMessage = err?.toArabicErrorMessage() ?: "تعذر جلب الحضور المسجل من السيرفر"
                        )
                    }
                    return@launch
                }
                val records = res.getOrNull().orEmpty()
                val groupStudentIds = allStudentsList.filter { it.groupId == groupId }.map { it.studentId }.toSet()
                val relevantRecords = records.filter { groupStudentIds.contains(it.studentId) }

                val loadedPresent = relevantRecords.filter { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
                    .map { it.studentId }.toSet()
                val loadedAbsent = relevantRecords.filter { it.status == AttendanceStatus.ABSENT }
                    .map { it.studentId }.toSet()
                val loadedLate = relevantRecords.filter { it.status == AttendanceStatus.LATE }
                    .map { it.studentId }.toSet()
                val loadedExcused = relevantRecords.filter { it.status == AttendanceStatus.EXCUSED }
                    .map { it.studentId }.toSet()
                val statusMap = relevantRecords.associate { it.studentId to it.status }

                _uiState.update { currentState ->
                    currentState.copy(
                        presentStudentIds = loadedPresent,
                        absentStudentIds = loadedAbsent,
                        lateStudentIds = loadedLate,
                        excusedStudentIds = loadedExcused,
                        attendanceStatusMap = statusMap,
                        isLoadingAttendance = false
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingAttendance = false,
                        errorMessage = e.toArabicErrorMessage()
                    )
                }
            }
        }
    }

    fun loadExistingAttendanceForGrade(gradeId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingAttendance = true) }
            try {
                val state = _uiState.value
                val date = state.currentDate
                val res = attendanceRepository.fetchAttendanceForDateAndGroupResult(date, null)
                if (res.isFailure) {
                    val err = res.exceptionOrNull()
                    _uiState.update {
                        it.copy(
                            isLoadingAttendance = false,
                            errorMessage = err?.toArabicErrorMessage() ?: "تعذر جلب الحضور المسجل من السيرفر"
                        )
                    }
                    return@launch
                }
                val records = res.getOrNull().orEmpty()
                val gradeStudentIds = allStudentsList.filter { it.gradeId == gradeId }.map { it.studentId }.toSet()
                val relevantRecords = records.filter { gradeStudentIds.contains(it.studentId) }

                val loadedPresent = relevantRecords.filter { it.status == AttendanceStatus.PRESENT || it.status == AttendanceStatus.LATE }
                    .map { it.studentId }.toSet()
                val loadedAbsent = relevantRecords.filter { it.status == AttendanceStatus.ABSENT }
                    .map { it.studentId }.toSet()
                val loadedLate = relevantRecords.filter { it.status == AttendanceStatus.LATE }
                    .map { it.studentId }.toSet()
                val loadedExcused = relevantRecords.filter { it.status == AttendanceStatus.EXCUSED }
                    .map { it.studentId }.toSet()
                val statusMap = relevantRecords.associate { it.studentId to it.status }

                _uiState.update { currentState ->
                    currentState.copy(
                        presentStudentIds = loadedPresent,
                        absentStudentIds = loadedAbsent,
                        lateStudentIds = loadedLate,
                        excusedStudentIds = loadedExcused,
                        attendanceStatusMap = statusMap,
                        isLoadingAttendance = false
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingAttendance = false,
                        errorMessage = e.toArabicErrorMessage()
                    )
                }
            }
        }
    }

    fun goToStep(step: StartLessonStep) {
        _uiState.update { it.copy(currentStep = step, scanFeedback = null, isScannerActive = false) }
    }

    fun toggleStudentPresent(studentId: String) {
        val student = allStudentsList.find { it.studentId == studentId }
        val studentName = student?.fullName ?: "الطالب"
        _uiState.update { state ->
            val set = state.presentStudentIds.toMutableSet()
            val absentSet = state.absentStudentIds.toMutableSet()
            val statusMap = state.attendanceStatusMap.toMutableMap()
            val msg: String

            if (set.contains(studentId)) {
                set.remove(studentId)
                absentSet.add(studentId)
                statusMap[studentId] = AttendanceStatus.ABSENT
                msg = "تم تسجيل $studentName غائبًا"
            } else {
                set.add(studentId)
                absentSet.remove(studentId)
                statusMap[studentId] = AttendanceStatus.PRESENT
                msg = "تم تسجيل حضور $studentName ✓"
            }
            state.copy(
                presentStudentIds = set,
                absentStudentIds = absentSet,
                attendanceStatusMap = statusMap,
                attendanceSaved = false,
                savedAbsenceItems = emptyList(),
                partialSaveReport = null,
                statusChangeMessage = msg
            )
        }
    }

    fun changeAbsentToPresent(studentId: String) {
        val student = allStudentsList.find { it.studentId == studentId }
        val studentName = student?.fullName ?: "الطالب"
        _uiState.update { state ->
            val updatedPresent = state.presentStudentIds + studentId
            val updatedAbsent = state.absentStudentIds - studentId
            val updatedStatusMap = state.attendanceStatusMap + (studentId to AttendanceStatus.PRESENT)
            val updatedRecent = if (student != null) {
                (listOf(student) + state.recentScannedStudents.filter { it.studentId != studentId }).take(5)
            } else state.recentScannedStudents

            state.copy(
                presentStudentIds = updatedPresent,
                absentStudentIds = updatedAbsent,
                attendanceStatusMap = updatedStatusMap,
                recentScannedStudents = updatedRecent,
                attendanceSaved = false,
                savedAbsenceItems = emptyList(),
                partialSaveReport = null,
                scanFeedback = if (student != null) ScanFeedback(student, ScanResultType.SUCCESS_PRESENT, student.studentCode) else null,
                statusChangeMessage = "تم تعديل حالة $studentName من غائب إلى حاضر بنجاح ✓"
            )
        }
    }

    fun clearStatusChangeMessage() {
        _uiState.update { it.copy(statusChangeMessage = null) }
    }

    fun togglePaymentStatus(studentId: String) {
        val state = _uiState.value
        if (studentId in state.togglingPaymentStudentIds) return
        val payment = state.paymentsMap[studentId]
        val newIsPaid = !(payment?.isPaid ?: false)
        _uiState.update {
            it.copy(
                togglingPaymentStudentIds = it.togglingPaymentStudentIds + studentId,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val result = paymentRepository.setPaymentStatus(
                    studentId = studentId,
                    year = state.currentYear,
                    month = state.currentMonth,
                    isPaid = newIsPaid,
                    amount = payment?.amount ?: 0.0
                )
                result.onSuccess { updatedPayment ->
                    _uiState.update { current ->
                        current.copy(
                            paymentsMap = current.paymentsMap + (studentId to updatedPayment),
                            togglingPaymentStudentIds = current.togglingPaymentStudentIds - studentId
                        )
                    }
                }.onFailure { err ->
                    _uiState.update { current ->
                        current.copy(
                            togglingPaymentStudentIds = current.togglingPaymentStudentIds - studentId,
                            errorMessage = err.toArabicErrorMessage()
                        )
                    }
                }
            } catch (ce: CancellationException) {
                _uiState.update { current ->
                    current.copy(togglingPaymentStudentIds = current.togglingPaymentStudentIds - studentId)
                }
                throw ce
            } catch (e: Exception) {
                _uiState.update { current ->
                    current.copy(
                        togglingPaymentStudentIds = current.togglingPaymentStudentIds - studentId,
                        errorMessage = e.toArabicErrorMessage()
                    )
                }
            }
        }
    }

    fun toggleScanner(active: Boolean) {
        _uiState.update { it.copy(isScannerActive = active, scanFeedback = null) }
    }

    fun processScannedBarcode(code: String) {
        val trimmed = code.trim()
        if (trimmed.isBlank()) return
        val state = _uiState.value
        val student = studentCodeMap[trimmed.lowercase()]

        if (student == null) {
            _uiState.update { it.copy(scanFeedback = ScanFeedback(null, ScanResultType.NOT_FOUND, trimmed)) }
            return
        }
        val inWrongScope = if (state.selectedGroupId != null) {
            student.groupId != state.selectedGroupId
        } else {
            student.gradeId != state.selectedGradeId
        }
        if (inWrongScope) {
            _uiState.update { it.copy(scanFeedback = ScanFeedback(student, ScanResultType.WRONG_GROUP, trimmed)) }
            return
        }
        if (state.presentStudentIds.contains(student.studentId)) {
            _uiState.update { it.copy(scanFeedback = ScanFeedback(student, ScanResultType.ALREADY_PRESENT, trimmed)) }
            return
        }
        if (state.absentStudentIds.contains(student.studentId) || 
            state.attendanceStatusMap[student.studentId] == AttendanceStatus.ABSENT) {
            if (state.scanFeedback?.type == ScanResultType.ALREADY_ABSENT && state.scanFeedback.student?.studentId == student.studentId) {
                changeAbsentToPresent(student.studentId)
                return
            }
            _uiState.update { it.copy(scanFeedback = ScanFeedback(student, ScanResultType.ALREADY_ABSENT, trimmed)) }
            return
        }

        val updatedPresent = state.presentStudentIds + student.studentId
        val updatedAbsent = state.absentStudentIds - student.studentId
        val updatedRecent = (listOf(student) + state.recentScannedStudents.filter { it.studentId != student.studentId }).take(5)
        val updatedStatusMap = state.attendanceStatusMap + (student.studentId to AttendanceStatus.PRESENT)

        _uiState.update {
            it.copy(
                presentStudentIds = updatedPresent,
                absentStudentIds = updatedAbsent,
                recentScannedStudents = updatedRecent,
                attendanceStatusMap = updatedStatusMap,
                attendanceSaved = false,
                savedAbsenceItems = emptyList(),
                partialSaveReport = null,
                scanFeedback = ScanFeedback(student, ScanResultType.SUCCESS_PRESENT, trimmed)
            )
        }
    }

    fun clearScanFeedback() {
        _uiState.update { it.copy(scanFeedback = null) }
    }

    // Recitation updates
    fun updateRecitationInput(studentId: String, update: (StudentRecitationInput) -> StudentRecitationInput) {
        _uiState.update { state ->
            val map = state.recitationsMap.toMutableMap()
            val current = map[studentId] ?: StudentRecitationInput()
            val updated = update(current)
            val resolved = if (updated.isRecorded != current.isRecorded) {
                updated
            } else {
                val hasInput = updated.title.isNotBlank() ||
                    updated.content.isNotBlank() ||
                    updated.scoreStr.isNotBlank() ||
                    updated.note.isNotBlank()
                updated.copy(isRecorded = hasInput)
            }
            map[studentId] = resolved
            state.copy(
                recitationsMap = map,
                savedRecitationItemsByStudent = state.savedRecitationItemsByStudent - studentId,
                partialSaveReport = null
            )
        }
    }

    fun clearRecitationInput(studentId: String) {
        _uiState.update { state ->
            val map = state.recitationsMap.toMutableMap()
            map[studentId] = StudentRecitationInput()
            state.copy(
                recitationsMap = map,
                savedRecitationItemsByStudent = state.savedRecitationItemsByStudent - studentId,
                partialSaveReport = null
            )
        }
    }

    fun setSkipRecitation(skip: Boolean) {
        _uiState.update { it.copy(skipRecitation = skip, partialSaveReport = null) }
    }

    // Homework updates
    fun updateHomeworkInput(studentId: String, update: (StudentHomeworkInput) -> StudentHomeworkInput) {
        _uiState.update { state ->
            val map = state.homeworkMap.toMutableMap()
            val current = map[studentId] ?: StudentHomeworkInput()
            val updated = update(current)
            val resolved = if (updated.isRecorded != current.isRecorded) {
                updated
            } else {
                val hasInput = updated.title.isNotBlank() || updated.note.isNotBlank()
                updated.copy(isRecorded = current.isRecorded || hasInput)
            }
            map[studentId] = resolved
            state.copy(
                homeworkMap = map,
                savedHomeworkItemsByStudent = state.savedHomeworkItemsByStudent - studentId,
                partialSaveReport = null
            )
        }
    }

    fun clearHomeworkInput(studentId: String) {
        _uiState.update { state ->
            val map = state.homeworkMap.toMutableMap()
            map[studentId] = StudentHomeworkInput()
            state.copy(
                homeworkMap = map,
                savedHomeworkItemsByStudent = state.savedHomeworkItemsByStudent - studentId,
                partialSaveReport = null
            )
        }
    }

    fun setSkipHomework(skip: Boolean) {
        _uiState.update { it.copy(skipHomework = skip, partialSaveReport = null) }
    }

    // Exam updates
    fun updateExamInput(studentId: String, update: (StudentExamInput) -> StudentExamInput) {
        _uiState.update { state ->
            val map = state.examsMap.toMutableMap()
            val current = map[studentId] ?: StudentExamInput()
            val updated = update(current)
            val resolved = if (updated.isRecorded != current.isRecorded) {
                updated
            } else {
                val hasInput = updated.examName.isNotBlank() ||
                    updated.scoreStr.isNotBlank() ||
                    updated.note.isNotBlank()
                updated.copy(isRecorded = hasInput)
            }
            map[studentId] = resolved
            state.copy(
                examsMap = map,
                savedExamItemsByStudent = state.savedExamItemsByStudent - studentId,
                partialSaveReport = null
            )
        }
    }

    fun clearExamInput(studentId: String) {
        _uiState.update { state ->
            val map = state.examsMap.toMutableMap()
            map[studentId] = StudentExamInput()
            state.copy(
                examsMap = map,
                savedExamItemsByStudent = state.savedExamItemsByStudent - studentId,
                partialSaveReport = null
            )
        }
    }

    fun setSkipExam(skip: Boolean) {
        _uiState.update { it.copy(skipExam = skip, partialSaveReport = null) }
    }

    fun checkReviewBeforeFinish() {
        val state = _uiState.value
        val unrecordedCount = state.studentsInGroup.size - state.presentStudentIds.size
        // If there are students not marked present, check if we want to show warning or finish directly
        if (unrecordedCount > 0 && !state.showUnrecordedWarning) {
            _uiState.update { it.copy(showUnrecordedWarning = true) }
        } else {
            finishLesson()
        }
    }

    fun dismissUnrecordedWarning() {
        _uiState.update { it.copy(showUnrecordedWarning = false) }
    }

    fun finishLesson() {
        val state = _uiState.value
        if (state.isSaving) return

        // 1. Validate Recitation scores before persistence (only for explicitly recorded entries)
        if (!state.skipRecitation) {
            for ((studentId, rec) in state.recitationsMap) {
                if (rec.isRecorded) {
                    val maxScore = rec.maxScoreStr.trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: 10.0
                    val score = rec.scoreStr.trim().toDoubleOrNull()
                    if (score == null || score < 0.0 || score > maxScore) {
                        val studentName = state.studentsInGroup.find { it.studentId == studentId }?.fullName ?: "الطالب"
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = "درجة التسميع غير صالحة لـ $studentName: يجب إدخال درجة بين 0 و $maxScore"
                            )
                        }
                        return
                    }
                }
            }
        }

        // 2. Validate Exam scores before persistence (only for explicitly recorded entries)
        if (!state.skipExam) {
            for ((studentId, ex) in state.examsMap) {
                if (ex.isRecorded) {
                    val maxScore = ex.maxScoreStr.trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: 10.0
                    val score = ex.scoreStr.trim().toDoubleOrNull()
                    if (score == null || score < 0.0 || score > maxScore) {
                        val studentName = state.studentsInGroup.find { it.studentId == studentId }?.fullName ?: "الطالب"
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                errorMessage = "درجة الامتحان غير صالحة لـ $studentName: يجب إدخال درجة بين 0 و $maxScore"
                            )
                        }
                        return
                    }
                }
            }
        }

        _uiState.update {
            it.copy(
                isSaving = true,
                showUnrecordedWarning = false,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val date = state.currentDate
                val students = state.studentsInGroup
                val studentNamesById = students.associate { it.studentId to it.fullName }
                val groupId: String? = state.selectedGroupId

                // Identify students who are absent
                val absentStudentIds = students
                    .filter { !state.presentStudentIds.contains(it.studentId) }
                    .map { it.studentId }
                    .toSet()

                // 1. Record Attendance (idempotent: skip if already confirmed on cloud in previous attempt)
                val absenceItems: List<LessonSavedItem>
                if (!state.attendanceSaved) {
                    val attRecords = students.map { s ->
                        val status = if (state.presentStudentIds.contains(s.studentId)) AttendanceStatus.PRESENT else AttendanceStatus.ABSENT
                        BatchAttendanceItemDto(studentId = s.studentId, status = status.name, note = null)
                    }
                    val attResult = attendanceRepository.recordBatchAttendance(date, attRecords, groupId)
                    val batchSync = attResult.getOrNull()
                    if (attResult.isFailure || batchSync == null || batchSync.syncStatus != BatchSyncStatus.SAVED_TO_CLOUD) {
                        val attErrMsg = attResult.exceptionOrNull()?.toArabicErrorMessage()
                            ?: "تعذر حفظ سجل الحضور على السيرفر. يرجى التحقق من الاتصال والمحاولة مرة أخرى."
                        val report = LessonSaveReport(
                            attendanceSaved = false,
                            attendanceError = attErrMsg
                        )
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                isFinished = false,
                                attendanceSaved = false,
                                partialSaveReport = report,
                                errorMessage = attErrMsg
                            )
                        }
                        return@launch
                    }

                    val collectedAbsence = mutableListOf<LessonSavedItem>()
                    if (absentStudentIds.isNotEmpty()) {
                        val savedAttRes = attendanceRepository.fetchAttendanceForDateAndGroupResult(date, groupId)
                        val savedAttendanceList = savedAttRes.getOrNull().orEmpty()
                        val savedMap = savedAttendanceList.associateBy { it.studentId }
                        for (studentId in absentStudentIds) {
                            val att = savedMap[studentId]
                            if (att != null && att.status == AttendanceStatus.ABSENT && att.attendanceId.isNotBlank()) {
                                val isOnline = isServerUUID(att.attendanceId)
                                collectedAbsence.add(
                                    LessonSavedItem(
                                        studentId = studentId,
                                        sourceId = att.attendanceId,
                                        eventType = NotificationEventType.ABSENCE,
                                        isConfirmedOnline = isOnline
                                    )
                                )
                            }
                        }
                    }
                    absenceItems = collectedAbsence
                    val newStatusMap = attRecords.associate { it.studentId to AttendanceStatus.fromString(it.status) }
                    _uiState.update {
                        it.copy(
                            attendanceSaved = true,
                            savedAbsenceItems = absenceItems,
                            absentStudentIds = absentStudentIds,
                            attendanceStatusMap = it.attendanceStatusMap + newStatusMap
                        )
                    }
                } else {
                    absenceItems = state.savedAbsenceItems
                }

                // 2. Record Recitations (checkpoint per student to prevent duplicates on retry)
                val savedRecsByStudent = _uiState.value.savedRecitationItemsByStudent.toMutableMap()
                val failedRecitations = mutableListOf<FailedLessonItem>()
                if (!state.skipRecitation) {
                    val recResults = coroutineScope {
                        state.recitationsMap.mapNotNull { (studentId, rec) ->
                            if (rec.isRecorded && !savedRecsByStudent.containsKey(studentId)) {
                                val maxScore = rec.maxScoreStr.trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: 10.0
                                val score = rec.scoreStr.trim().toDoubleOrNull()
                                if (score != null && score >= 0.0 && score <= maxScore) {
                                    async(ioDispatcher) {
                                        val recRes = recitationRepository.addRecitation(
                                            studentId = studentId,
                                            date = date,
                                            title = rec.title.trim().ifBlank { "تسميع الحصة" },
                                            content = rec.content.trim(),
                                            score = score,
                                            maxScore = maxScore,
                                            note = rec.note.trim().ifBlank { null }
                                        )
                                        studentId to recRes
                                    }
                                } else null
                            } else null
                        }.awaitAll()
                    }
                    for ((studentId, res) in recResults) {
                        val syncRes = res.getOrNull()
                        if (res.isSuccess && syncRes != null && syncRes.syncStatus == BatchSyncStatus.SAVED_TO_CLOUD) {
                            val savedRec = syncRes.data
                            savedRecsByStudent[studentId] = LessonSavedItem(
                                studentId = studentId,
                                sourceId = savedRec.recitationId,
                                eventType = NotificationEventType.RECITATION,
                                isConfirmedOnline = isServerUUID(savedRec.recitationId)
                            )
                        } else {
                            failedRecitations.add(
                                FailedLessonItem(
                                    studentId = studentId,
                                    studentName = studentNamesById[studentId] ?: "الطالب",
                                    message = res.exceptionOrNull()?.toArabicErrorMessage() ?: "تعذر حفظ التسميع"
                                )
                            )
                        }
                    }
                    _uiState.update { it.copy(savedRecitationItemsByStudent = savedRecsByStudent.toMap()) }
                }

                // 3. Record Homework (checkpoint per student)
                val savedHwByStudent = _uiState.value.savedHomeworkItemsByStudent.toMutableMap()
                val failedHomework = mutableListOf<FailedLessonItem>()
                if (!state.skipHomework) {
                    val hwResults = coroutineScope {
                        state.homeworkMap.mapNotNull { (studentId, hw) ->
                            if (hw.isRecorded && !savedHwByStudent.containsKey(studentId)) {
                                async(ioDispatcher) {
                                    val hwRes = homeworkRepository.addHomework(
                                        studentId = studentId,
                                        date = date,
                                        title = hw.title.trim().ifBlank { "واجب الحصة" },
                                        status = hw.status,
                                        note = hw.note.trim().ifBlank { null }
                                    )
                                    studentId to hwRes
                                }
                            } else null
                        }.awaitAll()
                    }
                    for ((studentId, res) in hwResults) {
                        val syncRes = res.getOrNull()
                        if (res.isSuccess && syncRes != null && syncRes.syncStatus == BatchSyncStatus.SAVED_TO_CLOUD) {
                            val savedHw = syncRes.data
                            savedHwByStudent[studentId] = LessonSavedItem(
                                studentId = studentId,
                                sourceId = savedHw.homeworkId,
                                eventType = NotificationEventType.HOMEWORK,
                                isConfirmedOnline = isServerUUID(savedHw.homeworkId)
                            )
                        } else {
                            failedHomework.add(
                                FailedLessonItem(
                                    studentId = studentId,
                                    studentName = studentNamesById[studentId] ?: "الطالب",
                                    message = res.exceptionOrNull()?.toArabicErrorMessage() ?: "تعذر حفظ الواجب"
                                )
                            )
                        }
                    }
                    _uiState.update { it.copy(savedHomeworkItemsByStudent = savedHwByStudent.toMap()) }
                }

                // 4. Record Exams (checkpoint per student)
                val savedExamsByStudent = _uiState.value.savedExamItemsByStudent.toMutableMap()
                val failedExams = mutableListOf<FailedLessonItem>()
                if (!state.skipExam) {
                    val examResults = coroutineScope {
                        state.examsMap.mapNotNull { (studentId, ex) ->
                            if (ex.isRecorded && ex.scoreStr.isNotBlank() && !savedExamsByStudent.containsKey(studentId)) {
                                val maxScore = ex.maxScoreStr.trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: 10.0
                                val score = ex.scoreStr.trim().toDoubleOrNull()
                                if (score != null && score >= 0.0 && score <= maxScore) {
                                    async(ioDispatcher) {
                                        val exRes = examRepository.addExam(
                                            studentId = studentId,
                                            date = date,
                                            examName = ex.examName.trim().ifBlank { "امتحان الحصة" },
                                            subject = state.selectedGradeName.ifBlank { state.selectedGroupName },
                                            score = score,
                                            maxScore = maxScore,
                                            note = ex.note.trim().ifBlank { null }
                                        )
                                        studentId to exRes
                                    }
                                } else null
                            } else null
                        }.awaitAll()
                    }
                    for ((studentId, res) in examResults) {
                        val syncRes = res.getOrNull()
                        if (res.isSuccess && syncRes != null && syncRes.syncStatus == BatchSyncStatus.SAVED_TO_CLOUD) {
                            val savedEx = syncRes.data
                            savedExamsByStudent[studentId] = LessonSavedItem(
                                studentId = studentId,
                                sourceId = savedEx.examId,
                                eventType = NotificationEventType.EXAM,
                                isConfirmedOnline = isServerUUID(savedEx.examId)
                            )
                        } else {
                            failedExams.add(
                                FailedLessonItem(
                                    studentId = studentId,
                                    studentName = studentNamesById[studentId] ?: "الطالب",
                                    message = res.exceptionOrNull()?.toArabicErrorMessage() ?: "تعذر حفظ الامتحان"
                                )
                            )
                        }
                    }
                    _uiState.update { it.copy(savedExamItemsByStudent = savedExamsByStudent.toMap()) }
                }

                val report = LessonSaveReport(
                    attendanceSaved = true,
                    savedRecitationCount = savedRecsByStudent.size,
                    failedRecitations = failedRecitations,
                    savedHomeworkCount = savedHwByStudent.size,
                    failedHomework = failedHomework,
                    savedExamCount = savedExamsByStudent.size,
                    failedExams = failedExams
                )

                if (report.hasFailures) {
                    val totalFailed = failedRecitations.size + failedHomework.size + failedExams.size
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isFinished = false,
                            partialSaveReport = report,
                            errorMessage = "تم حفظ الحضور، لكن تعذر حفظ $totalFailed عنصر. أعد المحاولة لحفظ العناصر المتبقية دون تكرار."
                        )
                    }
                    return@launch
                }

                val savedContext = LessonSavedContext(
                    absenceItems = absenceItems,
                    recitationItems = savedRecsByStudent.values.toList(),
                    homeworkItems = savedHwByStudent.values.toList(),
                    examItems = savedExamsByStudent.values.toList()
                )

                val reviewState = buildNotificationReviewState(
                    savedContext = savedContext,
                    students = students,
                    presentStudentIds = state.presentStudentIds
                )

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isFinished = true,
                        partialSaveReport = null,
                        currentStep = StartLessonStep.NOTIFICATION_REVIEW,
                        savedContext = savedContext,
                        reviewState = reviewState
                    )
                }
            } catch (ce: CancellationException) {
                _uiState.update { it.copy(isSaving = false) }
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        isFinished = false,
                        errorMessage = e.toArabicErrorMessage()
                    )
                }
            }
        }
    }

    private fun buildNotificationReviewState(
        savedContext: LessonSavedContext,
        students: List<Student>,
        presentStudentIds: Set<String>
    ): NotificationReviewState {
        val studentMap = students.associateBy { it.studentId }

        fun mapToReviewItem(item: LessonSavedItem): NotificationReviewItem {
            val student = studentMap[item.studentId]
            val studentName = student?.fullName ?: "طالب"
            val parentPhone = student?.parentPhone?.trim() ?: ""
            val whatsappEnabled = student?.hasWhatsApp ?: false

            val (isEligible, reason) = when {
                !item.isConfirmedOnline -> Pair(false, "معلق حتى تكتمل المزامنة")
                parentPhone.isBlank() -> Pair(false, "لا يوجد رقم هاتف لولي الأمر")
                !whatsappEnabled -> Pair(false, "واتساب غير مفعّل للطالب")
                item.sourceId.isBlank() -> Pair(false, "معرف المصدر غير صالح")
                else -> Pair(true, null)
            }

            return NotificationReviewItem(
                studentId = item.studentId,
                sourceId = item.sourceId,
                eventType = item.eventType,
                studentName = studentName,
                parentPhone = parentPhone,
                whatsappEnabled = whatsappEnabled,
                isConfirmedOnline = item.isConfirmedOnline,
                eligibleForNotification = isEligible,
                ineligibilityReason = reason
            )
        }

        val totalStudents = students.size
        val presentStudents = presentStudentIds.size
        val absentStudents = totalStudents - presentStudents

        return NotificationReviewState(
            absenceItems = savedContext.absenceItems.map(::mapToReviewItem),
            recitationItems = savedContext.recitationItems.map(::mapToReviewItem),
            homeworkItems = savedContext.homeworkItems.map(::mapToReviewItem),
            examItems = savedContext.examItems.map(::mapToReviewItem),
            totalStudents = totalStudents,
            presentStudents = presentStudents,
            absentStudents = absentStudents,
            recitationCount = savedContext.recitationItems.size,
            homeworkCount = savedContext.homeworkItems.size,
            examCount = savedContext.examItems.size
        )
    }

    fun submitNotificationEvents(onComplete: (() -> Unit)? = null) {
        val state = _uiState.value
        if (state.isSubmittingNotifications) return // UX guard against rapid double taps

        val review = state.reviewState ?: run {
            onComplete?.invoke()
            return
        }

        _uiState.update { it.copy(isSubmittingNotifications = true) }

        viewModelScope.launch {
            try {
                val allReviewItems = review.absenceItems +
                        review.recitationItems +
                        review.homeworkItems +
                        review.examItems

                var createdCount = 0
                var alreadyExistsCount = 0
                var failedCount = 0
                var skippedCount = 0
                val failedList = mutableListOf<FailedNotificationItem>()

                val eligibleItems = allReviewItems.filter { item ->
                    item.isConfirmedOnline &&
                            item.eligibleForNotification &&
                            item.studentId.isNotBlank() &&
                            item.sourceId.isNotBlank()
                }

                skippedCount = allReviewItems.size - eligibleItems.size

                val results = coroutineScope {
                    eligibleItems.map { item ->
                        async(ioDispatcher) {
                            if (item.eventType == NotificationEventType.RECITATION) {
                                Pair(item, notificationEventRepository.approveRecitation(item.sourceId))
                            } else {
                                val domainEvent: NotificationEvent = when (item.eventType) {
                                    NotificationEventType.ABSENCE -> NotificationEvent.Absence(
                                        teacherId = "",
                                        studentId = item.studentId,
                                        attendanceId = item.sourceId
                                    )
                                    NotificationEventType.HOMEWORK -> NotificationEvent.Homework(
                                        teacherId = "",
                                        studentId = item.studentId,
                                        homeworkId = item.sourceId
                                    )
                                    NotificationEventType.EXAM -> NotificationEvent.Exam(
                                        teacherId = "",
                                        studentId = item.studentId,
                                        examId = item.sourceId
                                    )
                                    NotificationEventType.MONTHLY_REPORT -> NotificationEvent.MonthlyReport(
                                        teacherId = "",
                                        studentId = item.studentId,
                                        monthlyReportId = item.sourceId
                                    )
                                    else -> throw IllegalArgumentException("Unsupported event type: ${item.eventType}")
                                }
                                Pair(item, notificationEventRepository.createNotificationEvent(domainEvent))
                            }
                        }
                    }.awaitAll()
                }

                for ((item, result) in results) {
                    if (item.eventType == NotificationEventType.RECITATION) {
                        if ((result as Result<*>).isSuccess) {
                            createdCount++
                        } else {
                            failedCount++
                            failedList.add(FailedNotificationItem(item.studentId, item.sourceId, item.eventType, (result as Result<*>).exceptionOrNull()?.message ?: "فشل"))
                        }
                    } else {
                        when (result) {
                            is NotificationEventCreationResult.Created -> {
                                createdCount++
                            }
                            is NotificationEventCreationResult.AlreadyExists -> {
                                alreadyExistsCount++
                            }
                            is NotificationEventCreationResult.Failed -> {
                                failedCount++
                                failedList.add(
                                    FailedNotificationItem(
                                        studentId = item.studentId,
                                        sourceId = item.sourceId,
                                        eventType = item.eventType,
                                        message = result.message
                                    )
                                )
                            }
                        }
                    }
                }

                val summary = NotificationEventCreationSummary(
                    createdCount = createdCount,
                    alreadyExistsCount = alreadyExistsCount,
                    failedCount = failedCount,
                    skippedCount = skippedCount,
                    failedItems = failedList
                )

                if (failedCount > 0) {
                    _uiState.update {
                        it.copy(
                            isSubmittingNotifications = false,
                            notificationCreationSummary = summary,
                            errorMessage = "تعذر تجهيز بعض الإشعارات ($failedCount إشعار)؛ تم حفظ بيانات الحصة ويمكنك مراجعة الأخطاء."
                        )
                    }
                    return@launch
                }

                val totalEligibleEvents = createdCount + alreadyExistsCount
                if (totalEligibleEvents > 0) {
                    val distinctEventTypes = eligibleItems
                        .filter { item ->
                            failedList.none { it.sourceId == item.sourceId && it.eventType == item.eventType }
                        }
                        .map { it.eventType }
                        .distinct()
                    val workerEventTypeArg: String? = if (distinctEventTypes.size == 1) {
                        distinctEventTypes.first().name
                    } else {
                        null
                    }
                    val workerResult = notificationEventRepository.triggerNotificationWorker(workerEventTypeArg)
                    when (workerResult) {
                        is NotificationWorkerResult.Success -> {
                            _uiState.update {
                                it.copy(
                                    isSubmittingNotifications = false,
                                    notificationCreationSummary = summary
                                )
                            }
                            onComplete?.invoke()
                        }
                        is NotificationWorkerResult.Error -> {
                            _uiState.update {
                                it.copy(
                                    isSubmittingNotifications = false,
                                    notificationCreationSummary = summary,
                                    errorMessage = "تم تجهيز الإشعارات في قائمة الانتظار، لكن تعذر تشغيل معالج الإرسال الفوري: ${workerResult.message}. يمكنك الضغط مجددًا لإعادة المحاولة دون تكرار الإشعارات."
                                )
                            }
                        }
                        is NotificationWorkerResult.Skipped -> {
                            _uiState.update {
                                it.copy(
                                    isSubmittingNotifications = false,
                                    notificationCreationSummary = summary,
                                    errorMessage = "تم تجهيز الإشعارات في قائمة الانتظار، وتم تخطي التشغيل التلقائي: ${workerResult.reason}"
                                )
                            }
                            onComplete?.invoke()
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isSubmittingNotifications = false,
                            notificationCreationSummary = summary
                        )
                    }
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmittingNotifications = false,
                        errorMessage = e.message ?: "حدث خطأ أثناء إنشاء أحداث الإشعارات"
                    )
                }
            }
        }
    }

    private fun isServerUUID(id: String): Boolean {
        if (id.isBlank()) return false
        return try {
            java.util.UUID.fromString(id)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
