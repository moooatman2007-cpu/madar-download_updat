package com.example.ui.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.EducationalStages
import com.example.core.model.Grade
import com.example.data.SupabaseClientProvider
import com.example.data.repository.GradeRepository
import com.example.data.repository.RepositoryProvider
import com.example.data.repository.StudentRepository
import com.example.data.repository.TeacherRepository
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class GradesUiState(
    val grades: List<Grade> = emptyList(),
    val educationalStage: String = EducationalStages.PREPARATORY,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val showAddEditDialog: Boolean = false,
    val gradeToEdit: Grade? = null,
    val gradeNameInput: String = "",
    val gradeOrderInput: String = "",
    val inputError: String? = null,
    val gradeToDelete: Grade? = null
)

sealed class GradesEvent {
    data class ShowMessage(val message: String) : GradesEvent()
}

class GradesViewModel(
    private val gradeRepository: GradeRepository = RepositoryProvider.gradeRepository,
    private val studentRepository: StudentRepository = RepositoryProvider.studentRepository,
    private val teacherRepository: TeacherRepository = RepositoryProvider.teacherRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GradesUiState())
    val uiState: StateFlow<GradesUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<GradesEvent>()
    val events: SharedFlow<GradesEvent> = _events.asSharedFlow()

    private var currentTeacherId: String? = null

    init {
        observeData()
        refresh()
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                gradeRepository.getGrades(),
                studentRepository.getStudents(),
                teacherRepository.getCurrentTeacher()
            ) { gradesList, studentsList, teacher ->
                val counts = studentsList
                    .filter { it.deletedAt == null }
                    .groupBy { it.gradeId }
                    .mapValues { it.value.size }

                currentTeacherId = teacher?.id
                    ?: SupabaseClientProvider.mockTeacherId
                    ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }

                val stage = teacher?.educationalStage?.ifBlank { null } ?: EducationalStages.PREPARATORY
                val enriched = gradesList
                    .map { grade ->
                        grade.copy(studentCount = counts[grade.id] ?: 0)
                    }
                    .sortedBy { it.displayOrder }

                Pair(stage, enriched)
            }.collect { (stage, enrichedGrades) ->
                _uiState.update {
                    it.copy(
                        grades = enrichedGrades,
                        educationalStage = stage,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val teacher = teacherRepository.fetchCurrentTeacher()
                gradeRepository.refreshGrades(teacher?.educationalStage)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun openAddDialog() {
        val nextOrder = (_uiState.value.grades.maxOfOrNull { it.displayOrder } ?: 0) + 1
        _uiState.update {
            it.copy(
                showAddEditDialog = true,
                gradeToEdit = null,
                gradeNameInput = "",
                gradeOrderInput = nextOrder.toString(),
                inputError = null
            )
        }
    }

    fun openEditDialog(grade: Grade) {
        _uiState.update {
            it.copy(
                showAddEditDialog = true,
                gradeToEdit = grade,
                gradeNameInput = grade.name,
                gradeOrderInput = grade.displayOrder.toString(),
                inputError = null
            )
        }
    }

    fun dismissAddEditDialog() {
        _uiState.update {
            it.copy(
                showAddEditDialog = false,
                gradeToEdit = null,
                inputError = null
            )
        }
    }

    fun onGradeNameChange(value: String) {
        _uiState.update { it.copy(gradeNameInput = value, inputError = null) }
    }

    fun onGradeOrderChange(value: String) {
        val clean = value.filter { it.isDigit() }
        _uiState.update { it.copy(gradeOrderInput = clean, inputError = null) }
    }

    fun saveGrade() {
        if (_uiState.value.isSaving) return
        val name = _uiState.value.gradeNameInput.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(inputError = "يرجى إدخال اسم الصف الدراسي") }
            return
        }

        val parsedOrder = _uiState.value.gradeOrderInput.trim().toIntOrNull()
        val order = if (parsedOrder != null && parsedOrder > 0) {
            parsedOrder
        } else {
            (_uiState.value.grades.maxOfOrNull { it.displayOrder } ?: 0) + 1
        }

        val duplicateName = _uiState.value.grades.any {
            it.id != _uiState.value.gradeToEdit?.id &&
                it.name.trim().equals(name, ignoreCase = true)
        }
        if (duplicateName) {
            _uiState.update { it.copy(inputError = "يوجد صف بنفس الاسم بالفعل") }
            return
        }

        val editing = _uiState.value.gradeToEdit
        _uiState.update { it.copy(isSaving = true, inputError = null) }
        viewModelScope.launch {
            try {
                val success = if (editing == null) {
                    val newGrade = Grade(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        displayOrder = order,
                        studentCount = 0,
                        teacherId = currentTeacherId
                    )
                    gradeRepository.addGrade(newGrade)
                } else {
                    gradeRepository.updateGrade(
                        editing.copy(
                            name = name,
                            displayOrder = order,
                            teacherId = editing.teacherId ?: currentTeacherId
                        )
                    )
                }

                if (success) {
                    dismissAddEditDialog()
                    _events.emit(
                        GradesEvent.ShowMessage(
                            if (editing == null) "تمت إضافة الصف بنجاح" else "تم تحديث الصف بنجاح"
                        )
                    )
                } else {
                    _events.emit(GradesEvent.ShowMessage("تعذر حفظ الصف الدراسي، يرجى المحاولة مرة أخرى"))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun requestDeleteGrade(grade: Grade) {
        if (grade.studentCount > 0) {
            viewModelScope.launch {
                _events.emit(
                    GradesEvent.ShowMessage(
                        "لا يمكن حذف \"${grade.name}\" لوجود ${grade.studentCount} طالب مسجل فيه. انقل الطلاب أولاً."
                    )
                )
            }
            return
        }
        _uiState.update { it.copy(gradeToDelete = grade) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(gradeToDelete = null) }
    }

    fun confirmDeleteGrade() {
        if (_uiState.value.isSaving) return
        val grade = _uiState.value.gradeToDelete ?: return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                dismissDeleteDialog()
                val success = gradeRepository.deleteGrade(grade.id)
                if (success) {
                    _events.emit(GradesEvent.ShowMessage("تم حذف الصف بنجاح"))
                } else {
                    _events.emit(GradesEvent.ShowMessage("تعذر حذف الصف الدراسي"))
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun restoreStageGrades() {
        if (_uiState.value.isSaving) return
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                gradeRepository.ensureGradesForStage(_uiState.value.educationalStage)
                _events.emit(GradesEvent.ShowMessage("تم تحديث صفوف المرحلة (${_uiState.value.educationalStage})"))
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }
}
