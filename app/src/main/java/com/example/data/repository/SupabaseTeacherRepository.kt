package com.example.data.repository

import android.util.Log
import com.example.core.model.Teacher
import com.example.core.model.UpdateTeacherRequest
import com.example.core.model.UpsertTeacherRequest
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SupabaseTeacherRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client,
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TeacherRepository {

    private val _currentTeacher = MutableStateFlow<Teacher?>(null)

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    fun clearCache() {
        _currentTeacher.value = null
    }

    override fun getCurrentTeacher(): Flow<Teacher?> = _currentTeacher.map { teacher ->
        val activeId = resolveActiveTeacherId()
        if (activeId == null || teacher == null || teacher.id != activeId) null else teacher
    }

    override suspend fun fetchCurrentTeacher(): Teacher? = withContext(ioDispatcher) {
        val mockId = SupabaseClientProvider.mockTeacherId
        if (mockId != null) {
            if (!sessionActiveChecker(mockId)) {
                clearCache()
                return@withContext null
            }
            val cached = _currentTeacher.value
            if (cached != null && cached.id == mockId) return@withContext cached
            val mockTeacher = Teacher(id = mockId, email = "mock@teacher.com", fullName = "المدرس الحالي")
            _currentTeacher.value = mockTeacher
            return@withContext mockTeacher
        }

        val user = client.auth.currentUserOrNull()
        val teacherId = user?.id?.takeIf { it.isNotBlank() } ?: resolveActiveTeacherId()
        if (teacherId == null || !isStillAuthorized(teacherId)) {
            clearCache()
            return@withContext null
        }

        // Execute PostgREST query directly.
        // If this throws an exception (network error, timeout, 500), let it propagate
        // so callers can distinguish between query failure and a truly missing record.
        val result = client.postgrest["teachers"]
            .select {
                filter {
                    eq("id", teacherId)
                }
            }
            .decodeSingleOrNull<Teacher>()

        if (!isStillAuthorized(teacherId)) {
            clearCache()
            return@withContext null
        }

        val metaStage = user?.userMetadata?.get("educational_stage")?.toString()?.replace("\"", "")
        val finalTeacher = (result ?: Teacher(
            id = teacherId,
            email = user?.email ?: "",
            fullName = user?.userMetadata?.get("full_name")?.toString()?.replace("\"", "") ?: "",
            educationalStage = metaStage
        )).let { t ->
            if (t.educationalStage == null && !metaStage.isNullOrBlank()) {
                t.copy(educationalStage = metaStage)
            } else t
        }
        _currentTeacher.value = finalTeacher
        finalTeacher
    }

    override suspend fun updateTeacherProfile(
        fullName: String,
        phoneNumber: String?,
        subject: String?,
        centerName: String?,
        educationalStage: String?,
        avatarUrl: String?
    ): Result<Teacher> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("جلسة تسجيل الدخول غير صحيحة أو انتهت."))

        try {
            val user = client.auth.currentUserOrNull()
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("جلسة تسجيل الدخول غير صحيحة أو انتهت."))
            }

            val updateDto = UpdateTeacherRequest(
                fullName = fullName,
                phoneNumber = phoneNumber?.ifBlank { null },
                subject = subject?.ifBlank { null },
                centerName = centerName?.ifBlank { null },
                educationalStage = educationalStage?.ifBlank { null },
                avatarUrl = avatarUrl
            )

            try {
                client.postgrest["teachers"]
                    .update(updateDto) {
                        filter {
                            eq("id", teacherId)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("SupabaseTeacherRepo", "Update failed, falling back to upsert: ${e.message}")
                val upsertDto = UpsertTeacherRequest(
                    id = teacherId,
                    email = user?.email ?: _currentTeacher.value?.email ?: "",
                    fullName = fullName,
                    phoneNumber = phoneNumber?.ifBlank { null },
                    subject = subject?.ifBlank { null },
                    centerName = centerName?.ifBlank { null },
                    educationalStage = educationalStage?.ifBlank { null },
                    avatarUrl = avatarUrl
                )
                client.postgrest["teachers"].upsert(upsertDto)
            }

            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ البيانات."))
            }

            val updated = Teacher(
                id = teacherId,
                email = user?.email ?: _currentTeacher.value?.email ?: "",
                fullName = fullName,
                phoneNumber = phoneNumber?.ifBlank { null },
                subject = subject?.ifBlank { null },
                centerName = centerName?.ifBlank { null },
                educationalStage = educationalStage?.ifBlank { null },
                avatarUrl = avatarUrl
            )

            _currentTeacher.value = updated
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseTeacherRepo", "updateTeacherProfile failed: ${e.message}", e)
            val rawMsg = e.localizedMessage ?: e.message ?: e.toString()
            Result.failure(Exception("فشل حفظ البيانات: $rawMsg"))
        }
    }

    override suspend fun uploadAvatar(bytes: ByteArray, fileName: String, mimeType: String): Result<String> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("جلسة تسجيل الدخول غير صحيحة أو انتهت."))

        try {
            val bucket = client.storage.from("teacher-avatars")
            val filePath = "$teacherId/$fileName"

            bucket.upload(filePath, bytes) {
                upsert = true
            }
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء رفع الصورة."))
            }
            val publicUrl = bucket.publicUrl(filePath)
            Result.success(publicUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseTeacherRepo", "uploadAvatar failed: ${e.message}", e)
            val rawMsg = e.localizedMessage ?: e.message ?: e.toString()
            Result.failure(Exception("فشل رفع الصورة: $rawMsg"))
        }
    }

    override suspend fun deleteAvatar(): Result<Unit> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("غير مسجل الدخول."))

        try {
            val current = _currentTeacher.value
            val avatarUrl = current?.avatarUrl
            if (!avatarUrl.isNullOrBlank()) {
                val bucket = client.storage.from("teacher-avatars")
                val fileName = avatarUrl.substringAfterLast("/")
                if (fileName.isNotBlank()) {
                    try {
                        bucket.delete("$teacherId/$fileName")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("SupabaseTeacherRepo", "Failed to remove avatar file from storage: ${e.message}")
                    }
                }
            }

            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حذف الصورة."))
            }

            updateTeacherProfile(
                fullName = current?.fullName ?: "",
                phoneNumber = current?.phoneNumber,
                subject = current?.subject,
                centerName = current?.centerName,
                educationalStage = current?.educationalStage,
                avatarUrl = null
            ).map { Unit }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseTeacherRepo", "deleteAvatar failed: ${e.message}", e)
            val rawMsg = e.localizedMessage ?: e.message ?: e.toString()
            Result.failure(Exception("فشل حذف الصورة: $rawMsg"))
        }
    }
}
