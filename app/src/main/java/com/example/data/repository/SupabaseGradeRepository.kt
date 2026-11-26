package com.example.data.repository

import android.util.Log
import com.example.core.model.EducationalStages
import com.example.core.model.Grade
import com.example.core.model.Teacher
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class GradeDeletionBlockedException(message: String) : IllegalStateException(message)

interface GradeRemoteDataSource {
    suspend fun fetchTeacherProfile(teacherId: String): Teacher?
    suspend fun fetchGrades(teacherId: String): List<Grade>
    suspend fun fetchGradeById(teacherId: String, gradeId: String): Grade?
    suspend fun insertGrade(teacherId: String, id: String, name: String, displayOrder: Int): Grade
    suspend fun updateGrade(teacherId: String, id: String, name: String, displayOrder: Int): Grade
    suspend fun updateGradeName(teacherId: String, id: String, name: String)
    suspend fun hasStudentsInGrade(teacherId: String, gradeId: String): Boolean
    suspend fun hasGroupsInGrade(teacherId: String, gradeId: String): Boolean
    suspend fun deleteGrade(teacherId: String, id: String)
}

@Serializable
private data class GradeLinkIdRow(val id: String)

class SupabaseGradeRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : GradeRemoteDataSource {

    override suspend fun fetchTeacherProfile(teacherId: String): Teacher? {
        return client.postgrest["teachers"]
            .select {
                filter { eq("id", teacherId) }
            }
            .decodeSingleOrNull<Teacher>()
    }

    override suspend fun fetchGrades(teacherId: String): List<Grade> {
        return client.postgrest["grades"]
            .select {
                filter { eq("teacher_id", teacherId) }
                order("display_order", order = Order.ASCENDING)
            }
            .decodeList<Grade>()
    }

    override suspend fun fetchGradeById(teacherId: String, gradeId: String): Grade? {
        return client.postgrest["grades"]
            .select {
                filter {
                    eq("id", gradeId)
                    eq("teacher_id", teacherId)
                }
            }
            .decodeSingleOrNull<Grade>()
    }

    override suspend fun insertGrade(teacherId: String, id: String, name: String, displayOrder: Int): Grade {
        val dto = mapOf(
            "id" to id,
            "teacher_id" to teacherId,
            "name" to name,
            "display_order" to displayOrder
        )
        return client.postgrest["grades"]
            .insert(dto) {
                select()
            }
            .decodeSingle<Grade>()
    }

    override suspend fun updateGrade(teacherId: String, id: String, name: String, displayOrder: Int): Grade {
        val dto = mapOf(
            "name" to name,
            "display_order" to displayOrder
        )
        return client.postgrest["grades"]
            .update(dto) {
                filter {
                    eq("id", id)
                    eq("teacher_id", teacherId)
                }
                select()
            }
            .decodeSingle<Grade>()
    }

    override suspend fun updateGradeName(teacherId: String, id: String, name: String) {
        client.postgrest["grades"].update(mapOf("name" to name)) {
            filter {
                eq("id", id)
                eq("teacher_id", teacherId)
            }
        }
    }

    override suspend fun hasStudentsInGrade(teacherId: String, gradeId: String): Boolean {
        return client.postgrest["students"].select(columns = Columns.list("id")) {
            filter {
                eq("teacher_id", teacherId)
                eq("grade_id", gradeId)
                exact("deleted_at", null)
            }
            limit(1)
        }.decodeList<GradeLinkIdRow>().isNotEmpty()
    }

    override suspend fun hasGroupsInGrade(teacherId: String, gradeId: String): Boolean {
        return client.postgrest["groups"].select(columns = Columns.list("id")) {
            filter {
                eq("teacher_id", teacherId)
                eq("grade_id", gradeId)
            }
            limit(1)
        }.decodeList<GradeLinkIdRow>().isNotEmpty()
    }

    override suspend fun deleteGrade(teacherId: String, id: String) {
        client.postgrest["grades"].delete {
            filter {
                eq("id", id)
                eq("teacher_id", teacherId)
            }
        }
    }
}

class SupabaseGradeRepository(
    private val remoteDataSource: GradeRemoteDataSource = SupabaseGradeRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val teacherStageMetadataProvider: () -> String? = {
        try {
            SupabaseClientProvider.client.auth.currentUserOrNull()
                ?.userMetadata?.get("educational_stage")?.toString()?.replace("\"", "")
        } catch (_: Exception) {
            null
        }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GradeRepository {

    // Session-scoped in-memory state only (never persisted to Room/SQLite; no TTL caching).
    private val _sessionGrades = MutableStateFlow<Pair<String?, List<Grade>>>(null to emptyList())

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    fun clearCache() {
        _sessionGrades.value = null to emptyList()
    }

    suspend fun fetchGrades(): List<Grade> = refreshGrades()

    override suspend fun refreshGradesResult(stage: String?): Result<List<Grade>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))
        }

        try {
            val teacherFromDb = if (stage.isNullOrBlank()) {
                try {
                    remoteDataSource.fetchTeacherProfile(teacherId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else null

            val metaStage = teacherStageMetadataProvider()
            val resolvedStage = stage?.ifBlank { null }
                ?: teacherFromDb?.educationalStage?.ifBlank { null }
                ?: metaStage?.ifBlank { null }
                ?: EducationalStages.PREPARATORY

            val fetched = remoteDataSource.fetchGrades(teacherId)
            val ensured = ensureStageGrades(teacherId, resolvedStage, fetched)

            val normalized = ensured.map { g ->
                val norm = EducationalStages.normalizeGradeName(g.name)
                if (norm != g.name) {
                    try {
                        remoteDataSource.updateGradeName(teacherId, g.id, norm)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                    g.copy(name = norm, teacherId = teacherId)
                } else {
                    if (g.teacherId == null) g.copy(teacherId = teacherId) else g
                }
            }

            val matchedGrades = normalized
                .filter { EducationalStages.isGradeMatchingStage(it.name, resolvedStage) }
                .sortedBy { it.displayOrder }

            val stageGrades = if (matchedGrades.isNotEmpty() || normalized.isEmpty()) {
                matchedGrades
            } else {
                normalized.sortedBy { it.displayOrder }
            }

            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تحميل الصفوف الدراسية."))
            }

            _sessionGrades.value = teacherId to stageGrades
            Result.success(stageGrades)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_sessionGrades.value.first != teacherId) {
                clearCache()
            }
            Log.e("SupabaseGradeRepo", "refreshGradesResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun refreshGrades(stage: String?): List<Grade> {
        return refreshGradesResult(stage).getOrDefault(emptyList())
    }

    private suspend fun ensureStageGrades(
        userId: String,
        stage: String,
        existingGrades: List<Grade>
    ): List<Grade> {
        val expectedNames = EducationalStages.getGradeNamesForStage(stage)
        val existingNormalized = existingGrades.map { EducationalStages.normalizeGradeName(it.name) }.toSet()
        val missingNames = expectedNames.filter { !existingNormalized.contains(it) }

        if (missingNames.isNotEmpty()) {
            for (missingName in missingNames) {
                val order = expectedNames.indexOf(missingName) + 1
                try {
                    remoteDataSource.insertGrade(
                        teacherId = userId,
                        id = java.util.UUID.randomUUID().toString(),
                        name = missingName,
                        displayOrder = order
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Ignore duplicate on race
                }
            }
            return try {
                remoteDataSource.fetchGrades(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                existingGrades
            }
        }
        return existingGrades
    }

    override suspend fun ensureGradesForStage(stage: String?): List<Grade> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId() ?: return@withContext emptyList()
        try {
            val targetStage = stage?.ifBlank { null } ?: EducationalStages.PREPARATORY
            val fetched = remoteDataSource.fetchGrades(teacherId)
            val ensured = ensureStageGrades(teacherId, targetStage, fetched)
            val filtered = ensured
                .filter { EducationalStages.isGradeMatchingStage(it.name, targetStage) }
                .sortedBy { it.displayOrder }
                .map { if (it.teacherId == null) it.copy(teacherId = teacherId) else it }

            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext emptyList()
            }
            _sessionGrades.value = teacherId to filtered
            filtered
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGradeRepo", "ensureGradesForStage failed: ${e.message}", e)
            emptyList()
        }
    }

    override fun getGrades(): Flow<List<Grade>> {
        return _sessionGrades.map { (owner, list) ->
            val current = resolveActiveTeacherId()
            if (current == null || owner != current) emptyList() else list
        }
    }

    override suspend fun getGradeById(id: String): Grade? = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId() ?: return@withContext null
        try {
            val fetched = remoteDataSource.fetchGradeById(teacherId, id)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext null
            }
            fetched?.copy(teacherId = teacherId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGradeRepo", "getGradeById failed: ${e.message}", e)
            null
        }
    }

    override suspend fun addGradeResult(grade: Grade): Result<Grade> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        val cleanName = grade.name.trim()
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("اسم الصف الدراسي مطلوب."))
        }

        try {
            val inserted = remoteDataSource.insertGrade(
                teacherId = teacherId,
                id = grade.id,
                name = cleanName,
                displayOrder = grade.displayOrder
            )
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء إضافة الصف."))
            }

            val saved = inserted.copy(teacherId = teacherId)
            val (owner, current) = _sessionGrades.value
            val base = if (owner == teacherId) current else emptyList()
            _sessionGrades.value = teacherId to (base.filterNot { it.id == saved.id } + saved).sortedBy { it.displayOrder }

            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGradeRepo", "addGradeResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun addGrade(grade: Grade): Boolean = addGradeResult(grade).isSuccess

    override suspend fun updateGradeResult(grade: Grade): Result<Grade> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        val cleanName = grade.name.trim()
        if (cleanName.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("اسم الصف الدراسي مطلوب."))
        }

        try {
            val updated = remoteDataSource.updateGrade(
                teacherId = teacherId,
                id = grade.id,
                name = cleanName,
                displayOrder = grade.displayOrder
            )
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تحديث الصف."))
            }

            val saved = updated.copy(teacherId = teacherId)
            val (owner, current) = _sessionGrades.value
            val base = if (owner == teacherId) current else emptyList()
            _sessionGrades.value = teacherId to base.map { if (it.id == saved.id) saved else it }.sortedBy { it.displayOrder }

            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGradeRepo", "updateGradeResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateGrade(grade: Grade): Boolean = updateGradeResult(grade).isSuccess

    override suspend fun deleteGradeResult(id: String): Result<Unit> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            if (remoteDataSource.hasStudentsInGrade(teacherId, id) ||
                remoteDataSource.hasGroupsInGrade(teacherId, id)
            ) {
                return@withContext Result.failure(
                    GradeDeletionBlockedException("لا يمكن حذف الصف لوجود طلاب أو مجموعات مرتبطة به.")
                )
            }

            remoteDataSource.deleteGrade(teacherId, id)

            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حذف الصف."))
            }

            val (owner, current) = _sessionGrades.value
            if (owner == teacherId) {
                _sessionGrades.value = teacherId to current.filterNot { it.id == id }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "${e.message.orEmpty()} ${e.cause?.message.orEmpty()}".lowercase()
            if (msg.contains("23503") || msg.contains("foreign key") || msg.contains("is still referenced")) {
                return@withContext Result.failure(
                    GradeDeletionBlockedException("لا يمكن حذف الصف لوجود طلاب أو مجموعات مرتبطة به.")
                )
            }
            Log.e("SupabaseGradeRepo", "deleteGradeResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteGrade(id: String): Boolean = deleteGradeResult(id).isSuccess
}
