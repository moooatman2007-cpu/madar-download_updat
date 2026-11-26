package com.example.data.repository

import com.example.core.model.SubscriptionPlan
import com.example.core.model.TeacherSubscription
import com.example.core.model.TeacherSubscriptionRequest
import com.example.data.SupabaseClientProvider
import com.example.data.auth.AccountSessionManager
import com.example.util.ReceiptFileProcessor
import com.example.util.isOfflineOrNetworkError
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SupabaseSubscriptionRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client,
    private val teacherIdProvider: () -> String? = {
        SupabaseClientProvider.mockTeacherId
            ?: try { client.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
    },
    private val sessionActiveChecker: (String) -> Boolean = { teacherId ->
        AccountSessionManager.isSessionActive(teacherId)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uploadTimeoutMs: Long = 45_000L
) : SubscriptionRepository {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resolveActiveTeacherId(): String? {
        val teacherId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        return if (sessionActiveChecker(teacherId)) teacherId else null
    }

    private fun resolveAuthorizedTeacherId(requestedTeacherId: String): String? {
        if (requestedTeacherId.isBlank()) return null
        val activeId = teacherIdProvider()?.takeIf { it.isNotBlank() } ?: return null
        if (activeId != requestedTeacherId) return null
        return if (sessionActiveChecker(activeId)) activeId else null
    }

    private fun isStillAuthorized(teacherId: String): Boolean =
        sessionActiveChecker(teacherId) && teacherIdProvider() == teacherId

    override suspend fun getPlans(): List<SubscriptionPlan> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: throw IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً.")

        val plans = client.postgrest["subscription_plans"]
            .select {
                filter {
                    eq("is_active", true)
                    neq("code", "center")
                }
            }
            .decodeList<SubscriptionPlan>()

        if (!isStillAuthorized(teacherId)) {
            throw IllegalStateException("انتهت الجلسة أثناء جلب الباقات.")
        }
        plans
    }

    override suspend fun getCurrentSubscription(teacherId: String): TeacherSubscription? =
        withContext(ioDispatcher) {
            val authorizedId = resolveAuthorizedTeacherId(teacherId)
                ?: throw IllegalStateException("غير مصرح أو انتهت الجلسة.")

            val sub = client.postgrest["teacher_subscriptions"]
                .select {
                    filter {
                        eq("teacher_id", authorizedId)
                    }
                    order("created_at", Order.DESCENDING)
                    limit(1)
                }
                .decodeSingleOrNull<TeacherSubscription>()

            if (!isStillAuthorized(authorizedId)) {
                throw IllegalStateException("انتهت الجلسة أثناء جلب حالة الاشتراك.")
            }
            sub
        }

    override suspend fun getPendingRequest(teacherId: String): TeacherSubscriptionRequest? =
        withContext(ioDispatcher) {
            val authorizedId = resolveAuthorizedTeacherId(teacherId)
                ?: throw IllegalStateException("غير مصرح أو انتهت الجلسة.")

            val req = client.postgrest["teacher_subscription_requests"]
                .select {
                    filter {
                        eq("teacher_id", authorizedId)
                        eq("status", "pending")
                    }
                    order("created_at", Order.DESCENDING)
                    limit(1)
                }
                .decodeSingleOrNull<TeacherSubscriptionRequest>()

            if (!isStillAuthorized(authorizedId)) {
                throw IllegalStateException("انتهت الجلسة أثناء جلب طلب الاشتراك.")
            }
            req
        }

    override suspend fun getRequestHistory(teacherId: String): List<TeacherSubscriptionRequest> =
        withContext(ioDispatcher) {
            val authorizedId = resolveAuthorizedTeacherId(teacherId)
                ?: throw IllegalStateException("غير مصرح أو انتهت الجلسة.")

            val history = client.postgrest["teacher_subscription_requests"]
                .select {
                    filter {
                        eq("teacher_id", authorizedId)
                    }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<TeacherSubscriptionRequest>()

            if (!isStillAuthorized(authorizedId)) {
                throw IllegalStateException("انتهت الجلسة أثناء جلب سجل طلبات الاشتراك.")
            }
            history
        }

    override suspend fun uploadReceipt(
        bytes: ByteArray,
        fileName: String,
        teacherId: String,
        mimeType: String
    ): Result<String> = withContext(ioDispatcher) {
        val authorizedId = resolveAuthorizedTeacherId(teacherId)
            ?: return@withContext Result.failure(IllegalStateException("غير مصرح أو انتهت الجلسة."))

        if (bytes.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("الملف المختار فارغ أو تالف."))
        }
        if (bytes.size > ReceiptFileProcessor.MAX_UPLOAD_BYTES) {
            return@withContext Result.failure(IllegalArgumentException("حجم الإيصال يتجاوز الحد المسموح به (5 ميجابايت)."))
        }

        val normalizedMime = mimeType.lowercase().trim()
        val resolvedContentType = when (normalizedMime) {
            "image/jpeg", "image/jpg" -> ContentType.Image.JPEG
            "image/png" -> ContentType.Image.PNG
            "image/webp" -> ContentType.parse("image/webp")
            "application/pdf" -> ContentType.Application.Pdf
            else -> return@withContext Result.failure(
                IllegalArgumentException("نوع الملف غير مدعوم. يرجى اختيار صورة (JPG/PNG/WEBP) أو ملف PDF.")
            )
        }

        val expectedExt = when (normalizedMime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "application/pdf" -> "pdf"
            else -> "jpg"
        }
        val cleanName = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .let { base ->
                if (base.isBlank()) {
                    ReceiptFileProcessor.buildSafeStorageFileName(expectedExt)
                } else if (base.contains('.')) {
                    base
                } else {
                    "$base.$expectedExt"
                }
            }

        try {
            val bucket = client.storage.from("subscription-receipts")
            val filePath = "$authorizedId/$cleanName"
            withTimeout(uploadTimeoutMs) {
                bucket.upload(filePath, bytes) {
                    // Use unique object paths with upsert = false so private buckets that
                    // only grant INSERT (and not UPDATE/SELECT) to teachers succeed.
                    upsert = false
                    contentType = resolvedContentType
                }
            }
            if (!isStillAuthorized(authorizedId)) {
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء رفع الإيصال."))
            }
            Result.success(filePath)
        } catch (_: TimeoutCancellationException) {
            Result.failure(Exception("انتهت مهلة رفع الإيصال. يرجى التحقق من سرعة الإنترنت والمحاولة مرة أخرى."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val userMsg = mapUploadExceptionToArabic(e)
            Result.failure(Exception(userMsg, e))
        }
    }

    override suspend fun deleteReceipt(receiptPath: String): Result<Unit> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة."))
        val normalized = receiptPath.trim()
        if (normalized.isBlank() || !normalized.startsWith("$teacherId/")) {
            return@withContext Result.success(Unit)
        }
        return@withContext try {
            client.storage.from("subscription-receipts").delete(normalized)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Private receipt buckets may disallow DELETE via RLS; do not block replacing the file.
            Result.success(Unit)
        }
    }

    @Serializable
    private data class SubscriptionRpcParams(
        @SerialName("p_plan_id") val planId: String,
        @SerialName("p_billing_period") val billingPeriod: String,
        @SerialName("p_payment_method") val paymentMethod: String,
        @SerialName("p_payer_phone") val payerPhone: String,
        // No default value so kotlinx.serialization explicitly sends `"p_transfer_reference": null`
        @SerialName("p_transfer_reference") val transferReference: String?,
        @SerialName("p_receipt_path") val receiptPath: String,
        @SerialName("p_idempotency_key") val idempotencyKey: String
    )

    override suspend fun submitSubscriptionRequest(
        planId: String,
        billingPeriod: String,
        paymentMethod: String,
        payerPhone: String,
        transferReference: String?,
        receiptPath: String,
        idempotencyKey: String
    ): Result<SubmitSubscriptionResult> = withContext(ioDispatcher) {
        val teacherId = resolveActiveTeacherId()
            ?: return@withContext Result.failure(IllegalStateException("انتهت الجلسة، يرجى تسجيل الدخول أولاً."))

        try {
            val normalizedRef = transferReference?.trim()?.ifBlank { null }
            val params = SubscriptionRpcParams(
                planId = planId,
                billingPeriod = billingPeriod,
                paymentMethod = paymentMethod,
                payerPhone = payerPhone,
                transferReference = normalizedRef,
                receiptPath = receiptPath,
                idempotencyKey = idempotencyKey
            )
            val response = client.postgrest.rpc(
                function = "submit_teacher_subscription_request",
                parameters = params
            )
            if (!isStillAuthorized(teacherId)) {
                return@withContext Result.failure(IllegalStateException("انتهت الجلسة أثناء إرسال طلب الاشتراك."))
            }
            val responseText = response.data
            val jsonObj = json.parseToJsonElement(responseText).jsonObject
            val success = jsonObj["success"]?.jsonPrimitive?.boolean ?: false
            val requestId = jsonObj["request_id"]?.jsonPrimitive?.content
            val status = jsonObj["status"]?.jsonPrimitive?.content
            val durationDays = try {
                jsonObj["duration_days"]?.jsonPrimitive?.int
            } catch (_: Exception) { null }
            val price = try {
                jsonObj["price"]?.jsonPrimitive?.double
            } catch (_: Exception) { null }
            val idempotentReplay = try {
                jsonObj["idempotent_replay"]?.jsonPrimitive?.boolean ?: false
            } catch (_: Exception) { false }

            Result.success(
                SubmitSubscriptionResult(
                    success = success,
                    requestId = requestId,
                    status = status,
                    durationDays = durationDays,
                    price = price,
                    idempotentReplay = idempotentReplay
                )
            )
        } catch (_: TimeoutCancellationException) {
            Result.failure(Exception("انتهت مهلة الاتصال بالخادم أثناء إرسال الطلب. يرجى إعادة المحاولة."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val rawMsg = e.message.orEmpty()
            val userMsg = when {
                rawMsg.contains("pending_request_exists", ignoreCase = true) ->
                    "يوجد لديك طلب اشتراك قيد المراجعة بالفعل."
                rawMsg.contains("plan_not_found", ignoreCase = true) ->
                    "الباقة المطلوبة غير متاحة."
                rawMsg.contains("center_plan_not_allowed", ignoreCase = true) ->
                    "باقة المركز غير متاحة للمدرس المستقل."
                rawMsg.contains("teacher_not_active", ignoreCase = true) ->
                    "حسابك غير نشط. يرجى التواصل مع الدعم."
                rawMsg.contains("receipt_not_found", ignoreCase = true) ->
                    "لم يتم العثور على إيصال الدفع في الخادم. يرجى رفع الإيصال مجدداً."
                rawMsg.contains("teacher_in_organization", ignoreCase = true) ->
                    "أنت مسجل في مؤسسة. يرجى استخدام نظام اشتراكات المؤسسة."
                rawMsg.contains("idempotency", ignoreCase = true) ->
                    "بيانات الطلب تختلف عن الطلب السابق بنفس المعرف."
                rawMsg.contains("transfer_reference", ignoreCase = true) ||
                    rawMsg.contains("23502", ignoreCase = true) ->
                    "الخادم يطلب رقم العملية حاليًا، يرجى تحديث قاعدة البيانات لدعم تركه اختيارياً."
                e.isOfflineOrNetworkError() ->
                    "لا يوجد اتصال بالإنترنت، تعذر إرسال طلب الاشتراك. يرجى المحاولة عند عودة الاتصال."
                else -> "فشل إرسال طلب الاشتراك. يرجى المحاولة مرة أخرى."
            }
            Result.failure(Exception(userMsg))
        }
    }

    private fun mapUploadExceptionToArabic(e: Throwable): String {
        if (e.isOfflineOrNetworkError()) {
            return "لا يوجد اتصال بالإنترنت، تعذر رفع الإيصال. يرجى التحقق من الشبكة والمحاولة مرة أخرى."
        }
        val msg = "${e.message.orEmpty()} ${e.localizedMessage.orEmpty()}".lowercase()
        return when {
            msg.contains("payload too large") || msg.contains("413") || msg.contains("maximum allowed size") ->
                "حجم الإيصال يتجاوز الحد المسموح به على الخادم."
            msg.contains("mime") || msg.contains("415") || msg.contains("unsupported") ->
                "صيغة الملف غير مدعومة في الخادم. يرجى اختيار صورة JPG/PNG/WEBP أو ملف PDF."
            msg.contains("row-level security") || msg.contains("unauthorized") || msg.contains("403") || msg.contains("401") ->
                "غير مصرح برفع الإيصال. يرجى إعادة تسجيل الدخول والمحاولة مرة أخرى."
            msg.contains("bucket not found") || msg.contains("404") ->
                "مسار حفظ الإيصالات غير متاح حالياً على الخادم."
            else ->
                "تعذر رفع الإيصال إلى الخادم. يرجى المحاولة مرة أخرى."
        }
    }
}
