package com.example.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SupabaseGroupDto(
    val id: String,
    @SerialName("teacher_id") val teacherId: String,
    @SerialName("grade_id") val gradeId: String,
    val name: String,
    val active: Boolean = true,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val capacity: Int? = null,
    val location: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class UpdateGroupOnlineDto(
    @SerialName("grade_id") val gradeId: String,
    val name: String,
    val active: Boolean,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    val capacity: Int?,
    val location: String?
)

@Serializable
data class UpdateGroupActiveDto(
    val active: Boolean
)

fun Group.toSupabaseDto(): SupabaseGroupDto = SupabaseGroupDto(
    id = id,
    teacherId = teacherId,
    gradeId = gradeId,
    name = name,
    active = active,
    startTime = startTime,
    endTime = endTime,
    capacity = capacity,
    location = location,
    createdAt = null,
    updatedAt = null
)

fun Group.toUpdateOnlineDto(): UpdateGroupOnlineDto = UpdateGroupOnlineDto(
    gradeId = gradeId,
    name = name,
    active = active,
    startTime = startTime,
    endTime = endTime,
    capacity = capacity,
    location = location
)

fun SupabaseGroupDto.toDomain(): Group = Group(
    id = id,
    teacherId = teacherId,
    gradeId = gradeId,
    name = name,
    active = active,
    startTime = startTime,
    endTime = endTime,
    capacity = capacity,
    location = location,
    createdAt = parseGroupIsoToMillis(createdAt),
    updatedAt = parseGroupIsoToMillis(updatedAt)
)

private fun parseGroupIsoToMillis(isoString: String?): Long {
    if (isoString.isNullOrBlank()) return System.currentTimeMillis()
    return try {
        java.time.OffsetDateTime.parse(isoString).toInstant().toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.Instant.parse(isoString).toEpochMilli()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
