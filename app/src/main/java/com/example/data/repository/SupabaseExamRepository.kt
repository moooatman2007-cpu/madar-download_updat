package com.example.data.repository

import android.util.Log
import com.example.core.model.BatchSyncStatus
import com.example.core.model.Exam
import com.example.core.model.ExamSummary
import com.example.core.model.SupabaseExamDto
import com.example.core.model.SyncResult
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.util.DateUtils
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

interface ExamRemoteDataSource {
    suspend fun fetchExamsForStudent(teacherId: String, studentId: String): List<Exam>
    suspend fun fetchExamsForStudentByMonth(
        teacherId: String,
        studentId: String,
        startDate: String,
        endDate: String
    ): List<Exam>
    suspend fun fetchExamsCountThisMonth(teacherId: String, startDate: String, endDate: String): Int
    suspend fun fetchAllExamsForTeacher(teacherId: String): List<Exam>
    suspend fun insertExam(dto: SupabaseExamDto): Exam
    suspend fun updateExam(teacherId: String, dto: SupabaseExamDto): Exam
    suspend fun deleteExam(teacherId: String, examId: String)
    suspend fun fetchExamById(teacherId: String, examId: String): Exam?
}

class SupabaseExamRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : ExamRemoteDataSource {

    override suspend fun fetchExamsForStudent(teacherId: String, studentId: String): List<Exam> {
        return client.postgrest["exams"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseExamDto>()
            .map { it.toExam(teacherId = teacherId) }
    }

    override suspend fun fetchExamsForStudentByMonth(
        teacherId: String,
        studentId: String,
        startDate: String,
        endDate: String
    ): List<Exam> {
        return client.postgrest["exams"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                    gte("date", startDate)
                    lte("date", endDate)
                }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseExamDto>()
            .map { it.toExam(teacherId = teacherId) }
    }

    override suspend fun fetchExamsCountThisMonth(
        teacherId: String,
        startDate: String,
        endDate: String
    ): Int {
        val result = client.postgrest["exams"].select {
            head = true
            count(Count.EXACT)
            filter {
                eq("teacher_id", teacherId)
                gte("date", startDate)
                lte("date", endDate)
            }
        }
        return result.countOrNull()?.toInt() ?: 0
    }

    override suspend fun fetchAllExamsForTeacher(teacherId: String): List<Exam> {
        return client.postgrest["exams"]
            .select {
                filter { eq("teacher_id", teacherId) }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseExamDto>()
            .map { it.toExam(teacherId = teacherId) }
    }

    override suspend fun insertExam(dto: SupabaseExamDto): Exam {
        val inserted = client.postgrest["exams"]
            .insert(dto) { select() }
            .decodeSingle<SupabaseExamDto>()
        return inserted.toExam(teacherId = dto.teacherId ?: "")
    }

    override suspend fun updateExam(teacherId: String, dto: SupabaseExamDto): Exam {
        val updated = client.postgrest["exams"]
            .update(dto) {
                filter {
                    eq("id", dto.id ?: "")
                    eq("teacher_id", teacherId)
                }
                select()
            }
            .decodeSingle<SupabaseExamDto>()
        return updated.toExam(teacherId = teacherId)
    }

    override suspend fun deleteExam(teacherId: String, examId: String) {
        client.postgrest["exams"].delete {
            filter {
                eq("id", examId)
                eq("teacher_id", teacherId)
            }
        }
    }

    override suspend fun fetchExamById(teacherId: String, examId: String): Exam? {
        return client.postgrest["exams"]
            .select {
                filter {
                    eq("id", examId)
                    eq("teacher_id", teacherId)
                }
            }
            .decodeSingleOrNull<SupabaseExamDto>()
            ?.toExam(teacherId = teacherId)
    }
}

class SupabaseExamRepository(
    private val remoteDataSource: ExamRemoteDataSource = SupabaseExamRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ExamRepository {

    private val _sessionExams = MutableStateFlow<Pair<String?, List<Exam>>>(null to emptyList())

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    fun clearCache() {
        _sessionExams.value = null to emptyList()
    }

    private fun replaceScopedExamsInMemory(
        teacherId: String,
        fetched: List<Exam>,
        predicateToReplace: (Exam) -> Boolean
    ) {
        val (owner, current) = _sessionExams.value
        val base = if (owner == teacherId) current.filterNot(predicateToReplace) else emptyList()
        val combined = (fetched + base).distinctBy { it.examId }.sortedByDescending { it.date }
        _sessionExams.value = teacherId to combined
    }

    private fun upsertExamInMemory(teacherId: String, exam: Exam) {
        val (owner, current) = _sessionExams.value
        val base = if (owner == teacherId) current else emptyList()
        _sessionExams.value = teacherId to (listOf(exam) + base.filterNot { it.examId == exam.examId })
            .sortedByDescending { it.date }
    }

    override fun getExamsForStudent(studentId: String): Flow<List<Exam>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            try {
                val list = remoteDataSource.fetchExamsForStudent(teacherId, studentId)
                if (!isStillAuthorized(teacherId)) {
                    clearCache()
                    return@launch
                }
                replaceScopedExamsInMemory(teacherId, list) { it.studentId == studentId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SupabaseExamRepo", "getExamsForStudent failed: ${e.message}", e)
            }
        }

        _sessionExams
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.studentId == studentId }.sortedByDescending { it.date }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override fun getExamsForStudentByMonth(
        studentId: String,
        year: Int,
        month: Int
    ): Flow<List<Exam>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        val dateRange = DateUtils.getMonthDateRange(year, month)
        val startDate = dateRange.startDate
        val endDate = dateRange.endDate

        launch(ioDispatcher) {
            fetchExamsForStudentByMonthResult(studentId, year, month)
        }

        _sessionExams
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.studentId == studentId && it.date in startDate..endDate }
                    .sortedByDescending { it.date }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun fetchExamsForStudentByMonthResult(
        studentId: String,
        year: Int,
        month: Int
    ): Result<List<Exam>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        val dateRange = DateUtils.getMonthDateRange(year, month)
        val startDate = dateRange.startDate
        val endDate = dateRange.endDate

        try {
            val list = remoteDataSource.fetchExamsForStudentByMonth(teacherId, studentId, startDate, endDate)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب الامتحانات."))
            }
            replaceScopedExamsInMemory(teacherId, list) {
                it.studentId == studentId && it.date in startDate..endDate
            }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseExamRepo", "fetchExamsForStudentByMonthResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getExamSummaryForStudent(
        studentId: String,
        year: Int,
        month: Int
    ): ExamSummary {
        val list = fetchExamsForStudentByMonthResult(studentId, year, month).getOrNull() ?: emptyList()
        if (list.isEmpty()) return ExamSummary()

        val totalCount = list.size
        val avgScore = list.map { it.score }.average()
        val percentages = list.map { if (it.maxScore > 0) (it.score / it.maxScore) * 100.0 else 0.0 }
        val avgPct = percentages.average().toFloat()
        val highestPct = percentages.maxOrNull()?.toFloat() ?: 0f
        val lowestPct = percentages.minOrNull()?.toFloat() ?: 0f

        return ExamSummary(
            totalCount = totalCount,
            averageScore = avgScore,
            averagePercentage = avgPct,
            highestPercentage = highestPct,
            lowestPercentage = lowestPct
        )
    }

    override suspend fun fetchExamsCountThisMonthResult(year: Int, month: Int): Result<Int> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val dateRange = DateUtils.getMonthDateRange(year, month)
            val count = remoteDataSource.fetchExamsCountThisMonth(teacherId, dateRange.startDate, dateRange.endDate)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة."))
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseExamRepo", "fetchExamsCountThisMonthResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getExamsCountThisMonth(year: Int, month: Int): Int {
        return fetchExamsCountThisMonthResult(year, month).getOrDefault(0)
    }

    override suspend fun refreshAllExamsForTeacher(): Result<List<Exam>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val list = remoteDataSource.fetchAllExamsForTeacher(teacherId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب الامتحانات."))
            }
            _sessionExams.value = teacherId to list.sortedByDescending { it.date }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseExamRepo", "refreshAllExamsForTeacher failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun getAllExamsForTeacher(): Flow<List<Exam>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            refreshAllExamsForTeacher()
        }

        _sessionExams
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList() else list
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun addExam(
        studentId: String,
        date: String,
        examName: String,
        subject: String?,
        score: Double,
        maxScore: Double,
        note: String?
    ): Result<SyncResult<Exam>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        if (examName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("اسم الامتحان مطلوب"))
        }
        if (maxScore <= 0.0) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة الكاملة يجب أن تكون أكبر من صفر"))
        }
        if (score < 0.0) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة لا يمكن أن تكون سالبة"))
        }
        if (score > maxScore) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة لا يمكن أن تكون أكبر من الدرجة الكاملة"))
        }

        val dto = SupabaseExamDto(
            id = UUID.randomUUID().toString(),
            teacherId = teacherId,
            studentId = studentId,
            date = date.trim(),
            examName = examName.trim(),
            subject = subject?.trim()?.ifBlank { null },
            score = score,
            maxScore = maxScore,
            note = note?.trim()?.ifBlank { null }
        )

        try {
            val exam = remoteDataSource.insertExam(dto)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ الامتحان."))
            }
            upsertExamInMemory(teacherId, exam)
            Result.success(SyncResult(exam, BatchSyncStatus.SAVED_TO_CLOUD))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseExamRepo", "addExam failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateExam(exam: Exam): Result<Exam> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        if (exam.teacherId.isNotBlank() && exam.teacherId != teacherId) {
            return@withContext Result.failure(SecurityException("غير مصرح بتعديل امتحان تابع لحساب آخر."))
        }
        if (exam.examName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("اسم الامتحان مطلوب"))
        }
        if (exam.maxScore <= 0.0) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة الكاملة يجب أن تكون أكبر من صفر"))
        }
        if (exam.score < 0.0) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة لا يمكن أن تكون سالبة"))
        }
        if (exam.score > exam.maxScore) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة لا يمكن أن تكون أكبر من الدرجة الكاملة"))
        }

        val dto = SupabaseExamDto(
            id = exam.examId,
            teacherId = teacherId,
            studentId = exam.studentId,
            date = exam.date.trim(),
            examName = exam.examName.trim(),
            subject = exam.subject?.trim()?.ifBlank { null },
            score = exam.score,
            maxScore = exam.maxScore,
            note = exam.note?.trim()?.ifBlank { null }
        )

        try {
            val updated = remoteDataSource.updateExam(teacherId, dto)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تعديل الامتحان."))
            }
            upsertExamInMemory(teacherId, updated)
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseExamRepo", "updateExam failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteExam(examId: String): Result<Unit> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        try {
            remoteDataSource.deleteExam(teacherId, examId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حذف الامتحان."))
            }
            val (owner, current) = _sessionExams.value
            if (owner == teacherId) {
                _sessionExams.value = teacherId to current.filterNot { it.examId == examId }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseExamRepo", "deleteExam failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getExamById(examId: String): Exam? = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId() ?: return@withContext null
        try {
            val exam = remoteDataSource.fetchExamById(teacherId, examId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext null
            }
            if (exam != null) {
                upsertExamInMemory(teacherId, exam)
            }
            exam
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseExamRepo", "getExamById failed: ${e.message}", e)
            null
        }
    }
}
