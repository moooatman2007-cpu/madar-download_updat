package com.example.data.repository

import com.example.core.model.SubscriptionPlan
import com.example.core.model.TeacherSubscription
import com.example.core.model.TeacherSubscriptionRequest
import com.example.data.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SupabaseSubscriptionRepository : SubscriptionRepository {

    private val client = SupabaseClientProvider.client
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun getPlans(): List<SubscriptionPlan> = withContext(Dispatchers.IO) {
        client.postgrest["subscription_plans"]
            .select {
                filter {
                    eq("is_active", true)
                    neq("code", "center")
                }
            }
            .decodeList<SubscriptionPlan>()
    }

    override suspend fun getCurrentSubscription(teacherId: String): TeacherSubscription? =
        withContext(Dispatchers.IO) {
            client.postgrest["teacher_subscriptions"]
                .select {
                    filter {
                        eq("teacher_id", teacherId)
                    }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1)
                }
                .decodeSingleOrNull<TeacherSubscription>()
        }

    override suspend fun getPendingRequest(teacherId: String): TeacherSubscriptionRequest? =
        withContext(Dispatchers.IO) {
            client.postgrest["teacher_subscription_requests"]
                .select {
                    filter {
                        eq("teacher_id", teacherId)
                        eq("status", "pending")
                    }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1)
                }
                .decodeSingleOrNull<TeacherSubscriptionRequest>()
        }

    override suspend fun getRequestHistory(teacherId: String): List<TeacherSubscriptionRequest> =
        withContext(Dispatchers.IO) {
            client.postgrest["teacher_subscription_requests"]
                .select {
                    filter {
                        eq("teacher_id", teacherId)
                    }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                }
                .decodeList<TeacherSubscriptionRequest>()
        }

    override suspend fun uploadReceipt(
        bytes: ByteArray,
        fileName: String,
        teacherId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val bucket = client.storage.from("subscription-receipts")
            val filePath = "$teacherId/$fileName"
            bucket.upload(filePath, bytes) {
                upsert = true
            }
            Result.success(filePath)
        } catch (e: Exception) {
            val rawMsg = e.localizedMessage ?: e.message ?: e.toString()
            Result.failure(Exception("فشل رفع الإيصال: $rawMsg"))
        }
    }

    @Serializable
    private data class SubscriptionRpcParams(
        @SerialName("p_plan_id") val planId: String,
        @SerialName("p_billing_period") val billingPeriod: String,
        @SerialName("p_payment_method") val paymentMethod: String,
        @SerialName("p_payer_phone") val payerPhone: String,
        @SerialName("p_transfer_reference") val transferReference: String,
        @SerialName("p_receipt_path") val receiptPath: String,
        @SerialName("p_idempotency_key") val idempotencyKey: String
    )

    override suspend fun submitSubscriptionRequest(
        planId: String,
        billingPeriod: String,
        paymentMethod: String,
        payerPhone: String,
        transferReference: String,
        receiptPath: String,
        idempotencyKey: String
    ): Result<SubmitSubscriptionResult> = withContext(Dispatchers.IO) {
        try {
            val params = SubscriptionRpcParams(
                planId = planId,
                billingPeriod = billingPeriod,
                paymentMethod = paymentMethod,
                payerPhone = payerPhone,
                transferReference = transferReference,
                receiptPath = receiptPath,
                idempotencyKey = idempotencyKey
            )
            val response = client.postgrest.rpc(
                function = "submit_teacher_subscription_request",
                parameters = params
            )
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
        } catch (e: Exception) {
            val rawMsg = e.localizedMessage ?: e.message ?: e.toString()
            // Extract meaningful error messages from Supabase/PostgreSQL exceptions
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
                    "لم يتم العثور على إيصال الدفع. يرجى رفع الإيصال مجدداً."
                rawMsg.contains("teacher_in_organization", ignoreCase = true) ->
                    "أنت مسجل في مؤسسة. يرجى استخدام نظام اشتراكات المؤسسة."
                rawMsg.contains("idempotency", ignoreCase = true) ->
                    "بيانات الطلب تختلف عن الطلب السابق بنفس المعرف."
                else -> "فشل إرسال طلب الاشتراك: $rawMsg"
            }
            Result.failure(Exception(userMsg))
        }
    }
}
