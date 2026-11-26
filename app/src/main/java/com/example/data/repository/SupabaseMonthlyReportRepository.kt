package com.example.data.repository

import android.util.Log
import com.example.core.model.GradeMonthlyReportOverview
import com.example.core.model.MonthlyReport
import com.example.core.model.StudentMonthlyPerformance
import com.example.core.model.StudentMonthlyPerformanceViewDto
import com.example.core.model.SupabaseMonthlyReportDto
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface MonthlyReportRemoteDataSource {
    suspend fun fetchMonthlyReport(teacherId: String, studentId: String, year: Int, month: Int): SupabaseMonthlyReportDto?
    suspend fun updateMonthlyReport(teacherId: String, reportId: String, dto: SupabaseMonthlyReportDto): MonthlyReport
    suspend fun insertMonthlyReport(dto: SupabaseMonthlyReportDto): MonthlyReport
    suspend fun fetchStudentMonthlyPerformance(studentId: String, year: Int, month: Int): StudentMonthlyPerformance?
    suspend fun fetchGradeMonthlyPerformanceList(gradeId: String?, year: Int, month: Int): List<StudentMonthlyPerformance>
}

class SupabaseMonthlyReportRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : MonthlyReportRemoteDataSource {

    override suspend fun fetchMonthlyReport(
        teacherId: String,
        studentId: String,
        year: Int,
        month: Int
    ): SupabaseMonthlyReportDto? {
        return client.postgrest["monthly_reports"]
            .select {
                filter {
                    eq("student_id", studentId)
                    eq("teacher_id", teacherId)
                    eq("year", year)
                    eq("month", month)
                }
            }
            .decodeSingleOrNull<SupabaseMonthlyReportDto>()
    }

    override suspend fun updateMonthlyReport(
        teacherId: String,
        reportId: String,
        dto: SupabaseMonthlyReportDto
    ): MonthlyReport {
        return client.postgrest["monthly_reports"]
            .update(dto) {
                filter {
                    eq("id", reportId)
                    eq("teacher_id", teacherId)
                }
                select()
            }
            .decodeSingle<SupabaseMonthlyReportDto>()
            .toMonthlyReport()
    }

    override suspend fun insertMonthlyReport(dto: SupabaseMonthlyReportDto): MonthlyReport {
        return client.postgrest["monthly_reports"]
            .insert(dto) {
                select()
            }
            .decodeSingle<SupabaseMonthlyReportDto>()
            .toMonthlyReport()
    }

    override suspend fun fetchStudentMonthlyPerformance(
        studentId: String,
        year: Int,
        month: Int
    ): StudentMonthlyPerformance? {
        val params = buildJsonObject {
            put("p_year", year)
            put("p_month", month)
            put("p_student_id", studentId)
        }
        return client.postgrest.rpc(
            function = "get_student_monthly_performance",
            parameters = params
        ).decodeList<StudentMonthlyPerformanceViewDto>()
            .firstOrNull()
            ?.toStudentMonthlyPerformance()
    }

    override suspend fun fetchGradeMonthlyPerformanceList(
        gradeId: String?,
        year: Int,
        month: Int
    ): List<StudentMonthlyPerformance> {
        val params = buildJsonObject {
            put("p_year", year)
            put("p_month", month)
            if (gradeId != null) {
                put("p_grade_id", gradeId)
            }
        }
        return client.postgrest.rpc(
            function = "get_student_monthly_performance",
            parameters = params
        ).decodeList<StudentMonthlyPerformanceViewDto>()
            .map { it.toStudentMonthlyPerformance() }
    }
}

class SupabaseMonthlyReportRepository(
    private val remoteDataSource: MonthlyReportRemoteDataSource = SupabaseMonthlyReportRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MonthlyReportRepository {

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    fun clearCache() {
        // Pure Online-Only: no persistent or stale report cache stored locally
    }

    override suspend fun fetchMonthlyReportResult(
        studentId: String,
        year: Int,
        month: Int
    ): Result<MonthlyReport?> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        try {
            val dto = remoteDataSource.fetchMonthlyReport(teacherId, studentId, year, month)
            if (!isStillAuthorized(teacherId)) {
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب التقرير."))
            }
            Result.success(dto?.toMonthlyReport())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseMonthlyReport", "fetchMonthlyReportResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getMonthlyReport(
        studentId: String,
        year: Int,
        month: Int
    ): MonthlyReport? = fetchMonthlyReportResult(studentId, year, month).getOrNull()

    override suspend fun saveOrUpdateMonthlyReport(
        studentId: String,
        year: Int,
        month: Int,
        teacherNote: String
    ): Result<MonthlyReport> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        try {
            val existing = remoteDataSource.fetchMonthlyReport(teacherId, studentId, year, month)
            val saved = if (existing != null) {
                val updatedDto = SupabaseMonthlyReportDto(
                    id = existing.id,
                    teacherId = teacherId,
                    studentId = studentId,
                    year = year,
                    month = month,
                    teacherNote = teacherNote
                )
                remoteDataSource.updateMonthlyReport(teacherId, existing.id ?: "", updatedDto)
            } else {
                val newDto = SupabaseMonthlyReportDto(
                    teacherId = teacherId,
                    studentId = studentId,
                    year = year,
                    month = month,
                    teacherNote = teacherNote
                )
                remoteDataSource.insertMonthlyReport(newDto)
            }

            if (!isStillAuthorized(teacherId)) {
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ التقرير."))
            }

            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseMonthlyReport", "saveOrUpdateMonthlyReport failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun fetchStudentMonthlyPerformanceResult(
        studentId: String,
        year: Int,
        month: Int
    ): Result<StudentMonthlyPerformance?> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        try {
            val perf = remoteDataSource.fetchStudentMonthlyPerformance(studentId, year, month)
            if (!isStillAuthorized(teacherId)) {
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب الأداء الشهري."))
            }
            Result.success(perf)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseMonthlyReport", "fetchStudentMonthlyPerformanceResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getStudentMonthlyPerformance(
        studentId: String,
        year: Int,
        month: Int
    ): StudentMonthlyPerformance? = fetchStudentMonthlyPerformanceResult(studentId, year, month).getOrNull()

    override suspend fun fetchGradeMonthlyReportOverviewResult(
        gradeId: String?,
        year: Int,
        month: Int
    ): Result<GradeMonthlyReportOverview> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى إعادة تسجيل الدخول."))

        try {
            val list = remoteDataSource.fetchGradeMonthlyPerformanceList(gradeId, year, month)
            if (!isStillAuthorized(teacherId)) {
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب التقرير الشهري."))
            }

            val total = list.size
            val avgAttendance = if (total > 0) list.map { it.attendanceSummary.attendanceRate }.average().toFloat() else 0f
            val avgRecitation = if (total > 0) list.map { it.recitationSummary.averagePercentage }.average().toFloat() else 0f
            val avgExam = if (total > 0) list.map { it.examSummary.averagePercentage }.average().toFloat() else 0f

            val gradeName = if (gradeId != null && list.isNotEmpty()) {
                list.first().grade?.name ?: "الصف المحدد"
            } else if (gradeId != null) {
                "الصف المحدد"
            } else {
                "جميع الصفوف"
            }

            Result.success(
                GradeMonthlyReportOverview(
                    month = month,
                    year = year,
                    gradeId = gradeId,
                    gradeName = gradeName,
                    totalStudents = total,
                    averageAttendanceRate = avgAttendance,
                    averageRecitationRate = avgRecitation,
                    averageExamRate = avgExam,
                    studentPerformances = list
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabaseMonthlyReport", "fetchGradeMonthlyReportOverviewResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getGradeMonthlyReportOverview(
        gradeId: String?,
        year: Int,
        month: Int
    ): GradeMonthlyReportOverview {
        return fetchGradeMonthlyReportOverviewResult(gradeId, year, month).getOrElse {
            GradeMonthlyReportOverview(
                month = month,
                year = year,
                gradeId = gradeId,
                gradeName = if (gradeId == null) "جميع الصفوف" else "",
                totalStudents = 0,
                averageAttendanceRate = 0f,
                averageRecitationRate = 0f,
                averageExamRate = 0f,
                studentPerformances = emptyList()
            )
        }
    }
}
