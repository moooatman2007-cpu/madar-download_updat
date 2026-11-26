package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.example.core.config.PaymentSupportConfig
import com.example.core.model.SubscriptionPlan
import com.example.core.model.Teacher
import com.example.core.model.TeacherSubscription
import com.example.core.model.TeacherSubscriptionRequest
import com.example.data.repository.SubmitSubscriptionResult
import com.example.data.repository.SubscriptionRepository
import com.example.data.repository.TeacherRepository
import com.example.ui.subscription.SubscriptionPlansScreen
import com.example.ui.subscription.SubscriptionPlansViewModel
import com.example.ui.theme.StudentManagerTheme
import com.example.util.ProcessedReceipt
import com.example.util.ReceiptFileProcessor
import com.example.util.WhatsAppReceiptSharer
import com.example.util.WhatsAppShareResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubscriptionReceiptAndWhatsAppRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearFileProviderCache() {
        try {
            val field = FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        } catch (_: Exception) {
        }
    }

    private val freePlan = SubscriptionPlan(
        id = "plan-free",
        code = "free",
        name = "مجاني",
        monthlyPrice = 0.0,
        studentLimit = 20,
        isActive = true
    )

    private val proPlan = SubscriptionPlan(
        id = "plan-pro",
        code = "pro",
        name = "احترافي",
        monthlyPrice = 499.0,
        studentLimit = 500,
        isActive = true
    )

    private class FakeTeacherRepo : TeacherRepository {
        private val teacher = Teacher(
            id = "teacher-1",
            email = "teacher@example.com",
            fullName = "أحمد محمود",
            phoneNumber = "01055566677",
            accountStatus = "active",
            planId = "plan-free",
            subscriptionStatus = "active"
        )
        override fun getCurrentTeacher(): Flow<Teacher?> = MutableStateFlow(teacher)
        override suspend fun fetchCurrentTeacher(): Teacher? = teacher
        override suspend fun updateTeacherProfile(
            fullName: String,
            phoneNumber: String?,
            subject: String?,
            centerName: String?,
            educationalStage: String?,
            avatarUrl: String?
        ): Result<Teacher> = Result.success(teacher)
        override suspend fun uploadAvatar(bytes: ByteArray, fileName: String, mimeType: String): Result<String> =
            Result.success("avatar.jpg")
        override suspend fun deleteAvatar(): Result<Unit> = Result.success(Unit)
    }

    private class FakeSubRepo(
        private val plans: List<SubscriptionPlan>
    ) : SubscriptionRepository {
        var lastTransferRef: String? = "__UNSET__"
        var lastReceiptPath: String? = null

        override suspend fun getPlans(): List<SubscriptionPlan> = plans
        override suspend fun getCurrentSubscription(teacherId: String): TeacherSubscription? = null
        override suspend fun getPendingRequest(teacherId: String): TeacherSubscriptionRequest? = null
        override suspend fun getRequestHistory(teacherId: String): List<TeacherSubscriptionRequest> = emptyList()
        override suspend fun uploadReceipt(bytes: ByteArray, fileName: String, teacherId: String, mimeType: String): Result<String> =
            Result.success("$teacherId/$fileName")
        override suspend fun submitSubscriptionRequest(
            planId: String,
            billingPeriod: String,
            paymentMethod: String,
            payerPhone: String,
            transferReference: String?,
            receiptPath: String,
            idempotencyKey: String
        ): Result<SubmitSubscriptionResult> {
            lastTransferRef = transferReference
            lastReceiptPath = receiptPath
            return Result.success(
                SubmitSubscriptionResult(
                    success = true,
                    requestId = "req-ui-1",
                    status = "pending",
                    durationDays = 30,
                    price = 499.0
                )
            )
        }
    }

    @Test
    fun `ReceiptFileProcessor processes image and PDF URIs and rejects oversized PDFs`() = runTest {
        // 1. Image URI without filename extension -> magic-byte detection + cache file
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val rawOut = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, rawOut)
        bitmap.recycle()

        val sourceImage = File(context.cacheDir, "picker_image_no_ext").apply {
            writeBytes(rawOut.toByteArray())
        }
        val imageUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sourceImage)

        val imgResult = ReceiptFileProcessor.processUri(context, imageUri)
        assertTrue("Expected image processing to succeed: ${imgResult.exceptionOrNull()}", imgResult.isSuccess)
        val processedImg = imgResult.getOrThrow()
        assertFalse(processedImg.isPdf)
        assertEquals(ReceiptFileProcessor.MIME_PNG, processedImg.mimeType)
        assertTrue(processedImg.storageFileName.endsWith(".png"))
        assertNotNull(processedImg.cacheFilePath)
        assertTrue(File(processedImg.cacheFilePath!!).exists())

        // 2. Valid PDF URI via content:// FileProvider (or file:// fallback if cacheDir path rotated)
        val validPdfFile = File(context.cacheDir, "receipt_valid.pdf").apply {
            writeBytes("%PDF-1.4\n1 0 obj\n<<>>\nendobj".toByteArray())
        }
        val validPdfUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", validPdfFile)
        val okPdfResult = ReceiptFileProcessor.processUri(context, validPdfUri)
        assertTrue("Expected PDF processing to succeed: ${okPdfResult.exceptionOrNull()}", okPdfResult.isSuccess)
        val processedPdf = okPdfResult.getOrThrow()
        assertTrue(processedPdf.isPdf)
        assertEquals(ReceiptFileProcessor.MIME_PDF, processedPdf.mimeType)
        assertTrue(processedPdf.storageFileName.endsWith(".pdf"))

        // 3. Oversized (> 5 MB) PDF rejected cleanly
        val oversizedFile = File(context.cacheDir, "receipt_oversized.pdf").apply {
            val bytes = ByteArray(ReceiptFileProcessor.MAX_UPLOAD_BYTES + 1024)
            "%PDF-".toByteArray().copyInto(bytes)
            writeBytes(bytes)
        }
        val bigUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", oversizedFile)
        val failResult = ReceiptFileProcessor.processUri(context, bigUri)
        assertTrue(failResult.isFailure)
        assertTrue(failResult.exceptionOrNull()?.message?.contains("5 ميجابايت") == true)

        ReceiptFileProcessor.cleanupCacheFile(processedImg.cacheFilePath)
        ReceiptFileProcessor.cleanupCacheFile(processedPdf.cacheFilePath)
        sourceImage.delete()
        validPdfFile.delete()
        oversizedFile.delete()
    }

    @Test
    fun `WhatsAppReceiptSharer builds complete Arabic message with international number and optional reference`() {
        val withRef = WhatsAppReceiptSharer.buildSubscriptionWhatsAppMessage(
            teacherName = "أحمد محمود",
            planName = "احترافي",
            price = 499.0,
            billingPeriod = "monthly",
            durationDays = 30,
            transferReference = "REF-7788",
            registeredPhone = "01055566677"
        )
        assertTrue(withRef.contains("أحمد محمود"))
        assertTrue(withRef.contains("احترافي"))
        assertTrue(withRef.contains("499 ج.م"))
        assertTrue(withRef.contains("30 يوم"))
        assertTrue(withRef.contains("REF-7788"))
        assertTrue(withRef.contains("01055566677"))

        val withoutRef = WhatsAppReceiptSharer.buildSubscriptionWhatsAppMessage(
            teacherName = "أحمد محمود",
            planName = "احترافي",
            price = 499.0,
            billingPeriod = "monthly",
            durationDays = 30,
            transferReference = null,
            registeredPhone = "01055566677"
        )
        assertFalse(withoutRef.contains("رقم العملية"))

        val waUri = WhatsAppReceiptSharer.buildDirectChatUri(withoutRef)
        assertEquals("wa.me", waUri.host)
        assertEquals("/${PaymentSupportConfig.WHATSAPP_TARGET_E164_NO_PLUS}", waUri.path)
        assertTrue(waUri.toString().startsWith("https://wa.me/201040129414?text="))
    }

    @Test
    fun `WhatsAppReceiptSharer opens WhatsApp with FileProvider stream when installed and falls back cleanly when missing`() {
        val shareDir = File(context.cacheDir, "receipt_share").apply { mkdirs() }
        val tempReceipt = File(shareDir, "receipt_test.pdf").apply {
            writeBytes("%PDF-1.4\n%test".toByteArray())
        }

        // Case 1: WhatsApp NOT installed
        val missingResult = WhatsAppReceiptSharer.shareReceiptToWhatsApp(
            context = context,
            receiptCachePath = tempReceipt.absolutePath,
            receiptMimeType = ReceiptFileProcessor.MIME_PDF,
            message = "رسالة اختبار"
        )
        assertEquals(WhatsAppShareResult.WhatsAppNotInstalled, missingResult)

        // Chooser fallback works safely
        val chooserResult = WhatsAppReceiptSharer.shareReceiptViaChooser(
            context = context,
            receiptCachePath = tempReceipt.absolutePath,
            receiptMimeType = ReceiptFileProcessor.MIME_PDF,
            message = "رسالة اختبار"
        )
        assertTrue(chooserResult)

        // Case 2: Register com.whatsapp handler directly on ShadowPackageManager for ACTION_SEND
        val shadowPm = shadowOf(context.packageManager)
        val probeIntent = Intent(Intent.ACTION_SEND).apply {
            type = ReceiptFileProcessor.MIME_PDF
            setPackage("com.whatsapp")
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.whatsapp"
                name = "com.whatsapp.ContactPicker"
                exported = true
            }
        }
        shadowPm.addResolveInfoForIntent(probeIntent, resolveInfo)

        val installedResult = WhatsAppReceiptSharer.shareReceiptToWhatsApp(
            context = context,
            receiptCachePath = tempReceipt.absolutePath,
            receiptMimeType = ReceiptFileProcessor.MIME_PDF,
            message = "رسالة اختبار"
        )
        assertEquals(WhatsAppShareResult.OpenedWhatsApp, installedResult)

        val intents = generateSequence { shadowOf(context as android.app.Application).nextStartedActivity }.toList()
        val waIntent = intents.lastOrNull { it.`package` == "com.whatsapp" }
        assertNotNull("Expected ACTION_SEND intent targeting com.whatsapp", waIntent)
        assertEquals(Intent.ACTION_SEND, waIntent?.action)
        assertEquals("application/pdf", waIntent?.type)
        assertEquals("رسالة اختبار", waIntent?.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("201040129414@s.whatsapp.net", waIntent?.getStringExtra("jid"))
        @Suppress("DEPRECATION")
        val streamUri = waIntent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull(streamUri)
        assertTrue((waIntent!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        tempReceipt.delete()
    }

    @Test
    fun `SubscriptionPlansScreen full CUJ displays transfer number, copies it, gates submit on receipt, submits without reference, and shows WhatsApp button`() {
        val repo = FakeSubRepo(listOf(freePlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepo(), ioDispatcher = Dispatchers.Main)

        composeTestRule.setContent {
            StudentManagerTheme {
                SubscriptionPlansScreen(
                    onNavigateBack = {},
                    viewModel = vm
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000L) {
            !vm.uiState.value.isLoading
        }
        composeTestRule.waitForIdle()

        // 1. Transfer number visible at top and copy button copies to clipboard + shows confirmation badge
        composeTestRule.onNodeWithTag("subscription_request_title").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("transfer_number_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("01040129414").assertIsDisplayed()

        // Pause autoAdvance briefly so the 3000ms auto-hide timer doesn't expire during waitForIdle()
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag("copy_transfer_number_button").performScrollTo().performClick()
        composeTestRule.mainClock.advanceTimeBy(300L)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("01040129414", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        composeTestRule.onNodeWithTag("transfer_number_copied_badge").performScrollTo().assertIsDisplayed()
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        // 2. Select pro plan
        composeTestRule.onNodeWithTag("select_plan_button_pro").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // 3. Submit button disabled without uploaded receipt; optional label visible
        composeTestRule.onNodeWithTag("transfer_reference_input").performScrollTo()
        composeTestRule.onNodeWithText("رقم العملية (اختياري)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("submit_subscription_button").performScrollTo().assertIsNotEnabled()

        // 4. Enter payer phone (leave optional transfer reference blank)
        composeTestRule.onNodeWithTag("payer_phone_input").performScrollTo().performTextInput("01012345678")
        composeTestRule.waitForIdle()

        // 5. Simulate receipt upload completion
        vm.setProcessedReceiptAndUpload(
            ProcessedReceipt(
                bytes = "%PDF-1.4\ntest".toByteArray(),
                displayName = "receipt_april.pdf",
                storageFileName = "receipt_123.pdf",
                mimeType = ReceiptFileProcessor.MIME_PDF,
                sizeBytes = 14L,
                cacheFilePath = null
            )
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("receipt_status_uploaded").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("submit_subscription_button").performScrollTo().assertIsEnabled()

        // 6. Submit without transfer reference -> transitions to confirmation + WhatsApp CTA
        composeTestRule.onNodeWithTag("submit_subscription_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(null, repo.lastTransferRef)
        assertEquals("teacher-1/receipt_123.pdf", repo.lastReceiptPath)
        composeTestRule.onNodeWithTag("subscription_confirmation_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("whatsapp_send_receipt_button").performScrollTo().assertIsDisplayed()

        // 7. Tap WhatsApp send button when WhatsApp is not installed -> shows fallback card cleanly
        composeTestRule.onNodeWithTag("whatsapp_send_receipt_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("whatsapp_fallback_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("copy_whatsapp_message_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_receipt_other_app_button").performScrollTo().assertIsDisplayed()
    }
}
