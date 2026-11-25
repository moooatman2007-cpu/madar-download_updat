package com.example.data.repository

import com.example.core.model.SubscriptionPlan
import com.example.core.model.TeacherSubscription
import com.example.core.model.TeacherSubscriptionRequest

/**
 * Result of calling the submit_teacher_subscription_request RPC.
 */
data class SubmitSubscriptionResult(
    val success: Boolean,
    val requestId: String? = null,
    val status: String? = null,
    val durationDays: Int? = null,
    val price: Double? = null,
    val idempotentReplay: Boolean = false
)

interface SubscriptionRepository {

    /**
     * Fetch active subscription plans from `subscription_plans` table.
     * Excludes `center` plan (for independent teachers) and inactive plans.
     */
    suspend fun getPlans(): List<SubscriptionPlan>

    /**
     * Get the teacher's current active subscription (if any) from `teacher_subscriptions`.
     */
    suspend fun getCurrentSubscription(teacherId: String): TeacherSubscription?

    /**
     * Check if the teacher has a pending subscription request.
     */
    suspend fun getPendingRequest(teacherId: String): TeacherSubscriptionRequest?

    /**
     * Fetch all subscription requests for the teacher (history).
     */
    suspend fun getRequestHistory(teacherId: String): List<TeacherSubscriptionRequest>

    /**
     * Upload a payment receipt image to the `subscription-receipts` bucket.
     * Returns the storage path on success.
     */
    suspend fun uploadReceipt(bytes: ByteArray, fileName: String, teacherId: String): Result<String>

    /**
     * Submit a subscription request via the `submit_teacher_subscription_request` RPC.
     */
    suspend fun submitSubscriptionRequest(
        planId: String,
        billingPeriod: String,
        paymentMethod: String,
        payerPhone: String,
        transferReference: String,
        receiptPath: String,
        idempotencyKey: String
    ): Result<SubmitSubscriptionResult>
}
