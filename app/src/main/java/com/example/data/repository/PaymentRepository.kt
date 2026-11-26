package com.example.data.repository

import com.example.core.model.LessonPayment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface PaymentRepository {
    fun getMonthlyPayments(year: Int, month: Int): Flow<List<LessonPayment>>
    suspend fun getMonthlyPaymentsList(year: Int, month: Int): List<LessonPayment>
    suspend fun fetchMonthlyPaymentsResult(year: Int, month: Int): Result<List<LessonPayment>> =
        runCatching { getMonthlyPaymentsList(year, month) }

    fun observePaymentsByStudent(studentId: String): Flow<List<LessonPayment>> = flowOf(emptyList())
    suspend fun getPaymentsForStudentHistory(studentId: String): List<LessonPayment> = emptyList()
    suspend fun fetchPaymentsForStudentHistoryResult(studentId: String): Result<List<LessonPayment>> =
        runCatching { getPaymentsForStudentHistory(studentId) }

    suspend fun getPaymentForStudent(studentId: String, year: Int, month: Int): LessonPayment?
    suspend fun fetchPaymentForStudentResult(studentId: String, year: Int, month: Int): Result<LessonPayment?> =
        runCatching { getPaymentForStudent(studentId, year, month) }

    suspend fun setPaymentStatus(
        studentId: String,
        year: Int,
        month: Int,
        isPaid: Boolean,
        amount: Double = 0.0
    ): Result<LessonPayment>

    suspend fun togglePaymentStatus(
        studentId: String,
        year: Int,
        month: Int,
        amount: Double = 0.0
    ): Result<LessonPayment>
}
