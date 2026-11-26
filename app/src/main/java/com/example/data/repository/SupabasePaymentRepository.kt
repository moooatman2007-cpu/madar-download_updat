package com.example.data.repository

import android.util.Log
import com.example.core.model.LessonPayment
import com.example.core.model.SupabaseLessonPaymentDto
import com.example.core.model.UpsertLessonPaymentRequest
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
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime

interface PaymentRemoteDataSource {
    suspend fun fetchMonthlyPayments(teacherId: String, year: Int, month: Int): List<LessonPayment>
    suspend fun fetchPaymentsForStudent(teacherId: String, studentId: String): List<LessonPayment>
    suspend fun fetchPaymentForStudent(teacherId: String, studentId: String, year: Int, month: Int): LessonPayment?
    suspend fun upsertPayment(request: UpsertLessonPaymentRequest): LessonPayment
}

class SupabasePaymentRemoteDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : PaymentRemoteDataSource {

    override suspend fun fetchMonthlyPayments(teacherId: String, year: Int, month: Int): List<LessonPayment> {
        return client.postgrest["lesson_payments"]
            .select {
                filter {
                    eq("teacher_id", teacherId)
                    eq("year", year)
                    eq("month", month)
                }
            }
            .decodeList<SupabaseLessonPaymentDto>()
            .map { it.toLessonPayment() }
    }

    override suspend fun fetchPaymentsForStudent(teacherId: String, studentId: String): List<LessonPayment> {
        return client.postgrest["lesson_payments"]
            .select {
                filter {
                    eq("teacher_id", teacherId)
                    eq("student_id", studentId)
                }
                order("year", order = Order.DESCENDING)
                order("month", order = Order.DESCENDING)
            }
            .decodeList<SupabaseLessonPaymentDto>()
            .map { it.toLessonPayment() }
    }

    override suspend fun fetchPaymentForStudent(
        teacherId: String,
        studentId: String,
        year: Int,
        month: Int
    ): LessonPayment? {
        return client.postgrest["lesson_payments"]
            .select {
                filter {
                    eq("teacher_id", teacherId)
                    eq("student_id", studentId)
                    eq("year", year)
                    eq("month", month)
                }
            }
            .decodeSingleOrNull<SupabaseLessonPaymentDto>()
            ?.toLessonPayment()
    }

    override suspend fun upsertPayment(request: UpsertLessonPaymentRequest): LessonPayment {
        return client.postgrest["lesson_payments"].upsert(request) {
            onConflict = "student_id,year,month"
            select()
        }.decodeSingle<SupabaseLessonPaymentDto>().toLessonPayment()
    }
}

class SupabasePaymentRepository(
    private val remoteDataSource: PaymentRemoteDataSource = SupabasePaymentRemoteDataSource(),
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { SupabaseClientProvider.client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PaymentRepository {

    private val _sessionPayments = MutableStateFlow<Pair<String?, List<LessonPayment>>>(null to emptyList())

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    fun clearCache() {
        _sessionPayments.value = null to emptyList()
    }

    private fun paymentKey(p: LessonPayment): String = "${p.studentId}_${p.year}_${p.month}"

    private fun replaceScopedPaymentsInMemory(
        teacherId: String,
        fetched: List<LessonPayment>,
        predicateToReplace: (LessonPayment) -> Boolean
    ) {
        val (owner, current) = _sessionPayments.value
        val base = if (owner == teacherId) current.filterNot(predicateToReplace) else emptyList()
        val combined = (fetched + base).distinctBy(::paymentKey)
            .sortedWith(compareByDescending<LessonPayment> { it.year }.thenByDescending { it.month })
        _sessionPayments.value = teacherId to combined
    }

    private fun upsertPaymentInMemory(teacherId: String, payment: LessonPayment) {
        val (owner, current) = _sessionPayments.value
        val base = if (owner == teacherId) current else emptyList()
        val key = paymentKey(payment)
        _sessionPayments.value = teacherId to (listOf(payment) + base.filterNot { paymentKey(it) == key })
            .sortedWith(compareByDescending<LessonPayment> { it.year }.thenByDescending { it.month })
    }

    override fun getMonthlyPayments(year: Int, month: Int): Flow<List<LessonPayment>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            fetchMonthlyPaymentsResult(year, month)
        }

        _sessionPayments
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.year == year && it.month == month }
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun fetchMonthlyPaymentsResult(year: Int, month: Int): Result<List<LessonPayment>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val list = remoteDataSource.fetchMonthlyPayments(teacherId, year, month)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب المدفوعات."))
            }
            replaceScopedPaymentsInMemory(teacherId, list) { it.year == year && it.month == month }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabasePaymentRepo", "fetchMonthlyPaymentsResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getMonthlyPaymentsList(year: Int, month: Int): List<LessonPayment> {
        return fetchMonthlyPaymentsResult(year, month).getOrDefault(emptyList())
    }

    override fun observePaymentsByStudent(studentId: String): Flow<List<LessonPayment>> = channelFlow {
        val teacherId = resolveActiveTeacherId()
        if (teacherId == null) {
            clearCache()
            send(emptyList())
            return@channelFlow
        }

        launch(ioDispatcher) {
            fetchPaymentsForStudentHistoryResult(studentId)
        }

        _sessionPayments
            .map { (owner, list) ->
                val current = resolveActiveTeacherId()
                if (current == null || owner != current) emptyList()
                else list.filter { it.studentId == studentId }
                    .sortedWith(compareByDescending<LessonPayment> { it.year }.thenByDescending { it.month })
            }
            .collect { send(it) }
    }.flowOn(ioDispatcher)

    override suspend fun fetchPaymentsForStudentHistoryResult(studentId: String): Result<List<LessonPayment>> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val remoteList = remoteDataSource.fetchPaymentsForStudent(teacherId, studentId)
                .sortedWith(compareByDescending<LessonPayment> { it.year }.thenByDescending { it.month })
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب سجل المدفوعات."))
            }
            replaceScopedPaymentsInMemory(teacherId, remoteList) { it.studentId == studentId }
            Result.success(remoteList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabasePaymentRepo", "fetchPaymentsForStudentHistoryResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getPaymentsForStudentHistory(studentId: String): List<LessonPayment> {
        return fetchPaymentsForStudentHistoryResult(studentId).getOrDefault(emptyList())
    }

    override suspend fun fetchPaymentForStudentResult(
        studentId: String,
        year: Int,
        month: Int
    ): Result<LessonPayment?> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val payment = remoteDataSource.fetchPaymentForStudent(teacherId, studentId, year, month)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء جلب حالة الدفع."))
            }
            if (payment != null) {
                upsertPaymentInMemory(teacherId, payment)
            }
            Result.success(payment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabasePaymentRepo", "fetchPaymentForStudentResult failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getPaymentForStudent(studentId: String, year: Int, month: Int): LessonPayment? {
        return fetchPaymentForStudentResult(studentId, year, month).getOrNull()
    }

    override suspend fun setPaymentStatus(
        studentId: String,
        year: Int,
        month: Int,
        isPaid: Boolean,
        amount: Double
    ): Result<LessonPayment> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول مجددًا."))

        try {
            val paidAtIso = if (isPaid) OffsetDateTime.now().toString() else null
            val upsertDto = UpsertLessonPaymentRequest(
                teacherId = teacherId,
                studentId = studentId,
                year = year,
                month = month,
                amount = amount,
                isPaid = isPaid,
                paidAt = paidAtIso
            )

            val payment = remoteDataSource.upsertPayment(upsertDto)
            if (!isStillAuthorized(teacherId)) {
                clearCache()
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء حفظ حالة الدفع."))
            }
            // Update session memory ONLY after server confirmation
            upsertPaymentInMemory(teacherId, payment)
            Result.success(payment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SupabasePaymentRepo", "setPaymentStatus failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun togglePaymentStatus(
        studentId: String,
        year: Int,
        month: Int,
        amount: Double
    ): Result<LessonPayment> = withContext(ioDispatcher) {
        val currentResult = fetchPaymentForStudentResult(studentId, year, month)
        if (currentResult.isFailure) {
            return@withContext Result.failure(currentResult.exceptionOrNull() ?: Exception("تعذر التحقق من حالة الدفع."))
        }
        val currentPayment = currentResult.getOrNull()
        val newStatus = !(currentPayment?.isPaid ?: false)
        val currentAmount = if (amount > 0) amount else (currentPayment?.amount ?: 0.0)
        setPaymentStatus(studentId, year, month, newStatus, currentAmount)
    }
}
