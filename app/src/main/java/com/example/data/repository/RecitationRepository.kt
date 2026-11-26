package com.example.data.repository

import com.example.core.model.Recitation
import com.example.core.model.RecitationSummary
import com.example.core.model.SyncResult
import kotlinx.coroutines.flow.Flow

interface RecitationRepository {
    fun getRecitationsForStudent(studentId: String): Flow<List<Recitation>>
    fun getRecitationsForStudentByMonth(studentId: String, year: Int, month: Int): Flow<List<Recitation>>
    suspend fun getRecitationSummaryForStudent(studentId: String, year: Int, month: Int): RecitationSummary
    suspend fun fetchRecitationsForStudentByMonthResult(studentId: String, year: Int, month: Int): Result<List<Recitation>> =
        runCatching { emptyList() }
    suspend fun getRecitationsCountThisMonth(year: Int, month: Int): Int
    suspend fun fetchRecitationsCountThisMonthResult(year: Int, month: Int): Result<Int> =
        runCatching { getRecitationsCountThisMonth(year, month) }
    fun getAllRecitationsForTeacher(): Flow<List<Recitation>>
    suspend fun refreshAllRecitationsForTeacher(): Result<List<Recitation>> =
        runCatching { emptyList() }
    suspend fun addRecitation(
        studentId: String,
        date: String,
        title: String,
        content: String,
        score: Double,
        maxScore: Double,
        note: String? = null
    ): Result<SyncResult<Recitation>>
    suspend fun updateRecitation(recitation: Recitation): Result<Recitation>
    suspend fun deleteRecitation(recitationId: String): Result<Unit>
    suspend fun getRecitationById(recitationId: String): Recitation?
}
