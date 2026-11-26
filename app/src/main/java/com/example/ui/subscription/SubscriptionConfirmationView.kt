package com.example.ui.subscription

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.AmberWarningLight
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DangerRedLight
import com.example.ui.theme.Dimens
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldGreenLight
import com.example.ui.theme.WhatsAppColor

@Composable
internal fun SubscriptionSuccessConfirmationView(
    confirmation: SubmittedSubscriptionConfirmation,
    transferNumber: String,
    whatsappNotInstalled: Boolean,
    onSendViaWhatsApp: () -> Unit,
    onOpenDirectWhatsAppChat: () -> Unit,
    onCopyWhatsAppMessage: () -> Unit,
    onShareViaOtherApp: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("subscription_confirmation_view"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(EmeraldGreenLight)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "تم تقديم الطلب",
                tint = EmeraldGreen,
                modifier = Modifier.size(46.dp)
            )
        }

        Text(
            text = "تم تقديم طلب الاشتراك بنجاح",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Surface(
            shape = RoundedCornerShape(Dimens.Radius12),
            color = AmberWarningLight,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(Dimens.Spacing14),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassTop,
                    contentDescription = null,
                    tint = AmberWarning,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(Dimens.Spacing10))
                Text(
                    text = "الطلب قيد المراجعة فقط ولم يتم تفعيل الباقة بعد. سيتم التفعيل فور مراجعة الإدارة للإيصال والموافقة على الطلب.",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = Color(0xFF92400E)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimens.Radius16),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier.padding(Dimens.Spacing16),
                verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
            ) {
                SummaryRow("الباقة", confirmation.planName)
                SummaryRow(
                    "قيمة الاشتراك",
                    if (confirmation.isFree) "مجاني" else "${confirmation.price.toLong()} ج.م"
                )
                SummaryRow(
                    "المدة",
                    "${if (confirmation.billingPeriod == "annual") "سنوي" else "شهري"} (${confirmation.durationDays} يوم)"
                )
                if (!confirmation.isFree) {
                    SummaryRow("رقم هاتف المحوّل", confirmation.payerPhone)
                    if (!confirmation.transferReference.isNullOrBlank()) {
                        SummaryRow("رقم العملية", confirmation.transferReference)
                    }
                    if (!confirmation.receiptFileName.isNullOrBlank()) {
                        SummaryRow("الإيصال المرفق", confirmation.receiptFileName)
                    }
                }
            }
        }

        if (!confirmation.isFree) {
            Button(
                onClick = onSendViaWhatsApp,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WhatsAppColor,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(Dimens.Radius16),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("whatsapp_send_receipt_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(Dimens.Spacing8))
                Text(
                    text = "إرسال إيصال الدفع عبر WhatsApp",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            Text(
                text = "سيتم فتح WhatsApp بالرسالة والإيصال المرفق إلى الرقم $transferNumber. يرجى تأكيد الإرسال داخل WhatsApp.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            OutlinedButton(
                onClick = onOpenDirectWhatsAppChat,
                shape = RoundedCornerShape(Dimens.Radius12),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("whatsapp_direct_chat_button")
            ) {
                Text(
                    text = "فتح محادثة WhatsApp ($transferNumber)",
                    fontWeight = FontWeight.SemiBold
                )
            }

            AnimatedVisibility(visible = whatsappNotInstalled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("whatsapp_fallback_card"),
                    shape = RoundedCornerShape(Dimens.Radius16),
                    colors = CardDefaults.cardColors(containerColor = DangerRedLight)
                ) {
                    Column(
                        modifier = Modifier.padding(Dimens.Spacing16),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Spacing10)
                    ) {
                        Text(
                            text = "تعذر فتح تطبيق WhatsApp على هذا الجهاز. يمكنك نسخ نص الرسالة ومشاركة الإيصال عبر أي تطبيق آخر:",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = DangerRed
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
                        ) {
                            OutlinedButton(
                                onClick = onCopyWhatsAppMessage,
                                shape = RoundedCornerShape(Dimens.Radius12),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("copy_whatsapp_message_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(Dimens.Spacing4))
                                Text("نسخ الرسالة")
                            }
                            Button(
                                onClick = onShareViaOtherApp,
                                shape = RoundedCornerShape(Dimens.Radius12),
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("share_receipt_other_app_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(Dimens.Spacing4))
                                Text("مشاركة الإيصال")
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onDone,
            shape = RoundedCornerShape(Dimens.Radius12),
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("confirmation_done_button")
        ) {
            Text("العودة إلى صفحة الاشتراكات", fontWeight = FontWeight.Bold)
        }
    }
}
