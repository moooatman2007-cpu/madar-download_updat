package com.example.data.repository

import android.util.Log
import com.example.core.model.BatchSyncStatus
import com.example.core.model.Homework
import com.example.core.model.HomeworkStatus
import com.example.core.model.SupabaseHomeworkDto
import com.example.core.model.SyncResult
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.util.DateUtils
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
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

interface HomeworkRemoteDataSource {
    suspend fun fetchHomeworkForStudent(teacherId: String, studentId: String): List<Homework>
    suspend fun fetchHomeworkForStudentByMonth(
        teacherId: String,
        studentId: String,
        startDate: String,
        endDate: String
    ): List<Homework>
    suspend fun fetchHomeworkForTeacher(teacherId: String): List<Homework>
    suspend fun insertHomework(dto: SupabaseHomeworkDto): Homework
    suspend fun updateHomework(teacherId: String, dto: SupabaseHomeworkDto): Homework
    suspend fun deleteHomework(teacherId: String, homeworkId: String)
    suspend fun fetchHomeworkById(teacherId: String, homeworkId: String): Homework?
}

class SupabaseHomeworkRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : HomeworkRemoteDataSource {

    override suspend fun fetchHomeworkForStudent(teacherId: String, studentId: String): List<Homework> {
        return client.postgrest["homework"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                }
                order("homework_date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseHomeworkDto>()
            .map { it.toHomework(teacherId) }
    }

    override suspend fun fetchHomeworkForStudentByMonth(
        teacherId: String,
        studentId: String,
        startDate: String,
        endDate: String
    ): List<Homework> {
        return client.postgrest["homework"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                    gte("homework_date", startDate)
                    lte("homework_date", endDate)
                }
                order("homework_date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseHomeworkDto>()
            .map { it.toHomework(teacherId) }
    }

    override suspend fun fetchHomeworkForTeacher(teacherId: String): List<Homework> {
        return client.postgrest["homework"]
            .select {
                filter { eq("teacher_id", teacherId) }
                order("homework_date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseHomeworkDto>()
            .map { it.toHomework(teacherId) }
    }

    override suspend fun insertHomework(dto: SupabaseHomeworkDto): Homework {
        val inserted = client.postgrest["homework"]
            .insert(dto) { select() }
            .decodeSingle<SupabaseHomeworkDto>()
        return inserted.toHomework(dto.teacherId ?: "")
    }

    override suspend fun updateHomework(teacherId: String, dto: SupabaseHomeworkDto): Homework {
        val updated = client.postgrest["homework"]
            .update(dto) {
                filter {
                    eq("id", dto.id ?: "")
                    eq("teacher_id", teacherId)
                }
                select()
            }
            .decodeSingle<SupabaseHomeworkDto>()
        return updated.toHomework(teacherId)
    }

    override suspend fun deleteHomework(teacherId: String, homeworkId: String) {
        client.postgrest["homework"].delete {
            filter {
                eq("id", homeworkId)
                eq("teacher_id", teacherId)
            }
        }
    }

    override suspend fun fetchHomeworkById(teacherId: String, homeworkId: String): Homework? {
        return client.postgrest["homework"]
            .select {
                filter {
                    eq("id", homeworkId)
                    eq("teacher_id", teacherId)
                }
            }
            .decodeSingleOrNull<SupabaseHomeworkDto>()
            ?.toHomework(teacherId)
    }
}

class SupabaseHomeworkRepository(
    private val remoteDataSource: HomeworkRemoteDataSource = SupabaseHomeworkRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : HomeworkRepository {

    private val _sessionHomework = MutableStateFlow<Pair<String?, List<Homework>>>(null to emptyList())

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    fun clearCache() {
        _sessionHomework.value = null to emptyList()
    }

    private fun replaceScopedHomeworkInMemory(
        teacherId: String,
        fetched: List<Homework>,
        predicateToReplace: (Homework) -> Boolean
    ) {
        val (owner, current) = _sessionHomework.value
        val base = if (owner == teacherId) current.filterNot(predicateToReplace) else emptyList()
        val combined = (fetched + base).distinctBy { it.homeworkId }.sortedByDescending { it.date }
        _sessionHomework.value = teacherId to combined
    }

    private fun upsertHomeworkInMemory(teacherId: String, homework: Homework) {
        val (owner, current) = _sessionHomework.value
        val base = if (owner == teacherId) current else emptyList()
        _sessionHomework.value = teacherId to (listOf(homework) + base.filterNot { it.homeworkId == homework.homeworkId })
            .sortedByDescending { it.date }
    }

    override fun getHomeworkForStudent(studentId: String): Flow<List<Homework>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            try {
                val list = remoteDataSource.fetchHomeworkForStudent(teacherId, studentId)
                if (!isStillAuthorized(teacherId)) {
                    clearCache()
                    return@launch
                }
                replaceScopedHomeworkInMemory(teacherId, list) { it.studentId == studentId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SupabaseHomeworkRepo", "getHomeworkForStudent failed: ${e.message}", e)
            }
        }

        _sessionHomework
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.studentId == studentId }.sortedByDescending { it.date }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override fun getHomeworkForStudentByMonth(
        studentId: String,
        year: Int,
        month: Int
    ): Flow<List<Homework>> = channelFlow {
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
            fetchHomeworkForStudentByMonthResult(studentId, year, month)
        }

        _sessionHomework
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.studentId == studentId && it.date in startDate..endDate }
                    .sortedByDescending { it.date }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun fetchHomeworkForStudentByMonthResult(
        studentId: String,
        year: Int,
        month: Int
    ): Result<List<Homework>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        val dateRange = DateUtils.getMonthDateRange(year, month)
        val startDate = dateRange.startDate
        val endDate = dateRange.endDate

        try {
            val list = remoteDataSource.fetchHomeworkForStudentByMonth(teacherId, studentId, startDate, endDate)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب الواجبات."))
            }
            replaceScopedHomeworkInMemory(teacherId, list) {
                it.studentId == studentId && it.date in startDate..endDate
            }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseHomeworkRepo", "fetchHomeworkForStudentByMonthResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun refreshHomeworkForTeacher(): Result<List<Homework>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val list = remoteDataSource.fetchHomeworkForTeacher(teacherId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب الواجبات."))
            }
            _sessionHomework.value = teacherId to list.sortedByDescending { it.date }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseHomeworkRepo", "refreshHomeworkForTeacher failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun getHomeworkForTeacher(): Flow<List<Homework>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            refreshHomeworkForTeacher()
        }

        _sessionHomework
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList() else list
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun addHomework(
        studentId: String,
        date: String,
        title: String,
        status: HomeworkStatus,
        note: String?
    ): Result<SyncResult<Homework>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        if (title.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("عنوان الواجب مطلوب"))
        }

        val dto = SupabaseHomeworkDto(
            id = UUID.randomUUID().toString(),
            teacherId = teacherId,
            studentId = studentId,
            homeworkDate = date.trim(),
            title = title.trim(),
            status = status.toSupabaseStatus(),
            description = note?.trim() ?: ""
        )

        try {
            val homework = remoteDataSource.insertHomework(dto)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ الواجب."))
            }
            upsertHomeworkInMemory(teacherId, homework)
            Result.success(SyncResult(homework, BatchSyncStatus.SAVED_TO_CLOUD))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseHomeworkRepo", "addHomework failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateHomework(homework: Homework): Result<Homework> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        if (homework.teacherId.isNotBlank() && homework.teacherId != teacherId) {
            return@withContext Result.failure(SecurityException("غير مصرح بتعديل واجب تابع لحساب آخر."))
        }
        if (homework.title.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("عنوان الواجب مطلوب"))
        }

        val dto = SupabaseHomeworkDto(
            id = homework.homeworkId,
            teacherId = teacherId,
            studentId = homework.studentId,
            homeworkDate = homework.date.trim(),
            title = homework.title.trim(),
            status = homework.status.toSupabaseStatus(),
            description = homework.note?.trim() ?: ""
        )

        try {
            val result = remoteDataSource.updateHomework(teacherId, dto)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تعديل الواجب."))
            }
            upsertHomeworkInMemory(teacherId, result)
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseHomeworkRepo", "updateHomework failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteHomework(homeworkId: String): Result<Unit> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        try {
            remoteDataSource.deleteHomework(teacherId, homeworkId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حذف الواجب."))
            }
            val (owner, current) = _sessionHomework.value
            if (owner == teacherId) {
                _sessionHomework.value = teacherId to current.filterNot { it.homeworkId == homeworkId }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseHomeworkRepo", "deleteHomework failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getHomeworkById(homeworkId: String): Homework? = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId() ?: return@withContext null
        try {
            val homework = remoteDataSource.fetchHomeworkById(teacherId, homeworkId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext null
            }
            if (homework != null) {
                upsertHomeworkInMemory(teacherId, homework)
            }
            homework
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseHomeworkRepo", "getHomeworkById failed: ${e.message}", e)
            null
        }
    }
}
