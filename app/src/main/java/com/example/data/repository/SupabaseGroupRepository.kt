package com.example.data.repository

import android.util.Log
import com.example.core.model.Group
import com.example.core.model.GroupDay
import com.example.core.model.Student
import com.example.core.model.SupabaseGroupDayDto
import com.example.core.model.SupabaseGroupDto
import com.example.core.model.UpdateGroupActiveDto
import com.example.core.model.UpdateStudentGroupDto
import com.example.core.model.toDomain
import com.example.core.model.toSupabaseDto
import com.example.core.model.toUpdateOnlineDto
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class GroupDeletionBlockedException(message: String) : IllegalStateException(message)

interface GroupRemoteDataSource {
    suspend fun fetchGroups(teacherId: String): List<Group>
    suspend fun fetchGroupDays(teacherId: String): List<GroupDay>
    suspend fun fetchGroupById(teacherId: String, groupId: String): Group?
    suspend fun fetchGroupDaysForGroup(teacherId: String, groupId: String): List<GroupDay>
    suspend fun createGroup(group: Group): Group
    suspend fun updateGroup(group: Group): Group
    suspend fun setGroupActive(teacherId: String, groupId: String, active: Boolean): Group?
    suspend fun hasStudentsInGroup(teacherId: String, groupId: String): Boolean
    suspend fun hasAttendanceInGroup(teacherId: String, groupId: String): Boolean
    suspend fun deleteGroupDays(teacherId: String, groupId: String)
    suspend fun insertGroupDays(days: List<GroupDay>)
    suspend fun deleteGroupRow(teacherId: String, groupId: String)
    suspend fun fetchStudentById(teacherId: String, studentId: String): Student?
    suspend fun updateStudentGroup(teacherId: String, studentId: String, groupId: String?): Student
}

@Serializable
private data class RemoteIdRow(val id: String)

class SupabaseGroupRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : GroupRemoteDataSource {

    override suspend fun fetchGroups(teacherId: String): List<Group> {
        return client.postgrest["groups"].select {
            filter {
                eq("teacher_id", teacherId)
            }
        }.decodeList<SupabaseGroupDto>().map { it.toDomain() }
    }

    override suspend fun fetchGroupDays(teacherId: String): List<GroupDay> {
        return client.postgrest["group_days"].select {
            filter {
                eq("teacher_id", teacherId)
            }
        }.decodeList<SupabaseGroupDayDto>().map { it.toDomain() }
    }

    override suspend fun fetchGroupById(teacherId: String, groupId: String): Group? {
        return client.postgrest["groups"].select {
            filter {
                eq("id", groupId)
                eq("teacher_id", teacherId)
            }
        }.decodeSingleOrNull<SupabaseGroupDto>()?.toDomain()
    }

    override suspend fun fetchGroupDaysForGroup(teacherId: String, groupId: String): List<GroupDay> {
        return client.postgrest["group_days"].select {
            filter {
                eq("teacher_id", teacherId)
                eq("group_id", groupId)
            }
        }.decodeList<SupabaseGroupDayDto>().map { it.toDomain() }
    }

    override suspend fun createGroup(group: Group): Group {
        return client.postgrest["groups"].insert(group.toSupabaseDto()) {
            select()
        }.decodeSingle<SupabaseGroupDto>().toDomain()
    }

    override suspend fun updateGroup(group: Group): Group {
        return client.postgrest["groups"].update(group.toUpdateOnlineDto()) {
            filter {
                eq("id", group.id)
                eq("teacher_id", group.teacherId)
            }
            select()
        }.decodeSingle<SupabaseGroupDto>().toDomain()
    }

    override suspend fun setGroupActive(teacherId: String, groupId: String, active: Boolean): Group? {
        return client.postgrest["groups"].update(UpdateGroupActiveDto(active = active)) {
            filter {
                eq("id", groupId)
                eq("teacher_id", teacherId)
            }
            select()
        }.decodeSingleOrNull<SupabaseGroupDto>()?.toDomain()
    }

    override suspend fun hasStudentsInGroup(teacherId: String, groupId: String): Boolean {
        return client.postgrest["students"].select(columns = Columns.list("id")) {
            filter {
                eq("teacher_id", teacherId)
                eq("group_id", groupId)
            }
            limit(1)
        }.decodeList<RemoteIdRow>().isNotEmpty()
    }

    override suspend fun hasAttendanceInGroup(teacherId: String, groupId: String): Boolean {
        return client.postgrest["attendance"].select(columns = Columns.list("id")) {
            filter {
                eq("teacher_id", teacherId)
                eq("group_id", groupId)
            }
            limit(1)
        }.decodeList<RemoteIdRow>().isNotEmpty()
    }

    override suspend fun deleteGroupDays(teacherId: String, groupId: String) {
        client.postgrest["group_days"].delete {
            filter {
                eq("group_id", groupId)
                eq("teacher_id", teacherId)
            }
        }
    }

    override suspend fun insertGroupDays(days: List<GroupDay>) {
        if (days.isEmpty()) return
        client.postgrest["group_days"].insert(days.map { it.toSupabaseDto() })
    }

    override suspend fun deleteGroupRow(teacherId: String, groupId: String) {
        client.postgrest["groups"].delete {
            filter {
                eq("id", groupId)
                eq("teacher_id", teacherId)
            }
        }
    }

    override suspend fun fetchStudentById(teacherId: String, studentId: String): Student? {
        return client.postgrest["students"].select {
            filter {
                eq("id", studentId)
                eq("teacher_id", teacherId)
                exact("deleted_at", null)
            }
        }.decodeSingleOrNull<Student>()
    }

    override suspend fun updateStudentGroup(teacherId: String, studentId: String, groupId: String?): Student {
        return client.postgrest["students"].update(UpdateStudentGroupDto(groupId = groupId)) {
            filter {
                eq("id", studentId)
                eq("teacher_id", teacherId)
                exact("deleted_at", null)
            }
            select()
        }.decodeSingle<Student>()
    }
}

class SupabaseGroupRepository(
    private val remoteDataSource: GroupRemoteDataSource = SupabaseGroupRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val onStudentUpdated: ((Student) -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GroupRepository {

    // Session-scoped in-memory state only (never persisted to Room/SQLite).
    private val _sessionGroups = MutableStateFlow<Pair<String?, List<Group>>>(null to emptyList())
    private val _sessionGroupDays = MutableStateFlow<Pair<String?, List<GroupDay>>>(null to emptyList())

    private fun resolveAuthorizedTeacherId(requestedTeacherId: String): String? {
        if (requestedTeacherId.isBlank()) return null
        val activeId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        if (activeId != requestedTeacherId) return null
        return if (sessionActiveChecker(activeId)) activeId else null
    }

    fun clearCache() {
        _sessionGroups.value = null to emptyList()
        _sessionGroupDays.value = null to emptyList()
    }

    override fun observeGroups(teacherId: String): Flow<List<Group>> =
        _sessionGroups.map { (ownerId, list) ->
            val validId = resolveAuthorizedTeacherId(teacherId)
            if (validId == null || ownerId != validId) emptyList() else list.filter { it.teacherId == validId }
        }

    override fun observeActiveGroups(teacherId: String): Flow<List<Group>> =
        observeGroups(teacherId).map { list -> list.filter { it.active } }

    override fun observeGroupsByGrade(teacherId: String, gradeId: String): Flow<List<Group>> =
        observeGroups(teacherId).map { list -> list.filter { it.gradeId == gradeId } }

    override suspend fun fetchGroupById(teacherId: String, groupId: String): Result<Group?> = withContext(ioDispatcher) {
        val validTeacherId = resolveAuthorizedTeacherId(teacherId)
            ?: return@withContext Result.failure(IllegalStateException("غير مصرح أو انتهت الجلسة."))

        try {
            val group = remoteDataSource.fetchGroupById(validTeacherId, groupId)
            if (!sessionActiveChecker(validTeacherId) || teacherIdProvider() != validTeacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب بيانات المجموعة."))
            }
            if (group != null && group.teacherId == validTeacherId) {
                upsertGroupInMemory(validTeacherId, group)
                Result.success(group)
            } else {
                Result.success(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGroupRepo", "Online fetchGroupById failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getGroupById(teacherId: String, groupId: String): Group? {
        return fetchGroupById(teacherId, groupId).getOrNull()
    }

    override suspend fun refreshGroups(teacherId: String): Result<Unit> = withContext(ioDispatcher) {
        val validTeacherId = resolveAuthorizedTeacherId(teacherId)
        if (validTeacherId == null) {
            clearCache()
            return@withContext Result.failure(IllegalStateException("غير مصرح أو انتهت الجلسة."))
        }

        try {
            val remoteGroups = remoteDataSource.fetchGroups(validTeacherId)
                .filter { it.teacherId == validTeacherId }
            val remoteGroupDays = remoteDataSource.fetchGroupDays(validTeacherId)
                .filter { it.teacherId == validTeacherId }

            if (!sessionActiveChecker(validTeacherId) || teacherIdProvider() != validTeacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تحديث المجموعات."))
            }

            _sessionGroups.value = validTeacherId to remoteGroups
            _sessionGroupDays.value = validTeacherId to remoteGroupDays
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_sessionGroups.value.first != validTeacherId) {
                clearCache()
            }
            Log.e("SupabaseGroupRepo", "Online refreshGroups failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun createGroup(group: Group): Result<Group> = withContext(ioDispatcher) {
        val validTeacherId = resolveAuthorizedTeacherId(group.teacherId)
            ?: return@withContext Result.failure(SecurityException("غير مصرح بإنشاء مجموعة لهذا الحساب."))

        try {
            val created = remoteDataSource.createGroup(group)
            if (!sessionActiveChecker(validTeacherId) || teacherIdProvider() != validTeacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء إنشاء المجموعة."))
            }
            upsertGroupInMemory(validTeacherId, created)
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGroupRepo", "Online createGroup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateGroup(group: Group): Result<Group> = withContext(ioDispatcher) {
        val validTeacherId = resolveAuthorizedTeacherId(group.teacherId)
            ?: return@withContext Result.failure(SecurityException("غير مصرح بتعديل هذه المجموعة."))

        try {
            val updated = remoteDataSource.updateGroup(group)
            if (!sessionActiveChecker(validTeacherId) || teacherIdProvider() != validTeacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تحديث المجموعة."))
            }
            upsertGroupInMemory(validTeacherId, updated)
            Result.success(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGroupRepo", "Online updateGroup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deactivateGroup(teacherId: String, groupId: String): Result<Unit> = withContext(ioDispatcher) {
        val validTeacherId = resolveAuthorizedTeacherId(teacherId)
            ?: return@withContext Result.failure(SecurityException("غير مصرح بتعطيل هذه المجموعة."))

        try {
            val updated = remoteDataSource.setGroupActive(validTeacherId, groupId, false)
                ?: return@withContext Result.failure(NoSuchElementException("المجموعة غير موجودة."))
            if (!sessionActiveChecker(validTeacherId) || teacherIdProvider() != validTeacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تعطيل المجموعة."))
            }
            upsertGroupInMemory(validTeacherId, updated)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGroupRepo", "Online deactivateGroup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteGroup(teacherId: String, groupId: String): Result<Unit> = withContext(ioDispatcher) {
        val validTeacherId = resolveAuthorizedTeacherId(teacherId)
            ?: return@withContext Result.failure(SecurityException("غير مصرح بحذف هذه المجموعة."))

        try {
            val group = remoteDataSource.fetchGroupById(validTeacherId, groupId)
                ?: return@withContext Result.failure(NoSuchElementException("المجموعة غير موجودة."))
            if (group.teacherId != validTeacherId) {
                return@withContext Result.failure(SecurityException("غير مصرح بحذف هذه المجموعة."))
            }

            // CRITICAL SAFETY CHECK: Never delete group_days before confirming deletion is allowed!
            val hasLinkedStudents = remoteDataSource.hasStudentsInGroup(validTeacherId, groupId)
            val hasLinkedAttendance = remoteDataSource.hasAttendanceInGroup(validTeacherId, groupId)
            if (hasLinkedStudents || hasLinkedAttendance) {
                return@withContext Result.failure(
                    GroupDeletionBlockedException(
                        "لا يمكن حذف المجموعة لأنها مرتبطة بطلاب مسجلين أو سجلات حضور سابقة. يمكنك تعطيل المجموعة بدلاً من حذفها."
                    )
                )
            }

            // Backup current group_days in case a concurrent insert causes a FK failure on deleteGroupRow
            val backupDays = remoteDataSource.fetchGroupDaysForGroup(validTeacherId, groupId)

            remoteDataSource.deleteGroupDays(validTeacherId, groupId)
            try {
                remoteDataSource.deleteGroupRow(validTeacherId, groupId)
            } catch (deleteEx: Exception) {
                if (backupDays.isNotEmpty()) {
                    try {
                        remoteDataSource.insertGroupDays(backupDays)
                    } catch (restoreEx: Exception) {
                        Log.w("SupabaseGroupRepo", "Failed to restore group_days after delete rollback: ${restoreEx.message}")
                    }
                }
                val msg = deleteEx.message?.lowercase() ?: ""
                if (msg.contains("foreign key") || msg.contains("23503") || msg.contains("violates foreign key") || msg.contains("is still referenced")) {
                    return@withContext Result.failure(
                        GroupDeletionBlockedException(
                            "لا يمكن حذف المجموعة لأنها مرتبطة بطلاب مسجلين أو سجلات حضور سابقة. يمكنك تعطيل المجموعة بدلاً من حذفها."
                        )
                    )
                }
                return@withContext Result.failure(deleteEx)
            }

            if (!sessionActiveChecker(validTeacherId) || teacherIdProvider() != validTeacherId) {
                clearCache()
                return@withContext Result.success(Unit)
            }

            // Remove from in-memory state ONLY after server deletion succeeds
            val (gOwner, gList) = _sessionGroups.value
            if (gOwner == validTeacherId) {
                _sessionGroups.value = validTeacherId to gList.filterNot { it.id == groupId }
            }
            val (dOwner, dList) = _sessionGroupDays.value
            if (dOwner == validTeacherId) {
                _sessionGroupDays.value = validTeacherId to dList.filterNot { it.groupId == groupId }
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGroupRepo", "Online deleteGroup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun assignStudentToGroup(teacherId: String, studentId: String, groupId: String?): Result<Unit> = withContext(ioDispatcher) {
        val validTeacherId = resolveAuthorizedTeacherId(teacherId)
            ?: return@withContext Result.failure(SecurityException("غير مصرح بتحديث مجموعة الطالب."))

        try {
            val student = remoteDataSource.fetchStudentById(validTeacherId, studentId)
                ?: return@withContext Result.failure(NoSuchElementException("لم يتم العثور على الطالب."))

            if (groupId != null) {
                val group = remoteDataSource.fetchGroupById(validTeacherId, groupId)
                    ?: return@withContext Result.failure(NoSuchElementException("المجموعة غير موجودة."))
                if (group.teacherId != validTeacherId) {
                    return@withContext Result.failure(SecurityException("غير مصرح بتعيين الطالب لهذه المجموعة."))
                }
                if (group.gradeId != student.gradeId) {
                    return@withContext Result.failure(IllegalArgumentException("المجموعة غير متوافقة مع الصف الدراسي للطالب."))
                }
            }

            val updatedStudent = remoteDataSource.updateStudentGroup(validTeacherId, studentId, groupId)
            if (!sessionActiveChecker(validTeacherId) || teacherIdProvider() != validTeacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء تعيين المجموعة."))
            }

            onStudentUpdated?.invoke(updatedStudent)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGroupRepo", "Online assignStudentToGroup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun observeGroupDays(teacherId: String, groupId: String): Flow<List<GroupDay>> =
        observeAllGroupDays(teacherId).map { list -> list.filter { it.groupId == groupId } }

    override fun observeAllGroupDays(teacherId: String): Flow<List<GroupDay>> =
        _sessionGroupDays.map { (ownerId, list) ->
            val validId = resolveAuthorizedTeacherId(teacherId)
            if (validId == null || ownerId != validId) emptyList() else list.filter { it.teacherId == validId }
        }

    override suspend fun replaceGroupDays(teacherId: String, groupId: String, days: List<String>): Result<Unit> = withContext(ioDispatcher) {
        val validTeacherId = resolveAuthorizedTeacherId(teacherId)
            ?: return@withContext Result.failure(SecurityException("غير مصرح بتعديل أيام المجموعة."))

        val cleanDays = days.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val newGroupDays = cleanDays.map { GroupDay(teacherId = validTeacherId, groupId = groupId, dayOfWeek = it) }

        try {
            val previousDays = remoteDataSource.fetchGroupDaysForGroup(validTeacherId, groupId)
            remoteDataSource.deleteGroupDays(validTeacherId, groupId)
            try {
                if (newGroupDays.isNotEmpty()) {
                    remoteDataSource.insertGroupDays(newGroupDays)
                }
            } catch (insertEx: Exception) {
                if (previousDays.isNotEmpty()) {
                    try {
                        remoteDataSource.insertGroupDays(previousDays)
                    } catch (restoreEx: Exception) {
                        Log.w("SupabaseGroupRepo", "Failed to restore previous group_days: ${restoreEx.message}")
                    }
                }
                return@withContext Result.failure(insertEx)
            }

            if (!sessionActiveChecker(validTeacherId) || teacherIdProvider() != validTeacherId) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ أيام المجموعة."))
            }

            // Update in-memory state ONLY after server confirmation
            val (ownerId, currentDays) = _sessionGroupDays.value
            val baseDays = if (ownerId == validTeacherId) currentDays else emptyList()
            _sessionGroupDays.value = validTeacherId to (baseDays.filterNot { it.groupId == groupId } + newGroupDays)

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseGroupRepo", "Online replaceGroupDays failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun upsertGroupInMemory(teacherId: String, group: Group) {
        val (ownerId, currentList) = _sessionGroups.value
        val baseList = if (ownerId == teacherId) currentList else emptyList()
        val mutable = baseList.toMutableList()
        val idx = mutable.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            mutable[idx] = group
        } else {
            mutable.add(0, group)
        }
        _sessionGroups.value = teacherId to mutable
    }
}
