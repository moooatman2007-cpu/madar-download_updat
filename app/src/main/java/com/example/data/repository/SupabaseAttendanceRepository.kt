package com.example.data.repository

import android.util.Log
import com.example.core.model.Attendance
import com.example.core.model.AttendanceStatus
import com.example.core.model.AttendanceSummary
import com.example.core.model.BatchAttendanceItemDto
import com.example.core.model.BatchAttendanceResult
import com.example.core.model.BatchSyncStatus
import com.example.core.model.SupabaseAttendanceDto
import com.example.core.model.SyncResult
import com.example.core.model.UpsertAttendanceRequest
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.util.DateUtils
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

interface AttendanceRemoteDataSource {
    suspend fun fetchAttendanceForStudent(teacherId: String, studentId: String): List<Attendance>
    suspend fun fetchAttendanceForStudentByMonth(
        teacherId: String,
        studentId: String,
        startDate: String,
        endDate: String
    ): List<Attendance>
    suspend fun fetchAllAttendanceForTeacher(teacherId: String): List<Attendance>
    suspend fun fetchTodayCountByStatus(teacherId: String, date: String, status: AttendanceStatus): Int
    suspend fun fetchStudentGroupId(teacherId: String, studentId: String): String?
    suspend fun upsertAttendance(request: UpsertAttendanceRequest): Attendance
    suspend fun upsertBatchAttendance(requests: List<UpsertAttendanceRequest>): List<Attendance>
    suspend fun deleteAttendanceByStudentAndDate(teacherId: String, studentId: String, date: String)
    suspend fun deleteAttendanceById(teacherId: String, attendanceId: String)
    suspend fun fetchAttendanceByDate(teacherId: String, studentId: String, date: String): Attendance?
    suspend fun fetchAttendanceForDate(teacherId: String, date: String): List<Attendance>
}

@Serializable
private data class StudentGroupIdRow(
    @kotlinx.serialization.SerialName("group_id") val groupId: String? = null
)

class SupabaseAttendanceRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : AttendanceRemoteDataSource {

    override suspend fun fetchAttendanceForStudent(teacherId: String, studentId: String): List<Attendance> {
        return client.postgrest["attendance"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseAttendanceDto>()
            .map { it.toAttendance(teacherId = teacherId) }
    }

    override suspend fun fetchAttendanceForStudentByMonth(
        teacherId: String,
        studentId: String,
        startDate: String,
        endDate: String
    ): List<Attendance> {
        return client.postgrest["attendance"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                    gte("date", startDate)
                    lte("date", endDate)
                }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseAttendanceDto>()
            .map { it.toAttendance(teacherId = teacherId) }
    }

    override suspend fun fetchAllAttendanceForTeacher(teacherId: String): List<Attendance> {
        return client.postgrest["attendance"]
            .select {
                filter {
                    eq("teacher_id", teacherId)
                }
                order("date", order = Order.DESCENDING)
            }
            .decodeList<SupabaseAttendanceDto>()
            .map { it.toAttendance(teacherId = teacherId) }
    }

    override suspend fun fetchTodayCountByStatus(
        teacherId: String,
        date: String,
        status: AttendanceStatus
    ): Int {
        val result = client.postgrest["attendance"].select {
            head = true
            count(Count.EXACT)
            filter {
                eq("teacher_id", teacherId)
                eq("date", date)
                eq("status", status.name)
            }
        }
        return result.countOrNull()?.toInt() ?: 0
    }

    override suspend fun fetchStudentGroupId(teacherId: String, studentId: String): String? {
        return client.postgrest["students"]
            .select(columns = Columns.list("group_id")) {
                filter {
                    eq("id", studentId)
                    eq("teacher_id", teacherId)
                    exact("deleted_at", null)
                }
                limit(1)
            }
            .decodeSingleOrNull<StudentGroupIdRow>()
            ?.groupId
    }

    override suspend fun upsertAttendance(request: UpsertAttendanceRequest): Attendance {
        return client.postgrest["attendance"].upsert(request) {
            onConflict = "teacher_id,student_id,date"
            select()
        }.decodeSingle<SupabaseAttendanceDto>()
            .toAttendance(teacherId = request.teacherId)
    }

    override suspend fun upsertBatchAttendance(requests: List<UpsertAttendanceRequest>): List<Attendance> {
        if (requests.isEmpty()) return emptyList()
        return client.postgrest["attendance"].upsert(requests) {
            onConflict = "teacher_id,student_id,date"
            select()
        }.decodeList<SupabaseAttendanceDto>()
            .map { it.toAttendance(teacherId = it.teacherId ?: requests.first().teacherId) }
    }

    override suspend fun deleteAttendanceByStudentAndDate(teacherId: String, studentId: String, date: String) {
        client.postgrest["attendance"].delete {
            filter {
                eq("student_id", studentId)
                eq("date", date)
                eq("teacher_id", teacherId)
            }
        }
    }

    override suspend fun deleteAttendanceById(teacherId: String, attendanceId: String) {
        client.postgrest["attendance"].delete {
            filter {
                eq("id", attendanceId)
                eq("teacher_id", teacherId)
            }
        }
    }

    override suspend fun fetchAttendanceByDate(
        teacherId: String,
        studentId: String,
        date: String
    ): Attendance? {
        return client.postgrest["attendance"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                    eq("date", date)
                }
            }
            .decodeSingleOrNull<SupabaseAttendanceDto>()
            ?.toAttendance(teacherId = teacherId)
    }

    override suspend fun fetchAttendanceForDate(teacherId: String, date: String): List<Attendance> {
        return client.postgrest["attendance"]
            .select {
                filter {
                    eq("teacher_id", teacherId)
                    eq("date", date)
                }
            }
            .decodeList<SupabaseAttendanceDto>()
            .map { it.toAttendance(teacherId = teacherId) }
    }
}

class SupabaseAttendanceRepository(
    private val remoteDataSource: AttendanceRemoteDataSource = SupabaseAttendanceRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AttendanceRepository {

    // Session-only in-memory state scoped strictly to active teacherId (never persisted to Room/SQLite).
    private val _sessionAttendance = MutableStateFlow<Pair<String?, List<Attendance>>>(null to emptyList())

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    fun clearCache() {
        _sessionAttendance.value = null to emptyList()
    }

    private fun replaceScopedAttendanceInMemory(
        teacherId: String,
        fetched: List<Attendance>,
        predicateToReplace: (Attendance) -> Boolean
    ) {
        val (owner, current) = _sessionAttendance.value
        val base = if (owner == teacherId) current.filterNot(predicateToReplace) else emptyList()
        val combined = (fetched + base).distinctBy { it.attendanceId.ifBlank { "${it.studentId}_${it.date}" } }
            .sortedByDescending { it.date }
        _sessionAttendance.value = teacherId to combined
    }

    private fun upsertAttendanceInMemory(teacherId: String, items: List<Attendance>) {
        if (items.isEmpty()) return
        val (owner, current) = _sessionAttendance.value
        val base = if (owner == teacherId) current else emptyList()
        val itemKeys = items.map { "${it.studentId}_${it.date}" }.toSet()
        val itemIds = items.mapNotNull { it.attendanceId.takeIf(String::isNotBlank) }.toSet()
        val remaining = base.filterNot {
            it.attendanceId in itemIds || "${it.studentId}_${it.date}" in itemKeys
        }
        _sessionAttendance.value = teacherId to (items + remaining).sortedByDescending { it.date }
    }

    override fun getAttendanceForStudent(studentId: String): Flow<List<Attendance>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            try {
                val list = remoteDataSource.fetchAttendanceForStudent(teacherId, studentId)
                if (!isStillAuthorized(teacherId)) {
                    clearCache()
                    return@launch
                }
                replaceScopedAttendanceInMemory(teacherId, list) { it.studentId == studentId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SupabaseAttendanceRepo", "getAttendanceForStudent failed: ${e.message}", e)
            }
        }

        _sessionAttendance
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.studentId == studentId }.sortedByDescending { it.date }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override fun getAttendanceForStudentByMonth(
        studentId: String,
        year: Int,
        month: Int
    ): Flow<List<Attendance>> = channelFlow {
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
            fetchAttendanceForStudentByMonthResult(studentId, year, month)
        }

        _sessionAttendance
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter {
                    it.studentId == studentId && it.date in startDate..endDate
                }.sortedByDescending { it.date }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun fetchAttendanceForStudentByMonthResult(
        studentId: String,
        year: Int,
        month: Int
    ): Result<List<Attendance>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        val dateRange = DateUtils.getMonthDateRange(year, month)
        val startDate = dateRange.startDate
        val endDate = dateRange.endDate

        try {
            val list = remoteDataSource.fetchAttendanceForStudentByMonth(
                teacherId = teacherId,
                studentId = studentId,
                startDate = startDate,
                endDate = endDate
            )
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب الحضور."))
            }
            replaceScopedAttendanceInMemory(teacherId, list) {
                it.studentId == studentId && it.date in startDate..endDate
            }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseAttendanceRepo", "fetchAttendanceForStudentByMonthResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getAttendanceSummaryForStudent(
        studentId: String,
        year: Int,
        month: Int
    ): AttendanceSummary {
        val list = fetchAttendanceForStudentByMonthResult(studentId, year, month).getOrNull() ?: emptyList()
        val total = list.size
        val present = list.count { it.status == AttendanceStatus.PRESENT }
        val absent = list.count { it.status == AttendanceStatus.ABSENT }
        val late = list.count { it.status == AttendanceStatus.LATE }
        val excused = list.count { it.status == AttendanceStatus.EXCUSED }
        val rate = if (total > 0) (present.toFloat() / total) * 100f else 0f

        return AttendanceSummary(
            totalDays = total,
            presentCount = present,
            absentCount = absent,
            lateCount = late,
            excusedCount = excused,
            attendanceRate = rate
        )
    }

    override suspend fun fetchTodayAttendanceCountResult(): Result<Pair<Int, Int>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date())
            val counts = coroutineScope {
                val presentDeferred = async {
                    remoteDataSource.fetchTodayCountByStatus(teacherId, todayStr, AttendanceStatus.PRESENT)
                }
                val absentDeferred = async {
                    remoteDataSource.fetchTodayCountByStatus(teacherId, todayStr, AttendanceStatus.ABSENT)
                }
                Pair(presentDeferred.await(), absentDeferred.await())
            }
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة."))
            }
            Result.success(counts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseAttendanceRepo", "fetchTodayAttendanceCountResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getTodayAttendanceCount(): Pair<Int, Int> {
        return fetchTodayAttendanceCountResult().getOrDefault(Pair(0, 0))
    }

    override suspend fun refreshAllAttendanceForTeacher(): Result<List<Attendance>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))
        try {
            val list = remoteDataSource.fetchAllAttendanceForTeacher(teacherId)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تحديث الحضور."))
            }
            _sessionAttendance.value = teacherId to list.sortedByDescending { it.date }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseAttendanceRepo", "refreshAllAttendanceForTeacher failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun getAllAttendanceForTeacher(): Flow<List<Attendance>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            refreshAllAttendanceForTeacher()
        }

        _sessionAttendance
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList() else list
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun recordOrUpdateAttendance(
        studentId: String,
        date: String,
        status: AttendanceStatus,
        note: String?,
        groupId: String?
    ): Result<SyncResult<Attendance>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val effectiveGroupId = groupId ?: try {
                remoteDataSource.fetchStudentGroupId(teacherId, studentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            val upsertDto = UpsertAttendanceRequest(
                teacherId = teacherId,
                studentId = studentId,
                groupId = effectiveGroupId,
                date = date,
                status = status.name,
                note = note?.ifBlank { null }
            )

            val savedAttendance = remoteDataSource.upsertAttendance(upsertDto)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ الحضور."))
            }

            // Update in-memory session state ONLY after server confirmation
            upsertAttendanceInMemory(teacherId, listOf(savedAttendance))
            Result.success(SyncResult(savedAttendance, BatchSyncStatus.SAVED_TO_CLOUD))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseAttendanceRepo", "recordOrUpdateAttendance failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun recordBatchAttendance(
        date: String,
        records: List<BatchAttendanceItemDto>,
        groupId: String?
    ): Result<BatchAttendanceResult> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        if (records.isEmpty()) {
            return@withContext Result.success(
                BatchAttendanceResult(total = 0, date = date, syncStatus = BatchSyncStatus.SAVED_TO_CLOUD)
            )
        }

        try {
            val distinctRecords = records.distinctBy { it.studentId }
            val upsertRequests = distinctRecords.map { item ->
                UpsertAttendanceRequest(
                    teacherId = teacherId,
                    studentId = item.studentId,
                    groupId = groupId,
                    date = date,
                    status = item.status,
                    note = item.note?.ifBlank { null }
                )
            }

            // Direct Postgrest Bulk Upsert on 'attendance' table preserving group_id
            val insertedList = remoteDataSource.upsertBatchAttendance(upsertRequests)

            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ الحضور."))
            }

            // Update in-memory session state ONLY after server confirmation
            upsertAttendanceInMemory(teacherId, insertedList)

            var presentCount = 0
            var absentCount = 0
            var lateCount = 0
            var excusedCount = 0
            for (record in distinctRecords) {
                when (AttendanceStatus.fromString(record.status)) {
                    AttendanceStatus.PRESENT -> presentCount++
                    AttendanceStatus.ABSENT -> absentCount++
                    AttendanceStatus.LATE -> lateCount++
                    AttendanceStatus.EXCUSED -> excusedCount++
                }
            }

            Result.success(
                BatchAttendanceResult(
                    total = distinctRecords.size,
                    presentCount = presentCount,
                    absentCount = absentCount,
                    lateCount = lateCount,
                    excusedCount = excusedCount,
                    date = date,
                    syncStatus = BatchSyncStatus.SAVED_TO_CLOUD
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseAttendanceRepo", "recordBatchAttendance failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAttendance(attendanceId: String): Result<Unit> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val parts = attendanceId.split("_")
            if (parts.size >= 2 && parts[1].matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                remoteDataSource.deleteAttendanceByStudentAndDate(teacherId, parts[0], parts[1])
            } else {
                remoteDataSource.deleteAttendanceById(teacherId, attendanceId)
            }

            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حذف الحضور."))
            }

            val (owner, current) = _sessionAttendance.value
            if (owner == teacherId) {
                _sessionAttendance.value = teacherId to current.filterNot {
                    it.attendanceId == attendanceId || "${it.studentId}_${it.date}" == attendanceId
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseAttendanceRepo", "deleteAttendance failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getAttendanceByDate(studentId: String, date: String): Attendance? = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId() ?: return@withContext null
        try {
            val attendance = remoteDataSource.fetchAttendanceByDate(teacherId, studentId, date)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext null
            }
            if (attendance != null) {
                upsertAttendanceInMemory(teacherId, listOf(attendance))
            }
            attendance
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseAttendanceRepo", "getAttendanceByDate failed: ${e.message}", e)
            null
        }
    }

    override suspend fun fetchAttendanceForDateAndGroupResult(
        date: String,
        groupId: String?
    ): Result<List<Attendance>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val list = remoteDataSource.fetchAttendanceForDate(teacherId, date)
                .filter { record ->
                    if (groupId == null) true else record.groupId == groupId || record.groupId == null
                }

            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب حضور اليوم."))
            }

            replaceScopedAttendanceInMemory(teacherId, list) { record ->
                record.date == date && (groupId == null || record.groupId == groupId || record.groupId == null)
            }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseAttendanceRepo", "fetchAttendanceForDateAndGroupResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getAttendanceForDateAndGroup(
        date: String,
        groupId: String?
    ): List<Attendance> {
        return fetchAttendanceForDateAndGroupResult(date, groupId).getOrDefault(emptyList())
    }
}
