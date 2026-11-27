package com.example

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionPlansCujInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

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
        private val teacher = MutableStateFlow<Teacher?>(
            Teacher(id = "teacher-1", fullName = "أحمد", email = "a@b.com", accountStatus = "active", planId = "plan-free")
        )
        override fun getCurrentTeacher(): Flow<Teacher?> = teacher
        override suspend fun fetchCurrentTeacher(): Teacher? = teacher.value
        override suspend fun updateTeacherProfile(
            fullName: String,
            phoneNumber: String?,
            subject: String?,
            centerName: String?,
            educationalStage: String?,
            avatarUrl: String?
        ): Result<Teacher> = Result.success(teacher.value!!)
        override suspend fun uploadAvatar(bytes: ByteArray, fileName: String, mimeType: String): Result<String> =
            Result.success("https://example.com/avatar.jpg")
        override suspend fun deleteAvatar(): Result<Unit> = Result.success(Unit)
    }

    private class FakeSubRepo(private val plans: List<SubscriptionPlan>) : SubscriptionRepository {
        var lastTransferRef: String? = "UNSET"
        var lastReceiptPath: String? = null

        override suspend fun getPlans(): List<SubscriptionPlan> = plans
        override suspend fun getCurrentSubscription(teacherId: String): TeacherSubscription? = null
        override suspend fun getPendingRequest(teacherId: String): TeacherSubscriptionRequest? = null
        override suspend fun getRequestHistory(teacherId: String): List<TeacherSubscriptionRequest> = emptyList()
        override suspend fun uploadReceipt(bytes: ByteArray, fileName: String, teacherId: String, mimeType: String): Result<String> =
            Result.success("$teacherId/$fileName")
        override suspend fun deleteReceipt(receiptPath: String): Result<Unit> = Result.success(Unit)
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
    fun subscriptionPlansScreen_fullCuj_displaysTransferNumber_copiesIt_gatesSubmit_submitsWithoutRef_andShowsWhatsApp() {
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

        composeTestRule.onNodeWithTag("subscription_request_title").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("transfer_number_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("01040129414").assertIsDisplayed()

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.onNodeWithTag("copy_transfer_number_button").performScrollTo().performClick()
        composeTestRule.mainClock.advanceTimeBy(300L)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("01040129414", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        composeTestRule.onNodeWithTag("transfer_number_copied_badge").performScrollTo().assertIsDisplayed()
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("select_plan_button_pro").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("transfer_reference_input").performScrollTo()
        composeTestRule.onNodeWithText("رقم العملية (اختياري)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("submit_subscription_button").performScrollTo().assertIsNotEnabled()

        composeTestRule.onNodeWithTag("payer_phone_input").performScrollTo().performTextInput("01012345678")
        composeTestRule.waitForIdle()

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

        composeTestRule.onNodeWithTag("submit_subscription_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertEquals(null, repo.lastTransferRef)
        assertEquals("teacher-1/receipt_123.pdf", repo.lastReceiptPath)
        composeTestRule.onNodeWithTag("subscription_confirmation_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("whatsapp_send_receipt_button").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("whatsapp_send_receipt_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("whatsapp_fallback_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("copy_whatsapp_message_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_receipt_other_app_button").performScrollTo().assertIsDisplayed()
    }
}
