package com.example.ui.subscription

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.core.model.SubscriptionPlan
import com.example.core.model.TeacherSubscriptionRequest
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningLight
import com.example.ui.theme.DarkNavyCard
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerRedLight
import com.example.ui.theme.Dimens
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldGreenLight
import com.example.ui.theme.PrimaryIndigo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionPlansScreen(
    onNavigateBack: () -> Unit,
    viewModel: SubscriptionPlansViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "receipt.jpg"
                    viewModel.setReceipt(bytes, fileName)
                }
            } catch (_: Exception) {
                // Ignore read failure
            }
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
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadData() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag("subscription_plans_screen")
    ) { innerPadding ->
        when {
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
                        .padding(innerPadding),
                    contentPadding = PaddingValues(
                        horizontal = Dimens.Spacing20,
                        vertical = Dimens.Spacing16
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
                ) {
                    // Success / Error Banner
                    if (!uiState.submitSuccessMessage.isNullOrBlank()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(Dimens.Radius12),
                                color = EmeraldGreenLight,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(Dimens.Spacing16),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = EmeraldGreen
                                    )
                                    Spacer(modifier = Modifier.width(Dimens.Spacing12))
                                    Text(
                                        text = uiState.submitSuccessMessage!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = EmeraldGreen,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // Current Subscription Banner
                    item {
                        CurrentSubscriptionBanner(
                            currentPlan = uiState.currentPlan,
                            subscriptionStatus = uiState.subscriptionStatus,
                            endDate = uiState.subscriptionEndDate
                        )
                    }

                    // Pending Request Banner
                    if (uiState.pendingRequest != null) {
                        item {
                            PendingRequestCard(
                                request = uiState.pendingRequest!!,
                                plans = uiState.plans
                            )
                        }
                    }

                    // Plans Section Header
                    item {
                        Text(
                            text = "الباقات المتاحة",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Plan Cards
                    items(uiState.plans, key = { it.id }) { plan ->
                        val isCurrent = uiState.currentPlan?.id == plan.id
                        PlanCard(
                            plan = plan,
                            isCurrent = isCurrent,
                            hasPendingRequest = uiState.hasPendingRequest,
                            onSelect = { viewModel.selectPlan(plan) }
                        )
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

        // Subscription Request Dialog
        val selectedPlan = uiState.selectedPlan
        if (selectedPlan != null) {
            SubscriptionRequestDialog(
                plan = selectedPlan,
                billingPeriod = uiState.selectedBillingPeriod,
                payerPhone = uiState.payerPhone,
                transferReference = uiState.transferReference,
                receiptFileName = uiState.receiptFileName,
                isSubmitting = uiState.isSubmitting,
                errorMessage = uiState.submitErrorMessage,
                onBillingPeriodChange = viewModel::selectBillingPeriod,
                onPayerPhoneChange = viewModel::updatePayerPhone,
                onTransferReferenceChange = viewModel::updateTransferReference,
                onPickReceipt = { imagePickerLauncher.launch("image/*") },
                onSubmit = viewModel::submitRequest,
                onDismiss = viewModel::dismissPlanSelection
            )
        }
    }
}

@Composable
private fun CurrentSubscriptionBanner(
    currentPlan: SubscriptionPlan?,
    subscriptionStatus: String?,
    endDate: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.Radius20),
        colors = CardDefaults.cardColors(containerColor = DarkNavyCard)
    ) {
        Column(modifier = Modifier.padding(Dimens.Spacing20)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    tint = Color(0xFFFBBF24),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(Dimens.Spacing12))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "باقتك الحالية",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Text(
                        text = currentPlan?.name ?: "مجاني",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
                val statusLabel = when (subscriptionStatus?.lowercase()) {
                    "active" -> "نشط"
                    "expired" -> "منتهي"
                    "trial" -> "تجريبي"
                    else -> if (currentPlan?.code == "free") "مجاني" else "غير محدد"
                }
                Surface(
                    shape = RoundedCornerShape(Dimens.Radius8),
                    color = if (subscriptionStatus == "expired") DangerRed else EmeraldGreen
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimens.Spacing12))
            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
            Spacer(modifier = Modifier.height(Dimens.Spacing12))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "الحد الأقصى للطلاب: ${currentPlan?.studentLimit ?: 20} طالب",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
                if (!endDate.isNullOrBlank()) {
                    Text(
                        text = "ينتهي: ${endDate.take(10)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingRequestCard(
    request: TeacherSubscriptionRequest,
    plans: List<SubscriptionPlan>
) {
    val planName = plans.find { it.id == request.planId }?.name ?: "باقة"
    Surface(
        shape = RoundedCornerShape(Dimens.Radius16),
        color = AmberWarningLight,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Dimens.Spacing16),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.HourglassTop,
                contentDescription = null,
                tint = AmberWarning,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(Dimens.Spacing12))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "طلب اشتراك قيد المراجعة",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = AmberWarning
                )
                Text(
                    text = "الباقة: $planName • المدة: ${if (request.billingPeriod == "annual") "سنوي" else "شهري"} • السعر: ${request.priceAtRequest.toLong()} ج.م",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF92400E)
                )
                Text(
                    text = "سيتم تفعيل الباقة فور مراجعة الإدارة والموافقة على الطلب.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF92400E)
                )
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: SubscriptionPlan,
    isCurrent: Boolean,
    hasPendingRequest: Boolean,
    onSelect: () -> Unit
) {
    val isPro = plan.code == "pro"
    val borderColor = when {
        isCurrent -> EmeraldGreen
        isPro -> PrimaryIndigo
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.Radius20),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (isCurrent || isPro) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPro) 3.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(Dimens.Spacing20)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plan.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isPro) {
                        Spacer(modifier = Modifier.width(Dimens.Spacing8))
                        Surface(
                            shape = RoundedCornerShape(Dimens.Radius8),
                            color = PrimaryIndigo.copy(alpha = 0.1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = PrimaryIndigo,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "الأكثر تميزاً",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = PrimaryIndigo
                                )
                            }
                        }
                    }
                }

                if (isCurrent) {
                    Surface(
                        shape = RoundedCornerShape(Dimens.Radius8),
                        color = EmeraldGreenLight
                    ) {
                        Text(
                            text = "الباقة الحالية",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = EmeraldGreen,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimens.Spacing12))

            // Pricing
            if (plan.monthlyPrice == 0.0) {
                Text(
                    text = "مجاني",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = EmeraldGreen
                )
            } else {
                val annualPrice = (plan.monthlyPrice * 10).toLong()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "${plan.monthlyPrice.toLong()} ج.م",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryIndigo
                    )
                    Text(
                        text = " / شهرياً",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "أو $annualPrice ج.م سنوياً (توفير شهرين)",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmeraldGreen
                )
            }

            Spacer(modifier = Modifier.height(Dimens.Spacing8))
            Text(
                text = "حتى ${plan.studentLimit} طالب",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )

            if (plan.features.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Dimens.Spacing12))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(Dimens.Spacing12))
                plan.features.forEach { feature ->
                    Row(
                        modifier = Modifier.padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(Dimens.Spacing8))
                        Text(
                            text = feature,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimens.Spacing16))

            Button(
                onClick = onSelect,
                enabled = !isCurrent && !hasPendingRequest,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimens.Radius12),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPro) PrimaryIndigo else EmeraldGreen
                )
            ) {
                Text(
                    text = when {
                        isCurrent -> "باقتك الحالية"
                        hasPendingRequest -> "يوجد طلب قيد المراجعة"
                        plan.code == "free" -> "اختيار الباقة المجانية"
                        else -> "اشترك الآن"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun RequestHistoryItem(
    request: TeacherSubscriptionRequest,
    plans: List<SubscriptionPlan>
) {
    val planName = plans.find { it.id == request.planId }?.name ?: "باقة"
    val (statusText, statusColor, bgColor) = when (request.status.lowercase()) {
        "approved" -> Triple("تمت الموافقة", EmeraldGreen, EmeraldGreenLight)
        "rejected" -> Triple("مرفوض", DangerRed, DangerRedLight)
        else -> Triple("قيد المراجعة", AmberWarning, AmberWarningLight)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.Radius12),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.Spacing14),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$planName (${if (request.billingPeriod == "annual") "سنوي" else "شهري"})",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "${request.priceAtRequest.toLong()} ج.م • ${request.createdAt.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!request.adminNote.isNullOrBlank()) {
                    Text(
                        text = "ملاحظة الإدارة: ${request.adminNote}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(Dimens.Radius8),
                color = bgColor
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SubscriptionRequestDialog(
    plan: SubscriptionPlan,
    billingPeriod: String,
    payerPhone: String,
    transferReference: String,
    receiptFileName: String?,
    isSubmitting: Boolean,
    errorMessage: String?,
    onBillingPeriodChange: (String) -> Unit,
    onPayerPhoneChange: (String) -> Unit,
    onTransferReferenceChange: (String) -> Unit,
    onPickReceipt: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val isFree = plan.code == "free"
    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = {
            Text(
                text = "طلب الاشتراك في باقة ${plan.name}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
                if (isFree) {
                    Text(
                        text = "سيتم إرسال طلب تفعيل الباقة المجانية (${plan.studentLimit} طالب). لا يلزم تحويل مالي.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "اختر مدة الاشتراك:",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                        FilterChip(
                            selected = billingPeriod == "monthly",
                            onClick = { onBillingPeriodChange("monthly") },
                            label = { Text("شهري (${plan.monthlyPrice.toLong()} ج.م)") }
                        )
                        FilterChip(
                            selected = billingPeriod == "annual",
                            onClick = { onBillingPeriodChange("annual") },
                            label = { Text("سنوي (${(plan.monthlyPrice * 10).toLong()} ج.م)") }
                        )
                    }

                    Text(
                        text = "طريقة الدفع: فودافون كاش (Vodafone Cash)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = payerPhone,
                        onValueChange = onPayerPhoneChange,
                        label = { Text("رقم الهاتف المحوّل منه") },
                        placeholder = { Text("010XXXXXXXX") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = transferReference,
                        onValueChange = onTransferReferenceChange,
                        label = { Text("رقم العملية / المرجع") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedButton(
                        onClick = onPickReceipt,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Dimens.Radius12)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(Dimens.Spacing8))
                        Text(
                            text = if (receiptFileName != null) "تم اختيار: $receiptFileName" else "إرفاق صورة إيصال الدفع"
                        )
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("إرسال الطلب")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("إلغاء")
            }
        }
    )
}
