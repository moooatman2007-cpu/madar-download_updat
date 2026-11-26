package com.example.data.repository

import com.example.core.model.GradeMonthlyReportOverview
import com.example.core.model.MonthlyReport
import com.example.core.model.StudentMonthlyPerformance

interface MonthlyReportRepository {
    suspend fun getMonthlyReport(studentId: String, year: Int, month: Int): MonthlyReport?
    suspend fun fetchMonthlyReportResult(studentId: String, year: Int, month: Int): Result<MonthlyReport?> =
        runCatching { getMonthlyReport(studentId, year, month) }
    suspend fun saveOrUpdateMonthlyReport(
        studentId: String,
        year: Int,
        month: Int,
        teacherNote: String
    ): Result<MonthlyReport>
    suspend fun getStudentMonthlyPerformance(
        studentId: String,
        year: Int,
        month: Int
    ): StudentMonthlyPerformance?
    suspend fun fetchStudentMonthlyPerformanceResult(
        studentId: String,
        year: Int,
        month: Int
    ): Result<StudentMonthlyPerformance?> =
        runCatching { getStudentMonthlyPerformance(studentId, year, month) }
    suspend fun getGradeMonthlyReportOverview(
        gradeId: String?, // null for all
        year: Int,
        month: Int
    ): GradeMonthlyReportOverview
    suspend fun fetchGradeMonthlyReportOverviewResult(
        gradeId: String?,
        year: Int,
        month: Int
    ): Result<GradeMonthlyReportOverview> =
        runCatching { getGradeMonthlyReportOverview(gradeId, year, month) }
}
