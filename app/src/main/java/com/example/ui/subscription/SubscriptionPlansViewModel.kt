package com.example.ui.subscription

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.SubscriptionPlan
import com.example.core.model.TeacherSubscription
import com.example.core.model.TeacherSubscriptionRequest
import com.example.data.repository.RepositoryProvider
import com.example.data.repository.SubscriptionRepository
import com.example.data.repository.TeacherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SubscriptionPlansUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val plans: List<SubscriptionPlan> = emptyList(),
    val teacherId: String? = null,
    val currentPlanId: String? = null,
    val currentPlan: SubscriptionPlan? = null,
    val subscriptionStatus: String? = null,
    val subscriptionEndDate: String? = null,
    val currentSubscription: TeacherSubscription? = null,
    val pendingRequest: TeacherSubscriptionRequest? = null,
    val requestHistory: List<TeacherSubscriptionRequest> = emptyList(),
    val selectedPlan: SubscriptionPlan? = null,
    val selectedBillingPeriod: String = "monthly", // "monthly" or "annual"
    val payerPhone: String = "",
    val transferReference: String = "",
    val receiptBytes: ByteArray? = null,
    val receiptFileName: String? = null,
    val isSubmitting: Boolean = false,
    val submitSuccessMessage: String? = null,
    val submitErrorMessage: String? = null
) {
    val hasPendingRequest: Boolean get() = pendingRequest != null
}

class SubscriptionPlansViewModel(
    private val subscriptionRepository: SubscriptionRepository = RepositoryProvider.subscriptionRepository,
    private val teacherRepository: TeacherRepository = RepositoryProvider.teacherRepository,
    autoLoad: Boolean = true
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionPlansUiState())
    val uiState: StateFlow<SubscriptionPlansUiState> = _uiState.asStateFlow()

    private var currentIdempotencyKey: String? = null

    init {
        if (autoLoad) {
            loadData()
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val teacher = teacherRepository.fetchCurrentTeacher()
                val teacherId = teacher?.id
                val plans = subscriptionRepository.getPlans()
                    .filter { it.isActive && it.code != "center" }
                    .sortedBy { it.monthlyPrice }

                val currentSub = if (!teacherId.isNullOrBlank()) {
                    try { subscriptionRepository.getCurrentSubscription(teacherId) } catch (_: Exception) { null }
                } else null

                val pendingReq = if (!teacherId.isNullOrBlank()) {
                    try { subscriptionRepository.getPendingRequest(teacherId) } catch (_: Exception) { null }
                } else null

                val history = if (!teacherId.isNullOrBlank()) {
                    try { subscriptionRepository.getRequestHistory(teacherId) } catch (_: Exception) { emptyList() }
                } else emptyList()

                val effectivePlanId = teacher?.planId ?: currentSub?.planId
                val currentPlan = plans.find { it.id == effectivePlanId }
                    ?: plans.find { it.code == "free" }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        plans = plans,
                        teacherId = teacherId,
                        currentPlanId = effectivePlanId,
                        currentPlan = currentPlan,
                        subscriptionStatus = teacher?.subscriptionStatus,
                        subscriptionEndDate = teacher?.subscriptionEndDate ?: currentSub?.endsAt,
                        currentSubscription = currentSub,
                        pendingRequest = pendingReq,
                        requestHistory = history
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "تعذر تحميل بيانات الباقات. يرجى التحقق من الاتصال بالإنترنت."
                    )
                }
            }
        }
    }

    fun selectPlan(plan: SubscriptionPlan) {
        currentIdempotencyKey = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                selectedPlan = plan,
                selectedBillingPeriod = if (plan.code == "free") "monthly" else it.selectedBillingPeriod,
                submitErrorMessage = null,
                submitSuccessMessage = null
            )
        }
    }

    fun dismissPlanSelection() {
        _uiState.update {
            it.copy(
                selectedPlan = null,
                payerPhone = "",
                transferReference = "",
                receiptBytes = null,
                receiptFileName = null,
                submitErrorMessage = null
            )
        }
    }

    fun selectBillingPeriod(period: String) {
        if (period == "monthly" || period == "annual") {
            currentIdempotencyKey = UUID.randomUUID().toString()
            _uiState.update { it.copy(selectedBillingPeriod = period, submitErrorMessage = null) }
        }
    }

    fun updatePayerPhone(phone: String) {
        _uiState.update { it.copy(payerPhone = phone, submitErrorMessage = null) }
    }

    fun updateTransferReference(reference: String) {
        _uiState.update { it.copy(transferReference = reference, submitErrorMessage = null) }
    }

    fun setReceipt(bytes: ByteArray, fileName: String) {
        _uiState.update {
            it.copy(
                receiptBytes = bytes,
                receiptFileName = fileName,
                submitErrorMessage = null
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(submitSuccessMessage = null, submitErrorMessage = null) }
    }

    fun submitRequest() {
        val state = _uiState.value
        val plan = state.selectedPlan ?: return

        if (state.hasPendingRequest) {
            _uiState.update {
                it.copy(submitErrorMessage = "يوجد لديك طلب اشتراك قيد المراجعة بالفعل.")
            }
            return
        }

        val isFree = plan.code == "free"
        if (!isFree) {
            if (state.payerPhone.isBlank()) {
                _uiState.update { it.copy(submitErrorMessage = "يرجى إدخال رقم هاتف فودافون كاش المحول منه.") }
                return
            }
            if (state.transferReference.isBlank()) {
                _uiState.update { it.copy(submitErrorMessage = "يرجى إدخال رقم العملية أو المرجع.") }
                return
            }
            if (state.receiptBytes == null || state.receiptFileName.isNullOrBlank()) {
                _uiState.update { it.copy(submitErrorMessage = "يرجى إرفاق صورة إيصال الدفع.") }
                return
            }
        }

        val teacherId = state.teacherId
        if (teacherId.isNullOrBlank()) {
            _uiState.update { it.copy(submitErrorMessage = "تعذر تحديد حساب المعلم. يرجى إعادة تسجيل الدخول.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, submitErrorMessage = null, submitSuccessMessage = null) }

            val receiptPath = if (isFree) {
                ""
            } else {
                val uploadResult = subscriptionRepository.uploadReceipt(
                    bytes = state.receiptBytes!!,
                    fileName = "${System.currentTimeMillis()}_${state.receiptFileName}",
                    teacherId = teacherId
                )
                if (uploadResult.isFailure) {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            submitErrorMessage = uploadResult.exceptionOrNull()?.message ?: "فشل رفع إيصال الدفع."
                        )
                    }
                    return@launch
                }
                uploadResult.getOrThrow()
            }

            val idempotencyKey = currentIdempotencyKey ?: UUID.randomUUID().toString().also {
                currentIdempotencyKey = it
            }

            val billingPeriod = if (isFree) "monthly" else state.selectedBillingPeriod

            val result = subscriptionRepository.submitSubscriptionRequest(
                planId = plan.id,
                billingPeriod = billingPeriod,
                paymentMethod = "vodafone_cash",
                payerPhone = if (isFree) "" else state.payerPhone.trim(),
                transferReference = if (isFree) "" else state.transferReference.trim(),
                receiptPath = receiptPath,
                idempotencyKey = idempotencyKey
            )

            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        selectedPlan = null,
                        payerPhone = "",
                        transferReference = "",
                        receiptBytes = null,
                        receiptFileName = null,
                        submitSuccessMessage = "تم إرسال طلب الاشتراك بنجاح وهو قيد المراجعة من الإدارة."
                    )
                }
                loadData()
            } else {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        submitErrorMessage = result.exceptionOrNull()?.message ?: "فشل إرسال طلب الاشتراك."
                    )
                }
            }
        }
    }
}
