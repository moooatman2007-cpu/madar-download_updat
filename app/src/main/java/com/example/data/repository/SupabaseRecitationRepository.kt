package com.example.data.repository

import android.util.Log
import com.example.core.model.BatchSyncStatus
import com.example.core.model.Recitation
import com.example.core.model.RecitationSummary
import com.example.core.model.SupabaseRecitationDto
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

interface RecitationRemoteDataSource {
    suspend fun fetchRecitationsForStudent(teacherId: String, studentId: String): List<Recitation>
    suspend fun fetchRecitationsForStudentByMonth(
        teacherId: String,
        studentId: String,
        startDate: String,
        endDate: String
    ): List<Recitation>
    suspend fun fetchRecitationsCountThisMonth(teacherId: String, startDate: String, endDate: String): Int
    suspend fun fetchAllRecitationsForTeacher(teacherId: String): List<Recitation>
    suspend fun insertRecitation(dto: SupabaseRecitationDto): Recitation
    suspend fun updateRecitation(teacherId: String, dto: SupabaseRecitationDto): Recitation
    suspend fun deleteRecitation(teacherId: String, recitationId: String)
    suspend fun fetchRecitationById(teacherId: String, recitationId: String): Recitation?
}

class SupabaseRecitationRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : RecitationRemoteDataSource {

    override suspend fun fetchRecitationsForStudent(teacherId: String, studentId: String): List<Recitation> {
        return client.postgrest["recitations"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseRecitationDto>()
            .map { it.toRecitation(teacherId = teacherId) }
    }

    override suspend fun fetchRecitationsForStudentByMonth(
        teacherId: String,
        studentId: String,
        startDate: String,
        endDate: String
    ): List<Recitation> {
        return client.postgrest["recitations"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                    gte("date", startDate)
                    lte("date", endDate)
                }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseRecitationDto>()
            .map { it.toRecitation(teacherId = teacherId) }
    }

    override suspend fun fetchRecitationsCountThisMonth(
        teacherId: String,
        startDate: String,
        endDate: String
    ): Int {
        val result = client.postgrest["recitations"].select {
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

    override suspend fun fetchAllRecitationsForTeacher(teacherId: String): List<Recitation> {
        return client.postgrest["recitations"]
            .select {
                filter { eq("teacher_id", teacherId) }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseRecitationDto>()
            .map { it.toRecitation(teacherId = teacherId) }
    }

    override suspend fun insertRecitation(dto: SupabaseRecitationDto): Recitation {
        val inserted = client.postgrest["recitations"]
            .insert(dto) { select() }
            .decodeSingle<SupabaseRecitationDto>()
        return inserted.toRecitation(teacherId = dto.teacherId ?: "")
    }

    override suspend fun updateRecitation(teacherId: String, dto: SupabaseRecitationDto): Recitation {
        val updated = client.postgrest["recitations"]
            .update(dto) {
                filter {
                    eq("id", dto.id ?: "")
                    eq("teacher_id", teacherId)
                }
                select()
            }
            .decodeSingle<SupabaseRecitationDto>()
        return updated.toRecitation(teacherId = teacherId)
    }

    override suspend fun deleteRecitation(teacherId: String, recitationId: String) {
        client.postgrest["recitations"].delete {
            filter {
                eq("id", recitationId)
                eq("teacher_id", teacherId)
            }
        }
    }

    override suspend fun fetchRecitationById(teacherId: String, recitationId: String): Recitation? {
        return client.postgrest["recitations"]
            .select {
                filter {
                    eq("id", recitationId)
                    eq("teacher_id", teacherId)
                }
            }
            .decodeSingleOrNull<SupabaseRecitationDto>()
            ?.toRecitation(teacherId = teacherId)
    }
}

class SupabaseRecitationRepository(
    private val remoteDataSource: RecitationRemoteDataSource = SupabaseRecitationRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RecitationRepository {

    private val _sessionRecitations = MutableStateFlow<Pair<String?, List<Recitation>>>(null to emptyList())

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    fun clearCache() {
        _sessionRecitations.value = null to emptyList()
    }

    private fun replaceScopedRecitationsInMemory(
        teacherId: String,
        fetched: List<Recitation>,
        predicateToReplace: (Recitation) -> Boolean
    ) {
        val (owner, current) = _sessionRecitations.value
        val base = if (owner == teacherId) current.filterNot(predicateToReplace) else emptyList()
        val combined = (fetched + base).distinctBy { it.recitationId }.sortedByDescending { it.date }
        _sessionRecitations.value = teacherId to combined
    }

    private fun upsertRecitationInMemory(teacherId: String, recitation: Recitation) {
        val (owner, current) = _sessionRecitations.value
        val base = if (owner == teacherId) current else emptyList()
        _sessionRecitations.value = teacherId to (listOf(recitation) + base.filterNot { it.recitationId == recitation.recitationId })
            .sortedByDescending { it.date }
    }

    override fun getRecitationsForStudent(studentId: String): Flow<List<Recitation>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            try {
                val list = remoteDataSource.fetchRecitationsForStudent(teacherId, studentId)
                if (!isStillAuthorized(teacherId)) {
                    clearCache()
                    return@launch
                }
                replaceScopedRecitationsInMemory(teacherId, list) { it.studentId == studentId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SupabaseRecitationRepo", "getRecitationsForStudent failed: ${e.message}", e)
            }
        }

        _sessionRecitations
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.studentId == studentId }.sortedByDescending { it.date }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override fun getRecitationsForStudentByMonth(
        studentId: String,
        year: Int,
        month: Int
    ): Flow<List<Recitation>> = channelFlow {
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
            fetchRecitationsForStudentByMonthResult(studentId, year, month)
        }

        _sessionRecitations
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.studentId == studentId && it.date in startDate..endDate }
                    .sortedByDescending { it.date }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun fetchRecitationsForStudentByMonthResult(
        studentId: String,
        year: Int,
        month: Int
    ): Result<List<Recitation>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        val dateRange = DateUtils.getMonthDateRange(year, month)
        val startDate = dateRange.startDate
        val endDate = dateRange.endDate

        try {
            val list = remoteDataSource.fetchRecitationsForStudentByMonth(teacherId, studentId, startDate, endDate)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب التسميعات."))
            }
            replaceScopedRecitationsInMemory(teacherId, list) {
                it.studentId == studentId && it.date in startDate..endDate
            }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseRecitationRepo", "fetchRecitationsForStudentByMonthResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getRecitationSummaryForStudent(
        studentId: String,
        year: Int,
        month: Int
    ): RecitationSummary {
        val list = fetchRecitationsForStudentByMonthResult(studentId, year, month).getOrNull() ?: emptyList()
        if (list.isEmpty()) return RecitationSummary()

        val totalCount = list.size
        val avgScore = list.map { it.score }.average()
        val avgMaxScore = list.map { it.maxScore }.average()
        val avgPct = list.map { if (it.maxScore > 0) (it.score / it.maxScore) * 100.0 else 0.0 }
            .average().toFloat()

        return RecitationSummary(
            totalCount = totalCount,
            averageScore = avgScore,
            averageMaxScore = avgMaxScore,
            averagePercentage = avgPct
        )
    }

    override suspend fun fetchRecitationsCountThisMonthResult(year: Int, month: Int): Result<Int> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val dateRange = DateUtils.getMonthDateRange(year, month)
            val count = remoteDataSource.fetchRecitationsCountThisMonth(teacherId, dateRange.startDate, dateRange.endDate)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة."))
            }
            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseRecitationRepo", "fetchRecitationsCountThisMonthResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getRecitationsCountThisMonth(year: Int, month: Int): Int {
        return fetchRecitationsCountThisMonthResult(year, month).getOrDefault(0)
    }

    override suspend fun refreshAllRecitationsForTeacher(): Result<List<Recitation>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val list = remoteDataSource.fetchAllRecitationsForTeacher(teacherId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب التسميعات."))
            }
            _sessionRecitations.value = teacherId to list.sortedByDescending { it.date }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseRecitationRepo", "refreshAllRecitationsForTeacher failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun getAllRecitationsForTeacher(): Flow<List<Recitation>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            refreshAllRecitationsForTeacher()
        }

        _sessionRecitations
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList() else list
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun addRecitation(
        studentId: String,
        date: String,
        title: String,
        content: String,
        score: Double,
        maxScore: Double,
        note: String?
    ): Result<SyncResult<Recitation>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        if (title.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("عنوان التسميع مطلوب"))
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

        val dto = SupabaseRecitationDto(
            id = UUID.randomUUID().toString(),
            teacherId = teacherId,
            studentId = studentId,
            date = date.trim(),
            title = title.trim(),
            content = content.trim(),
            score = score,
            maxScore = maxScore,
            note = note?.trim()?.ifBlank { null }
        )

        try {
            val recitation = remoteDataSource.insertRecitation(dto)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ التسميع."))
            }
            upsertRecitationInMemory(teacherId, recitation)
            Result.success(SyncResult(recitation, BatchSyncStatus.SAVED_TO_CLOUD))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseRecitationRepo", "addRecitation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateRecitation(recitation: Recitation): Result<Recitation> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        if (recitation.teacherId.isNotBlank() && recitation.teacherId != teacherId) {
            return@withContext Result.failure(SecurityException("غير مصرح بتعديل تسميع تابع لحساب آخر."))
        }
        if (recitation.title.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("عنوان التسميع مطلوب"))
        }
        if (recitation.maxScore <= 0.0) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة الكاملة يجب أن تكون أكبر من صفر"))
        }
        if (recitation.score < 0.0) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة لا يمكن أن تكون سالبة"))
        }
        if (recitation.score > recitation.maxScore) {
            return@withContext Result.failure(IllegalArgumentException("الدرجة لا يمكن أن تكون أكبر من الدرجة الكاملة"))
        }

        val dto = SupabaseRecitationDto(
            id = recitation.recitationId,
            teacherId = teacherId,
            studentId = recitation.studentId,
            date = recitation.date.trim(),
            title = recitation.title.trim(),
            content = recitation.content.trim(),
            score = recitation.score,
            maxScore = recitation.maxScore,
            note = recitation.note?.trim()?.ifBlank { null }
        )

        try {
            val updated = remoteDataSource.updateRecitation(teacherId, dto)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تعديل التسميع."))
            }
            upsertRecitationInMemory(teacherId, updated)
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseRecitationRepo", "updateRecitation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteRecitation(recitationId: String): Result<Unit> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            remoteDataSource.deleteRecitation(teacherId, recitationId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حذف التسميع."))
            }
            val (owner, current) = _sessionRecitations.value
            if (owner == teacherId) {
                _sessionRecitations.value = teacherId to current.filterNot { it.recitationId == recitationId }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseRecitationRepo", "deleteRecitation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getRecitationById(recitationId: String): Recitation? = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId() ?: return@withContext null
        try {
            val recitation = remoteDataSource.fetchRecitationById(teacherId, recitationId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext null
            }
            if (recitation != null) {
                upsertRecitationInMemory(teacherId, recitation)
            }
            recitation
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseRecitationRepo", "getRecitationById failed: ${e.message}", e)
            null
        }
    }
}
