package com.example.data.repository

import android.util.Log
import com.example.core.model.BatchSyncStatus
import com.example.core.model.SoftDeleteStudentRequest
import com.example.core.model.Student
import com.example.core.model.SupabaseStudentInsertDto
import com.example.core.model.SyncResult
import com.example.core.model.TeacherStats
import com.example.core.model.UpdateStudentOnlineDto
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.util.UUID

interface StudentRemoteDataSource {
    suspend fun fetchStudents(teacherId: String): List<Student>
    suspend fun fetchStudentById(teacherId: String, studentId: String): Student?
    suspend fun fetchStudentByCode(teacherId: String, studentCode: String): Student?
    suspend fun insertStudent(dto: SupabaseStudentInsertDto): Student
    suspend fun updateStudent(teacherId: String, studentId: String, request: UpdateStudentOnlineDto): Student
    suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String)
}

class SupabaseStudentRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : StudentRemoteDataSource {
    override suspend fun fetchStudents(teacherId: String): List<Student> {
        return client.postgrest["students"]
            .select {
                filter {
                    eq("teacher_id", teacherId)
                    exact("deleted_at", null)
                }
                order("created_at", order = Order.DESCENDING)
            }
            .decodeList<Student>()
    }

    override suspend fun fetchStudentById(teacherId: String, studentId: String): Student? {
        return client.postgrest["students"]
            .select {
                filter {
                    eq("id", studentId)
                    eq("teacher_id", teacherId)
                    exact("deleted_at", null)
                }
            }
            .decodeSingleOrNull<Student>()
    }

    override suspend fun fetchStudentByCode(teacherId: String, studentCode: String): Student? {
        return client.postgrest["students"]
            .select {
                filter {
                    eq("teacher_id", teacherId)
                    eq("student_code", studentCode)
                    exact("deleted_at", null)
                }
            }
            .decodeSingleOrNull<Student>()
    }

    override suspend fun insertStudent(dto: SupabaseStudentInsertDto): Student {
        return client.postgrest["students"]
            .insert(dto) {
                select()
            }
            .decodeSingle<Student>()
    }

    override suspend fun updateStudent(
        teacherId: String,
        studentId: String,
        request: UpdateStudentOnlineDto
    ): Student {
        return client.postgrest["students"]
            .update(request) {
                filter {
                    eq("id", studentId)
                    eq("teacher_id", teacherId)
                    exact("deleted_at", null)
                }
                select()
            }
            .decodeSingle<Student>()
    }

    override suspend fun softDeleteStudent(teacherId: String, studentId: String, deletedAtIso: String) {
        client.postgrest["students"]
            .update(SoftDeleteStudentRequest(deletedAt = deletedAtIso)) {
                filter {
                    eq("id", studentId)
                    eq("teacher_id", teacherId)
                }
            }
    }
}

class SupabaseStudentRepository(
    private val remoteDataSource: StudentRemoteDataSource = SupabaseStudentRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : StudentRepository {

    // In-memory session-only state scoped strictly to the active teacherId (never persisted to Room/SQLite).
    private val _sessionStudents = MutableStateFlow<Pair<String?, List<Student>>>(null to emptyList())

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    fun clearCache() {
        _sessionStudents.value = null to emptyList()
    }

    internal fun updateStudentInMemory(student: Student) {
        val teacherId = resolveActiveTeacherId() ?: return
        if (!student.teacherId.isNullOrBlank() && student.teacherId != teacherId) return

        val (currentOwner, currentList) = _sessionStudents.value
        if (currentOwner != teacherId) {
            _sessionStudents.value = teacherId to listOf(student)
            return
        }
        val mutable = currentList.toMutableList()
        val idx = mutable.indexOfFirst { it.studentId == student.studentId }
        if (idx >= 0) {
            mutable[idx] = student
        } else {
            mutable.add(0, student)
        }
        _sessionStudents.value = teacherId to mutable
    }

    override suspend fun refreshStudents(): Result<List<Student>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))
        }

        try {
            val fetched = remoteDataSource.fetchStudents(teacherId)

            // Reject late response if session changed or ended during flight
            if (!sessionActiveChecker(teacherId) || teacherIdProvider() != teacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب بيانات الطلاب."))
            }

            val activeStudents = fetched.filter {
                it.deletedAt == null && (it.teacherId.isNullOrBlank() || it.teacherId == teacherId)
            }
            _sessionStudents.value = teacherId to activeStudents
            Result.success(activeStudents)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_sessionStudents.value.first != teacherId) {
                clearCache()
            }
            Log.e("SupabaseStudentRepo", "Online fetchStudents failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun fetchStudents(forceRefresh: Boolean = true): List<Student> {
        return refreshStudents().getOrDefault(emptyList())
    }

    override fun getStudents(): Flow<List<Student>> {
        return _sessionStudents.map { (ownerId, list) ->
            val activeTeacherId = resolveActiveTeacherId()
            if (activeTeacherId == null || ownerId != activeTeacherId) {
                emptyList()
            } else {
                list
            }
        }
    }

    override fun getStudentsByGrade(gradeId: String): Flow<List<Student>> {
        return getStudents().map { list ->
            list.filter { it.gradeId == gradeId }
        }
    }

    override suspend fun fetchStudentById(studentId: String): Result<Student?> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val student = remoteDataSource.fetchStudentById(teacherId, studentId)
            if (!sessionActiveChecker(teacherId) || teacherIdProvider() != teacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب بيانات الطالب."))
            }
            if (student != null && student.deletedAt == null && (student.teacherId.isNullOrBlank() || student.teacherId == teacherId)) {
                updateStudentInMemory(student)
                Result.success(student)
            } else {
                Result.success(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseStudentRepo", "Online fetchStudentById failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getStudentById(studentId: String): Student? {
        return fetchStudentById(studentId).getOrNull()
    }

    override suspend fun getStudentByCode(studentCode: String): Student? = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId() ?: return@withContext null
        val normalized = studentCode.trim()
        if (normalized.isEmpty()) return@withContext null

        try {
            val student = remoteDataSource.fetchStudentByCode(teacherId, normalized)
            if (!sessionActiveChecker(teacherId) || teacherIdProvider() != teacherId) {
                clearCache()
                return@withContext null
            }
            if (student != null && student.deletedAt == null && (student.teacherId.isNullOrBlank() || student.teacherId == teacherId)) {
                updateStudentInMemory(student)
                student
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseStudentRepo", "Online getStudentByCode failed: ${e.message}", e)
            null
        }
    }

    override suspend fun addStudent(
        fullName: String,
        gradeId: String,
        parentPhone: String,
        hasWhatsApp: Boolean,
        alternativePhone: String?
    ): Result<SyncResult<Student>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        val generatedId = UUID.randomUUID().toString()
        val dto = SupabaseStudentInsertDto(
            id = generatedId,
            teacherId = teacherId,
            gradeId = gradeId,
            fullName = fullName.trim(),
            parentPhone = parentPhone.trim(),
            hasWhatsApp = hasWhatsApp,
            alternativePhone = alternativePhone?.trim()?.ifBlank { null }
        )

        try {
            val insertedStudent = remoteDataSource.insertStudent(dto)

            if (!sessionActiveChecker(teacherId) || teacherIdProvider() != teacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ الطالب."))
            }

            if (insertedStudent.studentCode.isBlank()) {
                return@withContext Result.failure(IllegalStateException("لم يُرجع السيرفر كود الطالب بعد الإضافة."))
            }

            // Update in-memory state ONLY after server confirmation
            val (currentOwner, currentList) = _sessionStudents.value
            val baseList = if (currentOwner == teacherId) currentList else emptyList()
            _sessionStudents.value = teacherId to (listOf(insertedStudent) + baseList.filterNot { it.studentId == insertedStudent.studentId })

            Result.success(SyncResult(insertedStudent, BatchSyncStatus.SAVED_TO_CLOUD))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseStudentRepo", "Online addStudent failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateStudent(student: Student): Result<Student> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        if (!student.teacherId.isNullOrBlank() && student.teacherId != teacherId) {
            return@withContext Result.failure(SecurityException("غير مصرح بتعديل بيانات طالب تابع لحساب آخر."))
        }

        val request = UpdateStudentOnlineDto(
            gradeId = student.gradeId,
            fullName = student.fullName.trim(),
            parentPhone = student.parentPhone.trim(),
            hasWhatsApp = student.hasWhatsApp,
            alternativePhone = student.alternativePhone?.trim()?.ifBlank { null },
            groupId = student.groupId
        )

        try {
            val updatedFromServer = remoteDataSource.updateStudent(teacherId, student.studentId, request)

            if (!sessionActiveChecker(teacherId) || teacherIdProvider() != teacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تحديث بيانات الطالب."))
            }

            val reconciled = if (updatedFromServer.studentCode.isBlank() && student.studentCode.isNotBlank()) {
                updatedFromServer.copy(studentCode = student.studentCode)
            } else {
                updatedFromServer
            }

            // Update in-memory state ONLY after server confirmation
            updateStudentInMemory(reconciled)

            Result.success(reconciled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseStudentRepo", "Online updateStudent failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteStudent(studentId: String): Result<SyncResult<Unit>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val nowIso = OffsetDateTime.now().toString()
            remoteDataSource.softDeleteStudent(teacherId, studentId, nowIso)

            if (!sessionActiveChecker(teacherId) || teacherIdProvider() != teacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حذف الطالب."))
            }

            // Remove from in-memory state ONLY after server confirmation
            val (currentOwner, currentList) = _sessionStudents.value
            if (currentOwner == teacherId) {
                _sessionStudents.value = teacherId to currentList.filterNot { it.studentId == studentId }
            }

            Result.success(SyncResult(Unit, BatchSyncStatus.SAVED_TO_CLOUD))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseStudentRepo", "Online deleteStudent failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun searchStudents(query: String, gradeId: String?): Flow<List<Student>> {
        return getStudents().map { list ->
            list.filter { student ->
                val matchesGrade = gradeId == null || student.gradeId == gradeId
                val q = query.trim().lowercase()
                val matchesQuery = q.isEmpty() ||
                    student.fullName.lowercase().contains(q) ||
                    student.studentCode.lowercase().contains(q) ||
                    student.parentPhone.contains(q)
                matchesGrade && matchesQuery
            }
        }
    }

    override fun getStats(): Flow<TeacherStats> {
        return getStudents().map { list ->
            val activeList = list.filter { it.deletedAt == null }
            val gradeCounts = activeList.groupBy { it.gradeId }.mapValues { it.value.size }
            TeacherStats(
                totalStudents = activeList.size,
                gradeCounts = gradeCounts,
                whatsappEnabledCount = activeList.count { it.hasWhatsApp },
                hasAlternativePhoneCount = activeList.count { !it.alternativePhone.isNullOrBlank() }
            )
        }
    }
}
