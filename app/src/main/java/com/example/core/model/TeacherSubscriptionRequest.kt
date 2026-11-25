package com.example.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TeacherSubscriptionRequest(
    @SerialName("id") val id: String = "",
    @SerialName("teacher_id") val teacherId: String = "",
    @SerialName("plan_id") val planId: String = "",
    @SerialName("requested_duration_days") val requestedDurationDays: Int = 0,
    @SerialName("price_at_request") val priceAtRequest: Double = 0.0,
    @SerialName("billing_period") val billingPeriod: String = "",
    @SerialName("payment_method") val paymentMethod: String = "",
    @SerialName("payer_phone") val payerPhone: String? = null,
    @SerialName("transfer_reference") val transferReference: String? = null,
    @SerialName("receipt_path") val receiptPath: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
    @SerialName("status") val status: String = "pending",
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("admin_note") val adminNote: String? = null,
    @SerialName("created_at") val createdAt: String = ""
)
