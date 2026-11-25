package com.example.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionPlan(
    @SerialName("id") val id: String = "",
    @SerialName("code") val code: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("monthly_price") val monthlyPrice: Double = 0.0,
    @SerialName("student_limit") val studentLimit: Int = 0,
    @SerialName("features") val features: List<String> = emptyList(),
    @SerialName("is_active") val isActive: Boolean = true
)
