package com.example.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.core.config.PaymentSupportConfig
import java.io.File

sealed interface WhatsAppShareResult {
    data object OpenedWhatsApp : WhatsAppShareResult
    data object WhatsAppNotInstalled : WhatsAppShareResult
    data class Failed(val message: String) : WhatsAppShareResult
}

object WhatsAppReceiptSharer {

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun buildSubscriptionWhatsAppMessage(
        teacherName: String,
        planName: String,
        price: Double,
        billingPeriod: String,
        durationDays: Int?,
        transferReference: String?,
        registeredPhone: String?
    ): String {
        val safeTeacher = teacherName.trim().ifBlank { "غير محدد" }
        val safePlan = planName.trim().ifBlank { "غير محددة" }
        val periodLabel = if (billingPeriod == "annual") "سنوي" else "شهري"
        val resolvedDays = durationDays ?: if (billingPeriod == "annual") 365 else 30
        val durationText = "$periodLabel ($resolvedDays يوم)"
        val priceText = if (price <= 0.0) "مجاني" else "${price.toLong()} ج.م"
        val safeAccountPhone = registeredPhone?.trim()?.takeIf { it.isNotBlank() } ?: "غير مسجل"

        return buildString {
            appendLine("السلام عليكم، تم تقديم طلب اشتراك في تطبيق مدار للمدرس:")
            appendLine("• اسم المدرس: $safeTeacher")
            appendLine("• اسم الباقة: $safePlan")
            appendLine("• قيمة الاشتراك: $priceText")
            appendLine("• مدة الاشتراك: $durationText")
            if (!transferReference.isNullOrBlank()) {
                appendLine("• رقم العملية: ${transferReference.trim()}")
            }
            append("• رقم الهاتف المسجل في الحساب: $safeAccountPhone")
        }
    }

    fun buildDirectChatUri(message: String): Uri {
        val waTarget = PaymentSupportConfig.WHATSAPP_TARGET_E164_NO_PLUS
        return Uri.parse("https://wa.me/$waTarget?text=${Uri.encode(message)}")
    }

    fun shareReceiptToWhatsApp(
        context: Context,
        receiptCachePath: String?,
        receiptMimeType: String?,
        message: String
    ): WhatsAppShareResult {
        val shareUri = resolveShareUri(context, receiptCachePath)
        val mime = receiptMimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg"

        for (pkg in WHATSAPP_PACKAGES) {
            val intent = if (shareUri != null) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_TEXT, message)
                    putExtra("jid", PaymentSupportConfig.WHATSAPP_TARGET_JID)
                    clipData = ClipData.newRawUri("", shareUri)
                    setPackage(pkg)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_VIEW, buildDirectChatUri(message)).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                continue
            }

            try {
                context.startActivity(intent)
                return WhatsAppShareResult.OpenedWhatsApp
            } catch (_: ActivityNotFoundException) {
                // Try next WhatsApp package
            } catch (e: Exception) {
                return WhatsAppShareResult.Failed(
                    e.localizedMessage ?: "تعذر فتح تطبيق WhatsApp."
                )
            }
        }

        return WhatsAppShareResult.WhatsAppNotInstalled
    }

    fun openDirectWhatsAppChat(
        context: Context,
        message: String,
        allowBrowserFallback: Boolean = true
    ): WhatsAppShareResult {
        val chatUri = buildDirectChatUri(message)
        for (pkg in WHATSAPP_PACKAGES) {
            val intent = Intent(Intent.ACTION_VIEW, chatUri).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                continue
            }
            try {
                context.startActivity(intent)
                return WhatsAppShareResult.OpenedWhatsApp
            } catch (_: ActivityNotFoundException) {
                // Try next package
            } catch (e: Exception) {
                return WhatsAppShareResult.Failed(e.localizedMessage ?: "تعذر فتح محادثة WhatsApp.")
            }
        }

        if (allowBrowserFallback) {
            val browserIntent = Intent(Intent.ACTION_VIEW, chatUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (browserIntent.resolveActivity(context.packageManager) != null) {
                return try {
                    context.startActivity(browserIntent)
                    WhatsAppShareResult.OpenedWhatsApp
                } catch (_: Exception) {
                    WhatsAppShareResult.WhatsAppNotInstalled
                }
            }
        }

        return WhatsAppShareResult.WhatsAppNotInstalled
    }

    fun shareReceiptViaChooser(
        context: Context,
        receiptCachePath: String?,
        receiptMimeType: String?,
        message: String
    ): Boolean {
        return try {
            val shareUri = resolveShareUri(context, receiptCachePath)
            val mime = receiptMimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg"
            val baseIntent = if (shareUri != null) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_TEXT, message)
                    clipData = ClipData.newRawUri("", shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
            }
            val chooser = Intent.createChooser(baseIntent, "مشاركة إيصال الدفع").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (shareUri != null) {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            context.startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveShareUri(context: Context, receiptCachePath: String?): Uri? {
        if (receiptCachePath.isNullOrBlank()) return null
        val file = File(receiptCachePath)
        if (!file.exists() || !file.isFile) return null
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (_: Exception) {
            null
        }
    }
}
