package com.example.ui.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.SubscriptionPlan
import com.example.core.model.TeacherSubscriptionRequest
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningLight
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerRedLight
import com.example.ui.theme.DarkNavyCard
import com.example.ui.theme.Dimens
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldGreenLight
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.PrimaryIndigoLight

@Composable
internal fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun SelectedPlanSummaryCard(
    selectedPlan: SubscriptionPlan,
    selectedBillingPeriod: String,
    isFree: Boolean,
    price: Double,
    durationDays: Int,
    onSelectBillingPeriod: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("selected_plan_summary_card"),
        shape = RoundedCornerShape(Dimens.Radius16),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, PrimaryIndigo.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(Dimens.Spacing16),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing10)
        ) {
            Text(
                text = "تفاصيل الباقة المختارة",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = PrimaryIndigo
            )
            if (!isFree) {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
                    FilterChip(
                        selected = selectedBillingPeriod == "monthly",
                        onClick = { onSelectBillingPeriod("monthly") },
                        label = { Text("شهري (${selectedPlan.monthlyPrice.toLong()} ج.م)") },
                        modifier = Modifier.testTag("billing_period_monthly")
                    )
                    FilterChip(
                        selected = selectedBillingPeriod == "annual",
                        onClick = { onSelectBillingPeriod("annual") },
                        label = { Text("سنوي (${(selectedPlan.monthlyPrice * 10).toLong()} ج.م)") },
                        modifier = Modifier.testTag("billing_period_annual")
                    )
                }
            }
            SummaryRow("الباقة", selectedPlan.name)
            SummaryRow(
                "السعر",
                if (isFree) "مجاني" else "${price.toLong()} ج.م"
            )
            SummaryRow(
                "المدة",
                "${if (selectedBillingPeriod == "annual") "سنوي" else "شهري"} ($durationDays يوم)"
            )
        }
    }
}

@Composable
internal fun CurrentSubscriptionBanner(
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
internal fun PendingRequestCard(
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
internal fun PlanSelectionCard(
    plan: SubscriptionPlan,
    isCurrent: Boolean,
    isSelected: Boolean,
    hasPendingRequest: Boolean,
    onSelect: () -> Unit
) {
    val isPro = plan.code == "pro"
    val borderColor = when {
        isSelected -> PrimaryIndigo
        isCurrent -> EmeraldGreen
        isPro -> PrimaryIndigo.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plan_card_${plan.code}"),
        shape = RoundedCornerShape(Dimens.Radius20),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) PrimaryIndigoLight.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(if (isSelected || isCurrent) 2.dp else 1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected || isPro) 3.dp else 1.dp)
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

                if (isSelected) {
                    Surface(
                        shape = RoundedCornerShape(Dimens.Radius8),
                        color = PrimaryIndigo
                    ) {
                        Text(
                            text = "تم الاختيار",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else if (isCurrent) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("select_plan_button_${plan.code}"),
                shape = RoundedCornerShape(Dimens.Radius12),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isSelected -> EmeraldGreen
                        isPro -> PrimaryIndigo
                        else -> EmeraldGreen
                    }
                )
            ) {
                Text(
                    text = when {
                        isCurrent -> "باقتك الحالية"
                        hasPendingRequest -> "يوجد طلب قيد المراجعة"
                        isSelected -> "الباقة المختارة ✓"
                        plan.code == "free" -> "اختيار الباقة المجانية"
                        else -> "اختيار هذه الباقة"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
internal fun RequestHistoryItem(
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
