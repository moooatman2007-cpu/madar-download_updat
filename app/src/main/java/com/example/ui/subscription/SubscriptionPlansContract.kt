package com.example.ui.subscription

import com.example.core.config.PaymentSupportConfig
import com.example.core.model.SubscriptionPlan
import com.example.core.model.TeacherSubscription
import com.example.core.model.TeacherSubscriptionRequest

sealed interface ReceiptUploadState {
    data object Idle : ReceiptUploadState

    data class Uploading(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val cacheFilePath: String? = null
    ) : ReceiptUploadState

    data class Uploaded(
        val displayName: String,
        val storageFileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val storagePath: String,
        val cacheFilePath: String? = null
    ) : ReceiptUploadState

    data class Failed(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val errorMessage: String,
        val cacheFilePath: String? = null
    ) : ReceiptUploadState
}

data class SubmittedSubscriptionConfirmation(
    val requestId: String?,
    val planName: String,
    val billingPeriod: String,
    val durationDays: Int,
    val price: Double,
    val isFree: Boolean,
    val payerPhone: String,
    val transferReference: String?,
    val teacherName: String,
    val teacherRegisteredPhone: String?,
    val receiptCachePath: String?,
    val receiptMimeType: String?,
    val receiptFileName: String?
)

data class SubscriptionPlansUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val transferNumber: String = PaymentSupportConfig.TRANSFER_NUMBER,
    val plans: List<SubscriptionPlan> = emptyList(),
    val teacherId: String? = null,
    val teacherName: String = "",
    val teacherPhone: String? = null,
    val currentPlanId: String? = null,
    val currentPlan: SubscriptionPlan? = null,
    val subscriptionStatus: String? = null,
    val subscriptionEndDate: String? = null,
    val currentSubscription: TeacherSubscription? = null,
    val pendingRequest: TeacherSubscriptionRequest? = null,
    val requestHistory: List<TeacherSubscriptionRequest> = emptyList(),
    val selectedPlan: SubscriptionPlan? = null,
    val selectedBillingPeriod: String = "monthly",
    val payerPhone: String = "",
    val transferReference: String = "",
    val receiptUploadState: ReceiptUploadState = ReceiptUploadState.Idle,
    val isSubmitting: Boolean = false,
    val submitErrorMessage: String? = null,
    val submittedConfirmation: SubmittedSubscriptionConfirmation? = null
) {
    val hasPendingRequest: Boolean get() = pendingRequest != null

    val isSelectedPlanFree: Boolean
        get() = selectedPlan?.let { it.code == "free" || it.monthlyPrice <= 0.0 } ?: false

    val selectedPlanPrice: Double?
        get() = selectedPlan?.let { plan ->
            if (plan.code == "free" || plan.monthlyPrice <= 0.0) {
                0.0
            } else if (selectedBillingPeriod == "annual") {
                plan.monthlyPrice * 10.0
            } else {
                plan.monthlyPrice
            }
        }

    val selectedPlanDurationDays: Int?
        get() = selectedPlan?.let {
            if (selectedBillingPeriod == "annual") 365 else 30
        }

    val isReceiptUploaded: Boolean
        get() = receiptUploadState is ReceiptUploadState.Uploaded

    val canSubmitRequest: Boolean
        get() = selectedPlan != null &&
            !hasPendingRequest &&
            !isSubmitting &&
            (isSelectedPlanFree || isReceiptUploaded)
}
