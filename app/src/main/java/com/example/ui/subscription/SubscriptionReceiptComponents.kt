package com.example.ui.subscription

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.SubscriptionPlan
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerRedLight
import com.example.ui.theme.Dimens
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldGreenLight
import com.example.ui.theme.PrimaryIndigo
import com.example.ui.theme.PrimaryIndigoLight
import com.example.util.ReceiptFileProcessor
import java.io.File

@Composable
internal fun TransferInstructionsCard(
    transferNumber: String,
    selectedPlan: SubscriptionPlan?,
    selectedPrice: Double?,
    selectedDurationDays: Int?,
    selectedBillingPeriod: String,
    copiedRecently: Boolean,
    onCopyNumber: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transfer_instructions_card"),
        shape = RoundedCornerShape(Dimens.Radius20),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, PrimaryIndigo.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(Dimens.Spacing20)) {
            Text(
                text = "تعليمات الدفع والتحويل",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = PrimaryIndigo
            )
            Spacer(modifier = Modifier.height(Dimens.Spacing6))
            Text(
                text = "يرجى تحويل قيمة الاشتراك إلى هذا الرقم أولاً، ثم إرفاق إيصال الدفع وإرسال الطلب:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.Spacing12))

            Surface(
                shape = RoundedCornerShape(Dimens.Radius16),
                color = PrimaryIndigoLight,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.Spacing16, vertical = Dimens.Spacing12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "رقم التحويل",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = transferNumber,
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp
                                ),
                                color = PrimaryIndigo,
                                modifier = Modifier.testTag("transfer_number_text")
                            )
                        }
                    }

                    Button(
                        onClick = onCopyNumber,
                        shape = RoundedCornerShape(Dimens.Radius12),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryIndigo),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("copy_transfer_number_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "نسخ رقم التحويل",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(Dimens.Spacing6))
                        Text(
                            text = "نسخ الرقم",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            AnimatedVisibility(visible = copiedRecently) {
                Row(
                    modifier = Modifier.padding(top = Dimens.Spacing8),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(Dimens.Spacing6))
                    Text(
                        text = "تم نسخ رقم التحويل",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = EmeraldGreen,
                        modifier = Modifier.testTag("transfer_number_copied_badge")
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.Spacing12))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(modifier = Modifier.height(Dimens.Spacing12))

            if (selectedPlan != null && selectedPrice != null) {
                val periodText = if (selectedPlan.code == "free" || selectedPrice <= 0.0) {
                    "مجاني"
                } else {
                    val periodLabel = if (selectedBillingPeriod == "annual") "سنوي" else "شهري"
                    val days = selectedDurationDays ?: if (selectedBillingPeriod == "annual") 365 else 30
                    "${selectedPrice.toLong()} ج.م ($periodLabel - $days يوم)"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "المبلغ المطلوب تحويله (${selectedPlan.name}):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = periodText,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = if (selectedPrice <= 0.0) EmeraldGreen else PrimaryIndigo,
                        modifier = Modifier.testTag("top_selected_plan_price")
                    )
                }
            } else {
                Text(
                    text = "اختر الباقة المناسبة بالأسفل لعرض قيمة الاشتراك المطلوب تحويلها.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun ReceiptUploadSection(
    uploadState: ReceiptUploadState,
    enabled: Boolean,
    onPickFile: () -> Unit,
    onRetryUpload: () -> Unit,
    onRemoveFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("receipt_upload_card"),
        shape = RoundedCornerShape(Dimens.Radius16),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = when (uploadState) {
                is ReceiptUploadState.Uploaded -> EmeraldGreen
                is ReceiptUploadState.Failed -> DangerRed
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.Spacing16),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
        ) {
            Text(
                text = "إيصال الدفع (صورة أو PDF)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            when (uploadState) {
                ReceiptUploadState.Idle -> {
                    Text(
                        text = "يدعم صور الإيصال (JPG / PNG / WEBP) أو ملفات PDF بحد أقصى 5 ميجابايت.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onPickFile,
                        enabled = enabled,
                        shape = RoundedCornerShape(Dimens.Radius12),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("pick_receipt_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(Dimens.Spacing8))
                        Text("اختيار صورة أو ملف PDF", fontWeight = FontWeight.Bold)
                    }
                }

                is ReceiptUploadState.Uploading -> {
                    ReceiptPreviewRow(
                        displayName = uploadState.displayName,
                        mimeType = uploadState.mimeType,
                        sizeBytes = uploadState.sizeBytes,
                        cacheFilePath = uploadState.cacheFilePath
                    )
                    Surface(
                        shape = RoundedCornerShape(Dimens.Radius12),
                        color = PrimaryIndigoLight,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(Dimens.Spacing12),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = PrimaryIndigo
                            )
                            Spacer(modifier = Modifier.width(Dimens.Spacing10))
                            Text(
                                text = "جاري رفع الإيصال إلى الخادم...",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = PrimaryIndigo,
                                modifier = Modifier.testTag("receipt_status_uploading")
                            )
                        }
                    }
                }

                is ReceiptUploadState.Uploaded -> {
                    ReceiptPreviewRow(
                        displayName = uploadState.displayName,
                        mimeType = uploadState.mimeType,
                        sizeBytes = uploadState.sizeBytes,
                        cacheFilePath = uploadState.cacheFilePath
                    )
                    Surface(
                        shape = RoundedCornerShape(Dimens.Radius12),
                        color = EmeraldGreenLight,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(Dimens.Spacing12),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = EmeraldGreen,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(Dimens.Spacing8))
                            Text(
                                text = "تم رفع الإيصال بنجاح",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = EmeraldGreen,
                                modifier = Modifier.testTag("receipt_status_uploaded")
                            )
                        }
                    }
                    ReceiptActionButtons(
                        enabled = enabled,
                        onReplace = onPickFile,
                        onRemove = onRemoveFile
                    )
                }

                is ReceiptUploadState.Failed -> {
                    if (uploadState.displayName.isNotBlank()) {
                        ReceiptPreviewRow(
                            displayName = uploadState.displayName,
                            mimeType = uploadState.mimeType,
                            sizeBytes = uploadState.sizeBytes,
                            cacheFilePath = uploadState.cacheFilePath
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(Dimens.Radius12),
                        color = DangerRedLight,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(Dimens.Spacing12),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = DangerRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(Dimens.Spacing8))
                            Text(
                                text = uploadState.errorMessage,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = DangerRed,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("receipt_status_failed")
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
                    ) {
                        if (uploadState.displayName.isNotBlank() && uploadState.sizeBytes > 0L) {
                            Button(
                                onClick = onRetryUpload,
                                enabled = enabled,
                                shape = RoundedCornerShape(Dimens.Radius12),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("retry_receipt_upload_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(Dimens.Spacing6))
                                Text("إعادة الرفع")
                            }
                        }
                        OutlinedButton(
                            onClick = onPickFile,
                            enabled = enabled,
                            shape = RoundedCornerShape(Dimens.Radius12),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("pick_receipt_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(Dimens.Spacing6))
                            Text("اختيار ملف آخر")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptPreviewRow(
    displayName: String,
    mimeType: String,
    sizeBytes: Long,
    cacheFilePath: String?
) {
    val isPdf = mimeType.equals(ReceiptFileProcessor.MIME_PDF, ignoreCase = true)
    val previewBitmap: ImageBitmap? = remember(cacheFilePath, isPdf) {
        if (isPdf || cacheFilePath.isNullOrBlank()) {
            null
        } else {
            try {
                val file = File(cacheFilePath)
                if (!file.exists()) null
                else {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(Dimens.Radius12))
                .background(if (isPdf) DangerRedLight else PrimaryIndigoLight)
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap,
                    contentDescription = "معاينة الإيصال",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp)
                )
            } else {
                Icon(
                    imageVector = if (isPdf) Icons.Default.PictureAsPdf else Icons.Default.CloudUpload,
                    contentDescription = if (isPdf) "ملف PDF" else "صورة الإيصال",
                    tint = if (isPdf) DangerRed else PrimaryIndigo,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(Dimens.Spacing12))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("receipt_file_name")
            )
            if (sizeBytes > 0L) {
                Text(
                    text = "${if (isPdf) "PDF" else "صورة"} • ${ReceiptFileProcessor.formatFileSizeLabel(sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReceiptActionButtons(
    enabled: Boolean,
    onReplace: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        OutlinedButton(
            onClick = onReplace,
            enabled = enabled,
            shape = RoundedCornerShape(Dimens.Radius12),
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("replace_receipt_button")
        ) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Dimens.Spacing6))
            Text("استبدال")
        }

        TextButton(
            onClick = onRemove,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = DangerRed),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("remove_receipt_button")
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Dimens.Spacing4))
            Text("حذف")
        }
    }
}
