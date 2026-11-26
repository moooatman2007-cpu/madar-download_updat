package com.example.data.repository

import com.example.core.model.Attendance
import com.example.core.model.AttendanceStatus
import com.example.core.model.AttendanceSummary
import com.example.core.model.BatchAttendanceItemDto
import com.example.core.model.BatchAttendanceResult
import com.example.core.model.SyncResult
import kotlinx.coroutines.flow.Flow

interface AttendanceRepository {
    fun getAttendanceForStudent(studentId: String): Flow<List<Attendance>>
    fun getAttendanceForStudentByMonth(studentId: String, year: Int, month: Int): Flow<List<Attendance>>
    suspend fun getAttendanceSummaryForStudent(studentId: String, year: Int, month: Int): AttendanceSummary
    suspend fun fetchAttendanceForStudentByMonthResult(studentId: String, year: Int, month: Int): Result<List<Attendance>> =
        runCatching { emptyList() }
    suspend fun getTodayAttendanceCount(): Pair<Int, Int> // (presentCount, absentCount)
    suspend fun fetchTodayAttendanceCountResult(): Result<Pair<Int, Int>> =
        runCatching { getTodayAttendanceCount() }
    fun getAllAttendanceForTeacher(): Flow<List<Attendance>>
    suspend fun refreshAllAttendanceForTeacher(): Result<List<Attendance>> =
        runCatching { emptyList() }
    suspend fun recordOrUpdateAttendance(
        studentId: String,
        date: String, // "YYYY-MM-DD"
        status: AttendanceStatus,
        note: String? = null,
        groupId: String? = null
    ): Result<SyncResult<Attendance>>
    suspend fun recordBatchAttendance(
        date: String,
        records: List<BatchAttendanceItemDto>,
        groupId: String? = null
    ): Result<BatchAttendanceResult>
    suspend fun deleteAttendance(attendanceId: String): Result<Unit>
    suspend fun getAttendanceByDate(studentId: String, date: String): Attendance?
    suspend fun getAttendanceForDateAndGroup(date: String, groupId: String?): List<Attendance>
    suspend fun fetchAttendanceForDateAndGroupResult(date: String, groupId: String?): Result<List<Attendance>> =
        runCatching { getAttendanceForDateAndGroup(date, groupId) }
}
