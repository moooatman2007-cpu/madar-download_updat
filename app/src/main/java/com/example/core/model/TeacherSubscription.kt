package com.example.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TeacherSubscription(
    @SerialName("id") val id: String = "",
    @SerialName("teacher_id") val teacherId: String = "",
    @SerialName("plan_id") val planId: String = "",
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("ends_at") val endsAt: String = "",
    @SerialName("billing_period") val billingPeriod: String = "",
    @SerialName("price_paid") val pricePaid: Double = 0.0,
    @SerialName("student_limit_snapshot") val studentLimitSnapshot: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("request_id") val requestId: String? = null
)
