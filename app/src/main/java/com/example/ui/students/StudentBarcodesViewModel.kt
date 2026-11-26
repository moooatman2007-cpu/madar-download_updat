package com.example.ui.students

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.Grade
import com.example.core.model.Student
import com.example.data.repository.GradeRepository
import com.example.data.repository.RepositoryProvider
import com.example.data.repository.StudentRepository
import com.example.util.BarcodePdfExporter
import com.example.util.isOfflineOrNetworkError
import com.example.util.toArabicErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StudentBarcodesUiState(
    val searchQuery: String = "",
    val selectedGradeId: String? = null,
    val grades: List<Grade> = emptyList(),
    val students: List<Student> = emptyList(),
    val filteredStudents: List<Student> = emptyList(),
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val errorMessage: String? = null,
    val isOfflineError: Boolean = false
)

class StudentBarcodesViewModel(
    private val studentRepository: StudentRepository = RepositoryProvider.studentRepository,
    private val gradeRepository: GradeRepository = RepositoryProvider.gradeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StudentBarcodesUiState(isLoading = true))
    val uiState: StateFlow<StudentBarcodesUiState> = _uiState.asStateFlow()

    private var allActiveStudents: List<Student> = emptyList()

    init {
        loadData()
    }

    fun retry() {
        refreshFromServer()
    }

    private fun refreshFromServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, isOfflineError = false) }
            try {
                coroutineScope {
                    val gradesDef = async { gradeRepository.refreshGradesResult() }
                    val studentsDef = async { studentRepository.refreshStudents() }

                    val gradesRes = gradesDef.await()
                    val studentsRes = studentsDef.await()

                    val firstErr = gradesRes.exceptionOrNull() ?: studentsRes.exceptionOrNull()
                    if (firstErr != null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = firstErr.toArabicErrorMessage(),
                                isOfflineError = firstErr.isOfflineOrNetworkError()
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = null,
                                isOfflineError = false
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.toArabicErrorMessage(),
                        isOfflineError = e.isOfflineOrNetworkError()
                    )
                }
            }
        }
    }

    private fun loadData() {
        refreshFromServer()

        viewModelScope.launch {
            combine(
                gradeRepository.getGrades(),
                studentRepository.getStudents()
            ) { gradesList, studentsList ->
                val active = studentsList.filter { it.deletedAt == null }
                allActiveStudents = active
                Pair(gradesList, active)
            }.catch { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error.toArabicErrorMessage(),
                        isOfflineError = error.isOfflineOrNetworkError()
                    )
                }
            }.collect { (gradesList, activeStudents) ->
                _uiState.update { state ->
                    state.copy(
                        grades = gradesList,
                        students = activeStudents,
                        isLoading = if (gradesList.isNotEmpty() || activeStudents.isNotEmpty()) false else state.isLoading
                    )
                }
                applyFilters()
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFilters()
    }

    fun onGradeSelected(gradeId: String?) {
        _uiState.update { it.copy(selectedGradeId = gradeId) }
        applyFilters()
    }

    private fun applyFilters() {
        _uiState.update { state ->
            val query = state.searchQuery.trim().lowercase()
            val filtered = allActiveStudents.filter { student ->
                val matchesGrade = state.selectedGradeId == null || student.gradeId == state.selectedGradeId
                val matchesQuery = query.isEmpty() ||
                    student.fullName.lowercase().contains(query) ||
                    student.studentCode.lowercase().contains(query)
                matchesGrade && matchesQuery
            }
            state.copy(filteredStudents = filtered)
        }
    }

    fun downloadPdf(context: Context) {
        val currentState = _uiState.value
        if (currentState.isExporting) return
        val targetStudents = currentState.filteredStudents
        if (targetStudents.isEmpty()) {
            Toast.makeText(context, "لا يوجد طلاب لتصدير ملف PDF", Toast.LENGTH_SHORT).show()
            return
        }

        _uiState.update { it.copy(isExporting = true) }
        val appContext = context.applicationContext

        viewModelScope.launch {
            try {
                val file = BarcodePdfExporter.generatePdfFile(appContext, targetStudents)
                _uiState.update { it.copy(isExporting = false) }
                if (file != null) {
                    BarcodePdfExporter.sharePdf(context, file)
                } else {
                    Toast.makeText(appContext, "فشل إنشاء ملف PDF", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isExporting = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isExporting = false, errorMessage = "فشل تصدير ملف PDF: ${e.message}") }
                Toast.makeText(appContext, "فشل إنشاء ملف PDF", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun printBarcodes(context: Context) {
        val targetStudents = _uiState.value.filteredStudents
        if (targetStudents.isEmpty()) {
            Toast.makeText(context, "لا يوجد طلاب للطباعة", Toast.LENGTH_SHORT).show()
            return
        }
        BarcodePdfExporter.printBarcodes(context, targetStudents)
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null, isOfflineError = false) }
    }
}
