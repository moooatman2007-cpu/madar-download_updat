package com.example.ui.subscription

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.model.SubscriptionPlan
import com.example.data.repository.RepositoryProvider
import com.example.data.repository.SubscriptionRepository
import com.example.data.repository.TeacherRepository
import com.example.util.AppDispatchers
import com.example.util.PhoneUtil
import com.example.util.ProcessedReceipt
import com.example.util.ReceiptFileProcessor
import com.example.util.toArabicErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class SubscriptionPlansViewModel(
    private val subscriptionRepository: SubscriptionRepository = RepositoryProvider.subscriptionRepository,
    private val teacherRepository: TeacherRepository = RepositoryProvider.teacherRepository,
    autoLoad: Boolean = true,
    private val ioDispatcher: CoroutineDispatcher = AppDispatchers.io
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionPlansUiState())
    val uiState: StateFlow<SubscriptionPlansUiState> = _uiState.asStateFlow()

    private var currentIdempotencyKey: String? = null
    private var receiptUploadJob: Job? = null
    private var pendingReceipt: ProcessedReceipt? = null

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
                        teacherName = teacher?.fullName.orEmpty(),
                        teacherPhone = teacher?.phoneNumber,
                        currentPlanId = effectivePlanId,
                        currentPlan = currentPlan,
                        subscriptionStatus = teacher?.subscriptionStatus,
                        subscriptionEndDate = teacher?.subscriptionEndDate ?: currentSub?.endsAt,
                        currentSubscription = currentSub,
                        pendingRequest = pendingReq,
                        requestHistory = history
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.toArabicErrorMessage()
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
                selectedBillingPeriod = if (plan.code == "free" || plan.monthlyPrice <= 0.0) "monthly" else it.selectedBillingPeriod,
                submitErrorMessage = null
            )
        }
    }

    fun dismissPlanSelection() {
        clearReceiptInternal(deleteRemote = true)
        _uiState.update {
            it.copy(
                selectedPlan = null,
                payerPhone = "",
                transferReference = "",
                receiptUploadState = ReceiptUploadState.Idle,
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

    fun onReceiptUriPicked(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        receiptUploadJob?.cancel()
        deletePreviouslyUploadedReceiptIfAny()

        _uiState.update {
            it.copy(
                receiptUploadState = ReceiptUploadState.Uploading(
                    displayName = "جاري تجهيز الملف...",
                    mimeType = "",
                    sizeBytes = 0L
                ),
                submitErrorMessage = null
            )
        }

        receiptUploadJob = viewModelScope.launch {
            val processResult = ReceiptFileProcessor.processUri(
                context = appContext,
                uri = uri,
                ioDispatcher = ioDispatcher
            )
            processResult.onSuccess { processed ->
                pendingReceipt = processed
                performUpload(processed)
            }.onFailure { err ->
                pendingReceipt = null
                _uiState.update {
                    it.copy(
                        receiptUploadState = ReceiptUploadState.Failed(
                            displayName = "",
                            mimeType = "",
                            sizeBytes = 0L,
                            errorMessage = err.message ?: "تعذر قراءة الملف المختار."
                        )
                    )
                }
            }
        }
    }

    fun setProcessedReceiptAndUpload(receipt: ProcessedReceipt) {
        receiptUploadJob?.cancel()
        deletePreviouslyUploadedReceiptIfAny()
        pendingReceipt = receipt
        _uiState.update {
            it.copy(
                receiptUploadState = ReceiptUploadState.Uploading(
                    displayName = receipt.displayName,
                    mimeType = receipt.mimeType,
                    sizeBytes = receipt.sizeBytes,
                    cacheFilePath = receipt.cacheFilePath
                ),
                submitErrorMessage = null
            )
        }
        receiptUploadJob = viewModelScope.launch {
            performUpload(receipt)
        }
    }

    fun retryReceiptUpload() {
        val currentPending = pendingReceipt ?: return
        receiptUploadJob?.cancel()
        _uiState.update {
            it.copy(
                receiptUploadState = ReceiptUploadState.Uploading(
                    displayName = currentPending.displayName,
                    mimeType = currentPending.mimeType,
                    sizeBytes = currentPending.sizeBytes,
                    cacheFilePath = currentPending.cacheFilePath
                ),
                submitErrorMessage = null
            )
        }
        receiptUploadJob = viewModelScope.launch {
            performUpload(currentPending)
        }
    }

    fun removeReceipt() {
        clearReceiptInternal(deleteRemote = true)
        _uiState.update {
            it.copy(
                receiptUploadState = ReceiptUploadState.Idle,
                submitErrorMessage = null
            )
        }
    }

    private fun clearReceiptInternal(deleteRemote: Boolean) {
        receiptUploadJob?.cancel()
        receiptUploadJob = null
        if (deleteRemote) {
            deletePreviouslyUploadedReceiptIfAny()
        }
        val cachePath = pendingReceipt?.cacheFilePath
            ?: when (val st = _uiState.value.receiptUploadState) {
                is ReceiptUploadState.Uploaded -> st.cacheFilePath
                is ReceiptUploadState.Failed -> st.cacheFilePath
                is ReceiptUploadState.Uploading -> st.cacheFilePath
                ReceiptUploadState.Idle -> null
            }
        ReceiptFileProcessor.cleanupCacheFile(cachePath)
        pendingReceipt = null
    }

    private fun deletePreviouslyUploadedReceiptIfAny() {
        val uploadedPath = (_uiState.value.receiptUploadState as? ReceiptUploadState.Uploaded)?.storagePath
        if (!uploadedPath.isNullOrBlank()) {
            viewModelScope.launch {
                try {
                    subscriptionRepository.deleteReceipt(uploadedPath)
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun performUpload(receipt: ProcessedReceipt) {
        val teacherId = _uiState.value.teacherId
        if (teacherId.isNullOrBlank()) {
            _uiState.update {
                it.copy(
                    receiptUploadState = ReceiptUploadState.Failed(
                        displayName = receipt.displayName,
                        mimeType = receipt.mimeType,
                        sizeBytes = receipt.sizeBytes,
                        errorMessage = "تعذر تحديد حساب المعلم. يرجى إعادة تسجيل الدخول.",
                        cacheFilePath = receipt.cacheFilePath
                    )
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                receiptUploadState = ReceiptUploadState.Uploading(
                    displayName = receipt.displayName,
                    mimeType = receipt.mimeType,
                    sizeBytes = receipt.sizeBytes,
                    cacheFilePath = receipt.cacheFilePath
                )
            )
        }

        val uploadResult = subscriptionRepository.uploadReceipt(
            bytes = receipt.bytes,
            fileName = receipt.storageFileName,
            teacherId = teacherId,
            mimeType = receipt.mimeType
        )

        if (uploadResult.isSuccess) {
            val storagePath = uploadResult.getOrThrow()
            _uiState.update {
                it.copy(
                    receiptUploadState = ReceiptUploadState.Uploaded(
                        displayName = receipt.displayName,
                        storageFileName = receipt.storageFileName,
                        mimeType = receipt.mimeType,
                        sizeBytes = receipt.sizeBytes,
                        storagePath = storagePath,
                        cacheFilePath = receipt.cacheFilePath
                    ),
                    submitErrorMessage = null
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    receiptUploadState = ReceiptUploadState.Failed(
                        displayName = receipt.displayName,
                        mimeType = receipt.mimeType,
                        sizeBytes = receipt.sizeBytes,
                        errorMessage = uploadResult.exceptionOrNull()?.message ?: "فشل رفع إيصال الدفع.",
                        cacheFilePath = receipt.cacheFilePath
                    )
                )
            }
        }
    }

    fun dismissConfirmation() {
        val cachePath = _uiState.value.submittedConfirmation?.receiptCachePath
        ReceiptFileProcessor.cleanupCacheFile(cachePath)
        _uiState.update { it.copy(submittedConfirmation = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(submitErrorMessage = null) }
    }

    fun submitRequest() {
        val state = _uiState.value
        if (state.isSubmitting) return

        val plan = state.selectedPlan ?: run {
            _uiState.update { it.copy(submitErrorMessage = "يرجى اختيار الباقة أولاً.") }
            return
        }

        if (state.hasPendingRequest) {
            _uiState.update {
                it.copy(submitErrorMessage = "يوجد لديك طلب اشتراك قيد المراجعة بالفعل.")
            }
            return
        }

        val isFree = plan.code == "free" || plan.monthlyPrice <= 0.0
        val uploadedReceipt = state.receiptUploadState as? ReceiptUploadState.Uploaded

        if (!isFree) {
            val normalizedPhone = PhoneUtil.normalizeDigits(state.payerPhone.trim())
            if (normalizedPhone.isBlank() || !PhoneUtil.hasValidPhoneNumber(normalizedPhone)) {
                _uiState.update {
                    it.copy(submitErrorMessage = "يرجى إدخال رقم هاتف المحوّل بشكل صحيح.")
                }
                return
            }
            if (state.receiptUploadState is ReceiptUploadState.Uploading) {
                _uiState.update {
                    it.copy(submitErrorMessage = "يرجى الانتظار حتى يكتمل رفع إيصال الدفع.")
                }
                return
            }
            if (uploadedReceipt == null || uploadedReceipt.storagePath.isBlank()) {
                _uiState.update {
                    it.copy(submitErrorMessage = "يجب رفع إيصال الدفع بنجاح قبل إرسال طلب الاشتراك.")
                }
                return
            }
        }

        val teacherId = state.teacherId
        if (teacherId.isNullOrBlank()) {
            _uiState.update {
                it.copy(submitErrorMessage = "تعذر تحديد حساب المعلم. يرجى إعادة تسجيل الدخول.")
            }
            return
        }

        val idempotencyKey = currentIdempotencyKey ?: UUID.randomUUID().toString().also {
            currentIdempotencyKey = it
        }
        val billingPeriod = if (isFree) "monthly" else state.selectedBillingPeriod
        val normalizedPayerPhone = if (isFree) "" else PhoneUtil.normalizeDigits(state.payerPhone.trim())
        val normalizedTransferRef = if (isFree) null else state.transferReference.trim().ifBlank { null }
        val receiptPath = if (isFree) "" else uploadedReceipt!!.storagePath

        // Set isSubmitting synchronously before coroutine launch to block double-tap race conditions.
        _uiState.update { it.copy(isSubmitting = true, submitErrorMessage = null) }

        viewModelScope.launch {
            try {
                val result = subscriptionRepository.submitSubscriptionRequest(
                    planId = plan.id,
                    billingPeriod = billingPeriod,
                    paymentMethod = "vodafone_cash",
                    payerPhone = normalizedPayerPhone,
                    transferReference = normalizedTransferRef,
                    receiptPath = receiptPath,
                    idempotencyKey = idempotencyKey
                )

                if (result.isSuccess) {
                    val rpcResult = result.getOrThrow()
                    val fallbackPrice = if (isFree) {
                        0.0
                    } else if (billingPeriod == "annual") {
                        plan.monthlyPrice * 10.0
                    } else {
                        plan.monthlyPrice
                    }
                    val fallbackDays = if (billingPeriod == "annual") 365 else 30

                    val confirmation = SubmittedSubscriptionConfirmation(
                        requestId = rpcResult.requestId,
                        planName = plan.name,
                        billingPeriod = billingPeriod,
                        durationDays = rpcResult.durationDays ?: fallbackDays,
                        price = rpcResult.price ?: fallbackPrice,
                        isFree = isFree,
                        payerPhone = normalizedPayerPhone,
                        transferReference = normalizedTransferRef,
                        teacherName = state.teacherName,
                        teacherRegisteredPhone = state.teacherPhone,
                        receiptCachePath = uploadedReceipt?.cacheFilePath,
                        receiptMimeType = uploadedReceipt?.mimeType,
                        receiptFileName = uploadedReceipt?.displayName
                    )

                    // Rotate key after successful submission; keep temp file alive for WhatsApp sharing.
                    currentIdempotencyKey = null
                    pendingReceipt = null

                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            selectedPlan = null,
                            payerPhone = "",
                            transferReference = "",
                            receiptUploadState = ReceiptUploadState.Idle,
                            submitErrorMessage = null,
                            submittedConfirmation = confirmation
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
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isSubmitting = false) }
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        submitErrorMessage = e.message ?: "فشل إرسال طلب الاشتراك."
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        receiptUploadJob?.cancel()
        ReceiptFileProcessor.cleanupCacheFile(pendingReceipt?.cacheFilePath)
        ReceiptFileProcessor.cleanupCacheFile(_uiState.value.submittedConfirmation?.receiptCachePath)
    }
}
