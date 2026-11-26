package com.example.ui.subscription

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerRedLight
import com.example.ui.theme.Dimens
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.PrimaryIndigo
import com.example.util.ReceiptFileProcessor
import com.example.util.WhatsAppReceiptSharer
import com.example.util.WhatsAppShareResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionPlansScreen(
    onNavigateBack: () -> Unit,
    viewModel: SubscriptionPlansViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var copiedRecently by remember { mutableStateOf(false) }
    var whatsappNotInstalled by remember { mutableStateOf(false) }

    val receiptPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onReceiptUriPicked(context, uri)
        }
    }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(3000L)
            copiedRecently = false
        }
    }

    val confirmation = uiState.submittedConfirmation
    if (confirmation != null) {
        BackHandler {
            whatsappNotInstalled = false
            viewModel.dismissConfirmation()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "الباقات والاشتراكات",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (confirmation != null) {
                                whatsappNotInstalled = false
                                viewModel.dismissConfirmation()
                            } else {
                                onNavigateBack()
                            }
                        },
                        modifier = Modifier.testTag("subscription_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    if (confirmation == null) {
                        IconButton(onClick = { viewModel.loadData() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "تحديث"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag("subscription_plans_screen")
    ) { innerPadding ->
        when {
            confirmation != null -> {
                val waMessage = remember(confirmation) {
                    WhatsAppReceiptSharer.buildSubscriptionWhatsAppMessage(
                        teacherName = confirmation.teacherName,
                        planName = confirmation.planName,
                        price = confirmation.price,
                        billingPeriod = confirmation.billingPeriod,
                        durationDays = confirmation.durationDays,
                        transferReference = confirmation.transferReference,
                        registeredPhone = confirmation.teacherRegisteredPhone
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Dimens.Spacing20, vertical = Dimens.Spacing16)
                ) {
                    SubscriptionSuccessConfirmationView(
                        confirmation = confirmation,
                        transferNumber = uiState.transferNumber,
                        whatsappNotInstalled = whatsappNotInstalled,
                        onSendViaWhatsApp = {
                            val result = WhatsAppReceiptSharer.shareReceiptToWhatsApp(
                                context = context,
                                receiptCachePath = confirmation.receiptCachePath,
                                receiptMimeType = confirmation.receiptMimeType,
                                message = waMessage
                            )
                            when (result) {
                                WhatsAppShareResult.OpenedWhatsApp -> {
                                    whatsappNotInstalled = false
                                }
                                WhatsAppShareResult.WhatsAppNotInstalled -> {
                                    whatsappNotInstalled = true
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar("تطبيق WhatsApp غير مثبت، يمكنك نسخ الرسالة أو مشاركة الإيصال بالأسفل.")
                                    }
                                }
                                is WhatsAppShareResult.Failed -> {
                                    whatsappNotInstalled = true
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(result.message)
                                    }
                                }
                            }
                        },
                        onOpenDirectWhatsAppChat = {
                            val result = WhatsAppReceiptSharer.openDirectWhatsAppChat(
                                context = context,
                                message = waMessage
                            )
                            if (result != WhatsAppShareResult.OpenedWhatsApp) {
                                whatsappNotInstalled = true
                            }
                        },
                        onCopyWhatsAppMessage = {
                            copyToClipboard(context, "بيانات طلب الاشتراك", waMessage)
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("تم نسخ نص الرسالة")
                            }
                        },
                        onShareViaOtherApp = {
                            val opened = WhatsAppReceiptSharer.shareReceiptViaChooser(
                                context = context,
                                receiptCachePath = confirmation.receiptCachePath,
                                receiptMimeType = confirmation.receiptMimeType,
                                message = waMessage
                            )
                            if (!opened) {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("تعذر فتح قائمة المشاركة على هذا الجهاز.")
                                }
                            }
                        },
                        onDone = {
                            whatsappNotInstalled = false
                            viewModel.dismissConfirmation()
                        }
                    )
                }
            }

            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = PrimaryIndigo)
                }
            }

            uiState.errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(Dimens.Spacing24),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = uiState.errorMessage!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(Dimens.Spacing16))
                        Button(onClick = { viewModel.loadData() }) {
                            Text("إعادة المحاولة")
                        }
                    }
                }
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding(),
                    contentPadding = PaddingValues(
                        horizontal = Dimens.Spacing20,
                        vertical = Dimens.Spacing16
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
                ) {
                    // 1. Subscription Request Title + Current Subscription Status
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
                            Text(
                                text = "طلب الاشتراك",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("subscription_request_title")
                            )
                            CurrentSubscriptionBanner(
                                currentPlan = uiState.currentPlan,
                                subscriptionStatus = uiState.subscriptionStatus,
                                endDate = uiState.subscriptionEndDate
                            )
                        }
                    }

                    if (uiState.pendingRequest != null) {
                        item {
                            PendingRequestCard(
                                request = uiState.pendingRequest!!,
                                plans = uiState.plans
                            )
                        }
                    }

                    // 2. Transfer Number (01040129414) + Copy Button + Selected Price (Before plan selection)
                    item {
                        TransferInstructionsCard(
                            transferNumber = uiState.transferNumber,
                            selectedPlan = uiState.selectedPlan,
                            selectedPrice = uiState.selectedPlanPrice,
                            selectedDurationDays = uiState.selectedPlanDurationDays,
                            selectedBillingPeriod = uiState.selectedBillingPeriod,
                            copiedRecently = copiedRecently,
                            onCopyNumber = {
                                copyToClipboard(context, "رقم التحويل", uiState.transferNumber)
                                copiedRecently = true
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("تم نسخ رقم التحويل")
                                }
                            }
                        )
                    }

                    // 3. Plan Selection
                    item {
                        Text(
                            text = "اختيار الباقة",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    items(uiState.plans, key = { it.id }) { plan ->
                        val isCurrent = uiState.currentPlan?.id == plan.id
                        val isSelected = uiState.selectedPlan?.id == plan.id
                        PlanSelectionCard(
                            plan = plan,
                            isCurrent = isCurrent,
                            isSelected = isSelected,
                            hasPendingRequest = uiState.hasPendingRequest,
                            onSelect = { viewModel.selectPlan(plan) }
                        )
                    }

                    // 4..8. Selected Plan Details, Payment Inputs, Receipt Upload & Submit Button
                    val selectedPlan = uiState.selectedPlan
                    if (selectedPlan != null) {
                        val isFree = uiState.isSelectedPlanFree
                        val price = uiState.selectedPlanPrice ?: 0.0
                        val durationDays = uiState.selectedPlanDurationDays ?: 30

                        // 4. Display Price and Duration
                        item {
                            SelectedPlanSummaryCard(
                                selectedPlan = selectedPlan,
                                selectedBillingPeriod = uiState.selectedBillingPeriod,
                                isFree = isFree,
                                price = price,
                                durationDays = durationDays,
                                onSelectBillingPeriod = viewModel::selectBillingPeriod
                            )
                        }

                        if (!isFree) {
                            // 5. Payer Phone Number
                            item {
                                OutlinedTextField(
                                    value = uiState.payerPhone,
                                    onValueChange = viewModel::updatePayerPhone,
                                    label = { Text("رقم هاتف المحوّل") },
                                    placeholder = { Text("01XXXXXXXXX") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Phone,
                                            contentDescription = null,
                                            tint = PrimaryIndigo
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    singleLine = true,
                                    enabled = !uiState.isSubmitting && !uiState.hasPendingRequest,
                                    shape = RoundedCornerShape(Dimens.Radius12),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("payer_phone_input")
                                )
                            }

                            // 6. Optional Transfer Reference
                            item {
                                OutlinedTextField(
                                    value = uiState.transferReference,
                                    onValueChange = viewModel::updateTransferReference,
                                    label = { Text("رقم العملية (اختياري)") },
                                    placeholder = { Text("أدخل رقم العملية إن وجد") },
                                    supportingText = {
                                        Text("يمكنك إرسال الطلب بدون رقم العملية إذا لم يتوفر.")
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                            contentDescription = null,
                                            tint = PrimaryIndigo
                                        )
                                    },
                                    singleLine = true,
                                    enabled = !uiState.isSubmitting && !uiState.hasPendingRequest,
                                    shape = RoundedCornerShape(Dimens.Radius12),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("transfer_reference_input")
                                )
                            }

                            // 7. Payment Receipt Upload
                            item {
                                ReceiptUploadSection(
                                    uploadState = uiState.receiptUploadState,
                                    enabled = !uiState.isSubmitting && !uiState.hasPendingRequest,
                                    onPickFile = {
                                        receiptPickerLauncher.launch(ReceiptFileProcessor.PICKER_MIME_TYPES)
                                    },
                                    onRetryUpload = viewModel::retryReceiptUpload,
                                    onRemoveFile = viewModel::removeReceipt
                                )
                            }
                        }

                        // Error banner if submit fails
                        if (!uiState.submitErrorMessage.isNullOrBlank()) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(Dimens.Radius12),
                                    color = DangerRedLight,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("submit_error_banner")
                                ) {
                                    Text(
                                        text = uiState.submitErrorMessage!!,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = DangerRed,
                                        modifier = Modifier.padding(Dimens.Spacing12)
                                    )
                                }
                            }
                        }

                        // 8. Submit Subscription Request Button
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing6)) {
                                if (!isFree && !uiState.isReceiptUploaded && !uiState.hasPendingRequest) {
                                    Text(
                                        text = "يرجى رفع إيصال الدفع بنجاح أولاً لتفعيل زر إرسال الطلب.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = viewModel::submitRequest,
                                    enabled = uiState.canSubmitRequest,
                                    shape = RoundedCornerShape(Dimens.Radius16),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isFree) EmeraldGreen else PrimaryIndigo
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .testTag("submit_subscription_button")
                                ) {
                                    if (uiState.isSubmitting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(Dimens.Spacing10))
                                        Text("جاري إرسال الطلب...", fontWeight = FontWeight.Bold)
                                    } else {
                                        Text(
                                            text = "إرسال طلب الاشتراك",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Request History
                    if (uiState.requestHistory.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(Dimens.Spacing8))
                            Text(
                                text = "سجل طلبات الاشتراك",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        items(uiState.requestHistory.take(5), key = { it.id }) { req ->
                            RequestHistoryItem(request = req, plans = uiState.plans)
                        }
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    } catch (_: Exception) {
    }
}
