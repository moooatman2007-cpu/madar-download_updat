package com.example.ui.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.Grade
import com.example.core.model.Teacher
import com.example.data.repository.GradeRepository
import com.example.data.repository.RepositoryProvider
import com.example.data.repository.StudentRepository
import com.example.data.repository.SupabaseGradeRepository
import com.example.data.repository.TeacherRepository
import com.example.util.isOfflineOrNetworkError
import com.example.util.toArabicErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TeacherProfileUiState(
    val id: String = "",
    val fullName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val subject: String = "",
    val customSubject: String = "",
    val isCustomSubject: Boolean = false,
    val centerName: String = "",
    val educationalStage: String = "",
    val avatarUrl: String? = null,
    val grades: List<Grade> = emptyList(),
    val isLoading: Boolean = false,
    val isUploadingAvatar: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val nameError: String? = null,
    val loadError: String? = null,
    val isOfflineError: Boolean = false
)

val PREDEFINED_STAGES = listOf(
    "ابتدائي",
    "إعدادي",
    "ثانوي"
)

val PREDEFINED_SUBJECTS = listOf(
    "قرآن كريم",
    "لغة عربية",
    "رياضيات",
    "لغة إنجليزية",
    "علوم",
    "دراسات اجتماعية",
    "أخرى"
)

class TeacherProfileViewModel(
    private val teacherRepository: TeacherRepository = RepositoryProvider.teacherRepository,
    private val gradeRepository: GradeRepository = RepositoryProvider.gradeRepository,
    private val studentRepository: StudentRepository = RepositoryProvider.studentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TeacherProfileUiState())
    val uiState: StateFlow<TeacherProfileUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private var gradesObserved = false

    fun loadData() {
        if (!gradesObserved) {
            gradesObserved = true
            viewModelScope.launch {
                combine(
                    gradeRepository.getGrades(),
                    studentRepository.getStudents()
                ) { gradesList, studentsList ->
                    val activeStudents = studentsList.filter { it.deletedAt == null }
                    val countsByGrade = activeStudents.groupBy { it.gradeId }.mapValues { it.value.size }
                    gradesList.map { grade ->
                        grade.copy(studentCount = countsByGrade[grade.id] ?: 0)
                    }.sortedBy { it.displayOrder }
                }.collect { updatedGrades ->
                    _uiState.update { it.copy(grades = updatedGrades) }
                }
            }
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, loadError = null, isOfflineError = false) }
            try {
                val teacher = teacherRepository.fetchCurrentTeacher()
                if (teacher != null) {
                    val gradesRes = gradeRepository.refreshGradesResult(teacher.educationalStage)
                    val studentsRes = studentRepository.refreshStudents()
                    val subErr = gradesRes.exceptionOrNull() ?: studentsRes.exceptionOrNull()
                    val isCustom = teacher.subject != null && !PREDEFINED_SUBJECTS.contains(teacher.subject) && teacher.subject != "أخرى"
                    val selectedSubj = if (isCustom) "أخرى" else (teacher.subject ?: "")

                    _uiState.update {
                        it.copy(
                            id = teacher.id,
                            fullName = teacher.fullName,
                            email = teacher.email,
                            phoneNumber = teacher.phoneNumber ?: "",
                            subject = selectedSubj,
                            customSubject = if (isCustom) (teacher.subject ?: "") else "",
                            isCustomSubject = isCustom || selectedSubj == "أخرى",
                            centerName = teacher.centerName ?: "",
                            educationalStage = teacher.educationalStage ?: "",
                            avatarUrl = teacher.avatarUrl,
                            isLoading = false,
                            loadError = subErr?.toArabicErrorMessage(),
                            isOfflineError = subErr?.isOfflineOrNetworkError() ?: false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loadError = "تعذر تحميل بيانات الحساب من الخادم.",
                            isOfflineError = false
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
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

    fun onFullNameChange(value: String) {
        _uiState.update { 
            it.copy(
                fullName = value,
                nameError = if (value.trim().isBlank()) "اسم المدرس إجباري ولا يمكن أن يكون فارغاً" else null
            ) 
        }
    }

    fun onPhoneNumberChange(value: String) {
        _uiState.update { it.copy(phoneNumber = value) }
    }

    fun onSubjectChange(value: String) {
        val isCustom = value == "أخرى"
        _uiState.update {
            it.copy(
                subject = value,
                isCustomSubject = isCustom
            )
        }
    }

    fun onCustomSubjectChange(value: String) {
        _uiState.update { it.copy(customSubject = value) }
    }

    fun onCenterNameChange(value: String) {
        _uiState.update { it.copy(centerName = value) }
    }

    fun onEducationalStageChange(value: String) {
        _uiState.update { it.copy(educationalStage = value) }
    }

    private var avatarUploadJob: kotlinx.coroutines.Job? = null
    private var pendingAvatarReceipt: com.example.util.ProcessedReceipt? = null

    fun uploadAvatarFromUri(context: Context, uri: Uri) {
        if (_uiState.value.isUploadingAvatar) return
        avatarUploadJob?.cancel()
        avatarUploadJob = viewModelScope.launch {
            _uiState.update { it.copy(isUploadingAvatar = true, errorMessage = null, successMessage = null) }
            try {
                val processResult = com.example.util.ReceiptFileProcessor.processUri(context, uri)
                val processed = processResult.getOrElse { error ->
                    _uiState.update {
                        it.copy(
                            isUploadingAvatar = false,
                            errorMessage = error.localizedMessage ?: "تعذر قراءة ملف الصورة المحدد."
                        )
                    }
                    return@launch
                }

                if (processed.isPdf) {
                    com.example.util.ReceiptFileProcessor.cleanupCacheFile(processed.cacheFilePath)
                    _uiState.update {
                        it.copy(
                            isUploadingAvatar = false,
                            errorMessage = "يرجى اختيار صورة (JPG, PNG, WEBP) وليس ملف PDF."
                        )
                    }
                    return@launch
                }

                pendingAvatarReceipt = processed
                performAvatarUpload(processed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isUploadingAvatar = false,
                        errorMessage = "حدث خطأ غير متوقع أثناء المعالجة: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun retryAvatarUpload() {
        val pending = pendingAvatarReceipt ?: return
        if (_uiState.value.isUploadingAvatar) return
        avatarUploadJob?.cancel()
        avatarUploadJob = viewModelScope.launch {
            _uiState.update { it.copy(isUploadingAvatar = true, errorMessage = null, successMessage = null) }
            performAvatarUpload(pending)
        }
    }

    private suspend fun performAvatarUpload(processed: com.example.util.ProcessedReceipt) {
        val result = teacherRepository.uploadAvatar(
            bytes = processed.bytes,
            fileName = processed.storageFileName,
            mimeType = processed.mimeType
        )
        result.onSuccess { url ->
            com.example.util.ReceiptFileProcessor.cleanupCacheFile(processed.cacheFilePath)
            pendingAvatarReceipt = null
            _uiState.update {
                it.copy(
                    avatarUrl = url,
                    isUploadingAvatar = false,
                    successMessage = "تم رفع الصورة بنجاح"
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    isUploadingAvatar = false,
                    errorMessage = error.localizedMessage ?: "حدث خطأ أثناء رفع الصورة."
                )
            }
        }
    }

    fun deleteAvatar() {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploadingAvatar = true, errorMessage = null, successMessage = null) }
            val result = teacherRepository.deleteAvatar()
            result.onSuccess {
                _uiState.update { 
                    it.copy(
                        avatarUrl = null,
                        isUploadingAvatar = false,
                        successMessage = "تم حذف الصورة الشخصية"
                    ) 
                }
            }.onFailure { error ->
                _uiState.update { 
                    it.copy(
                        isUploadingAvatar = false,
                        errorMessage = error.localizedMessage ?: "حدث خطأ أثناء حذف الصورة."
                    ) 
                }
            }
        }
    }

    fun saveProfile() {
        val state = _uiState.value
        if (state.fullName.trim().isBlank()) {
            _uiState.update { it.copy(nameError = "اسم المدرس إجباري ولا يمكن ترك الحقل فارغاً") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }

            val finalSubject = if (state.subject == "أخرى") {
                state.customSubject.trim()
            } else {
                state.subject.trim()
            }

            val result = teacherRepository.updateTeacherProfile(
                fullName = state.fullName.trim(),
                phoneNumber = state.phoneNumber.trim(),
                subject = finalSubject,
                centerName = state.centerName.trim(),
                educationalStage = state.educationalStage.trim(),
                avatarUrl = state.avatarUrl
            )

            result.onSuccess {
                val newStage = state.educationalStage.trim()
                gradeRepository.ensureGradesForStage(newStage)
                gradeRepository.refreshGrades()
                _uiState.update { 
                    it.copy(
                        isSaving = false,
                        successMessage = "تم حفظ بيانات الحساب بنجاح"
                    ) 
                }
            }.onFailure { error ->
                _uiState.update { 
                    it.copy(
                        isSaving = false,
                        errorMessage = error.toArabicErrorMessage()
                    ) 
                }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
