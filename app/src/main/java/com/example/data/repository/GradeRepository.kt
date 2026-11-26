package com.example.data.repository

import com.example.core.model.Grade
import kotlinx.coroutines.flow.Flow

interface GradeRepository {
    fun getGrades(): Flow<List<Grade>>
    suspend fun getGradeById(id: String): Grade?
    suspend fun addGrade(grade: Grade): Boolean
    suspend fun updateGrade(grade: Grade): Boolean
    suspend fun deleteGrade(id: String): Boolean
    suspend fun addGradeResult(grade: Grade): Result<Grade> =
        if (addGrade(grade)) Result.success(grade) else Result.failure(Exception("تعذر إضافة الصف الدراسي."))
    suspend fun updateGradeResult(grade: Grade): Result<Grade> =
        if (updateGrade(grade)) Result.success(grade) else Result.failure(Exception("تعذر تحديث الصف الدراسي."))
    suspend fun deleteGradeResult(id: String): Result<Unit> =
        if (deleteGrade(id)) Result.success(Unit) else Result.failure(Exception("تعذر حذف الصف الدراسي."))
    suspend fun ensureGradesForStage(stage: String?): List<Grade> = emptyList()
    suspend fun refreshGrades(stage: String? = null): List<Grade> = emptyList()
    suspend fun refreshGradesResult(stage: String? = null): Result<List<Grade>> =
        runCatching { refreshGrades(stage) }
}
