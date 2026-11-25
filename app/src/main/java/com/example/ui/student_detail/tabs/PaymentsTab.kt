package com.example.ui.student_detail.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.core.model.LessonPayment
import com.example.core.model.Student
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerRedLight
import com.example.ui.theme.Dimens
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldGreenLight
import com.example.ui.theme.PrimaryIndigo
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentsTab(
    student: Student,
    monthNameAr: String,
    monthlyPayment: LessonPayment?,
    isPaid: Boolean,
    isToggling: Boolean,
    onNavigateMonth: (Int) -> Unit,
    onTogglePayment: () -> Unit,
    modifier: Modifier = Modifier,
    selectedYear: Int = monthlyPayment?.year ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
    selectedMonth: Int = monthlyPayment?.month ?: (java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1),
    defaultMonthlyFee: Double = 0.0,
    paymentHistory: List<LessonPayment> = emptyList(),
    selectedYearFilter: Int? = null,
    onSelectYearFilter: (Int?) -> Unit = {},
    onSavePaymentDetails: (year: Int, month: Int, isPaid: Boolean, amount: Double) -> Unit = { _, _, _, _ -> }
) {
    var showAmountDialog by remember { mutableStateOf(false) }

    val availableYears = remember(paymentHistory) {
        paymentHistory.map { it.year }.distinct().sortedDescending()
    }
    val filteredHistory = remember(paymentHistory, selectedYearFilter) {
        if (selectedYearFilter == null) paymentHistory else paymentHistory.filter { it.year == selectedYearFilter }
    }
    val paidHistory = remember(filteredHistory) { filteredHistory.filter { it.isPaid } }
    val totalPaidAmount = remember(paidHistory) { paidHistory.sumOf { it.amount } }
    val displayedMonthAmount = monthlyPayment?.amount?.takeIf { it > 0.0 } ?: defaultMonthlyFee

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
    ) {
        // Month Navigation Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.Radius20),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.Spacing16, vertical = Dimens.Spacing12),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onNavigateMonth(1) },
                    modifier = Modifier.testTag("payments_next_month")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "الشهر التالي",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = monthNameAr,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                IconButton(
                    onClick = { onNavigateMonth(-1) },
                    modifier = Modifier.testTag("payments_prev_month")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "الشهر السابق",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Main Payment Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.Radius24),
            colors = CardDefaults.cardColors(
                containerColor = if (isPaid) EmeraldGreenLight.copy(alpha = 0.45f) else DangerRedLight.copy(alpha = 0.45f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.Spacing24),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (isPaid) EmeraldGreen else DangerRed)
                ) {
                    Icon(
                        imageVector = if (isPaid) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = if (isPaid) "مدفوع" else "غير مدفوع",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.Spacing16))

                Text(
                    text = if (isPaid) "تم دفع الاشتراك الشهري" else "لم يتم دفع الاشتراك الشهري",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (isPaid) Color(0xFF047857) else DangerRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("payment_status_text")
                )

                Spacer(modifier = Modifier.height(Dimens.Spacing8))

                Text(
                    text = "الطالب: ${student.fullName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Dimens.Spacing8))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                        modifier = Modifier.padding(horizontal = Dimens.Spacing16, vertical = Dimens.Spacing8)
                    ) {
                        Text(
                            text = if (displayedMonthAmount > 0.0) {
                                "قيمة الاشتراك: ${formatCurrency(displayedMonthAmount)}"
                            } else {
                                "لم يتم تحديد قيمة الاشتراك"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("payment_amount_text")
                        )
                    }
                }

                if (isPaid && !monthlyPayment?.paidAt.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(Dimens.Spacing8))
                    Text(
                        text = "تاريخ السداد: ${monthlyPayment?.paidAt?.take(10)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.Spacing20))

                // Primary Toggle Action Button
                Button(
                    onClick = onTogglePayment,
                    enabled = !isToggling,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPaid) DangerRed else EmeraldGreen,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(Dimens.Radius16),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("toggle_payment_button")
                ) {
                    if (isToggling) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Payments,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(Dimens.Spacing8))
                            Text(
                                text = if (isPaid) "تغيير الحالة إلى: غير مدفوع" else "تغيير الحالة إلى: مدفوع",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.Spacing10))

                OutlinedButton(
                    onClick = { showAmountDialog = true },
                    enabled = !isToggling,
                    shape = RoundedCornerShape(Dimens.Radius16),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("edit_payment_amount_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل قيمة الاشتراك",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Dimens.Spacing8))
                    Text(
                        text = "تعديل المبلغ والحالة",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }

        // Student Payments Summary Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("payment_summary_card"),
            shape = RoundedCornerShape(Dimens.Radius20),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.Spacing16),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
            ) {
                Text(
                    text = "ملخص مدفوعات الطالب",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
                ) {
                    SummaryMetricTile(
                        title = "إجمالي المدفوع",
                        value = formatCurrency(totalPaidAmount),
                        accent = EmeraldGreen,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryMetricTile(
                        title = "الأشهر المسددة",
                        value = "${paidHistory.size} شهر",
                        accent = PrimaryIndigo,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (defaultMonthlyFee > 0.0) {
                    Text(
                        text = "الرسوم الافتراضية المحددة: ${formatCurrency(defaultMonthlyFee)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Dimens.Spacing12, vertical = Dimens.Spacing8),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "يسجل النظام حالة السداد الشهري الكامل لكل شهر وقيمة الاشتراك وفق بنية قاعدة البيانات الحالية.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Student Payment History Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("payment_history_card"),
            shape = RoundedCornerShape(Dimens.Radius20),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.Spacing16),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = PrimaryIndigo,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "سجل مدفوعات الطالب",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (availableYears.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)
                    ) {
                        FilterChip(
                            selected = selectedYearFilter == null,
                            onClick = { onSelectYearFilter(null) },
                            label = { Text("الكل") },
                            modifier = Modifier.testTag("payment_filter_all")
                        )
                        availableYears.forEach { yearOption ->
                            FilterChip(
                                selected = selectedYearFilter == yearOption,
                                onClick = { onSelectYearFilter(yearOption) },
                                label = { Text(yearOption.toString()) },
                                modifier = Modifier.testTag("payment_filter_$yearOption")
                            )
                        }
                    }
                }

                if (filteredHistory.isEmpty()) {
                    Text(
                        text = "لا توجد سجلات دفع مسجلة لهذا الطالب بعد.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Dimens.Spacing8)
                    )
                } else {
                    filteredHistory.forEachIndexed { index, payment ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        PaymentHistoryRow(payment = payment)
                    }
                }
            }
        }
    }

    if (showAmountDialog) {
        EditMonthlyPaymentDialog(
            monthNameAr = monthNameAr,
            initialPaid = isPaid,
            initialAmount = displayedMonthAmount,
            onDismiss = { showAmountDialog = false },
            onSave = { newPaid, newAmount ->
                showAmountDialog = false
                onSavePaymentDetails(selectedYear, selectedMonth, newPaid, newAmount)
            }
        )
    }
}

@Composable
private fun SummaryMetricTile(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.08f)
    ) {
        Column(
            modifier = Modifier.padding(Dimens.Spacing12),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = accent
            )
        }
    }
}

@Composable
private fun PaymentHistoryRow(payment: LessonPayment) {
    val monthLabel = arabicMonthName(payment.month)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.Spacing8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$monthLabel ${payment.year}",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            val detail = when {
                payment.isPaid && !payment.paidAt.isNullOrBlank() -> "تم السداد في: ${payment.paidAt.take(10)}"
                payment.isPaid -> "مدفوع"
                else -> "غير مسدد"
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (payment.amount > 0.0) formatCurrency(payment.amount) else "—",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (payment.isPaid) EmeraldGreenLight.copy(alpha = 0.65f) else DangerRedLight.copy(alpha = 0.65f)
            ) {
                Text(
                    text = if (payment.isPaid) "مدفوع" else "غير مدفوع",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (payment.isPaid) Color(0xFF047857) else DangerRed,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun EditMonthlyPaymentDialog(
    monthNameAr: String,
    initialPaid: Boolean,
    initialAmount: Double,
    onDismiss: () -> Unit,
    onSave: (Boolean, Double) -> Unit
) {
    var amountText by remember {
        mutableStateOf(
            if (initialAmount > 0.0) {
                if (initialAmount % 1.0 == 0.0) initialAmount.toLong().toString() else initialAmount.toString()
            } else {
                ""
            }
        )
    }
    var paidState by remember { mutableStateOf(initialPaid) }
    var amountError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "تحديث اشتراك $monthNameAr",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        amountText = input
                        amountError = null
                    },
                    label = { Text("مبلغ الاشتراك (ج.م)") },
                    placeholder = { Text("مثال: 150") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = amountError != null,
                    supportingText = amountError?.let { { Text(it) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("payment_amount_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (paidState) "حالة الشهر: مدفوع" else "حالة الشهر: غير مدفوع",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = paidState,
                        onCheckedChange = { paidState = it },
                        modifier = Modifier.testTag("payment_paid_switch")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = amountText.trim()
                    val parsed = if (trimmed.isEmpty()) 0.0 else trimmed.toDoubleOrNull()
                    if (parsed == null || parsed < 0.0) {
                        amountError = "يرجى إدخال مبلغ صحيح أكبر من أو يساوي 0"
                    } else {
                        onSave(paidState, parsed)
                    }
                },
                modifier = Modifier.testTag("save_payment_dialog_button")
            ) {
                Text("حفظ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء")
            }
        }
    )
}

private fun formatCurrency(amount: Double): String {
    return if (amount % 1.0 == 0.0) {
        "${amount.toLong()} ج.م"
    } else {
        String.format(Locale.US, "%.2f ج.م", amount)
    }
}

private fun arabicMonthName(month: Int): String = when (month) {
    1 -> "يناير"
    2 -> "فبراير"
    3 -> "مارس"
    4 -> "أبريل"
    5 -> "مايو"
    6 -> "يونيو"
    7 -> "يوليو"
    8 -> "أغسطس"
    9 -> "سبتمبر"
    10 -> "أكتوبر"
    11 -> "نوفمبر"
    12 -> "ديسمبر"
    else -> "شهر $month"
}
