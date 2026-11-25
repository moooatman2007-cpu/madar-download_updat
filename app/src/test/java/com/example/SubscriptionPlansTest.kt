package com.example

import com.example.core.model.SubscriptionPlan
import com.example.core.model.Teacher
import com.example.core.model.TeacherSubscription
import com.example.core.model.TeacherSubscriptionRequest
import com.example.data.repository.SubmitSubscriptionResult
import com.example.data.repository.SubscriptionRepository
import com.example.data.repository.TeacherRepository
import com.example.ui.subscription.SubscriptionPlansViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionPlansTest {

    private val testDispatcher = StandardTestDispatcher()

    private val freePlan = SubscriptionPlan(
        id = "dfb4325b-33b8-4930-9250-8c5a2a389352",
        code = "free",
        name = "مجاني",
        monthlyPrice = 0.0,
        studentLimit = 20,
        features = listOf("الحضور", "الطلاب الأساسيين"),
        isActive = true
    )

    private val teacherPlan = SubscriptionPlan(
        id = "fbf2b56b-f72c-4c95-b248-2c282b6effda",
        code = "teacher",
        name = "مدرس",
        monthlyPrice = 199.0,
        studentLimit = 150,
        features = listOf("Barcode", "الحضور", "التقارير", "المدفوعات"),
        isActive = true
    )

    private val proPlan = SubscriptionPlan(
        id = "b7e0cbc1-2ec9-44b5-81e4-d3ef8810afa9",
        code = "pro",
        name = "احترافي",
        monthlyPrice = 499.0,
        studentLimit = 500,
        features = listOf("واتساب", "تقارير PDF", "تحليلات", "دعم مميز"),
        isActive = true
    )

    private val centerPlan = SubscriptionPlan(
        id = "41f15893-cf8b-4ec6-a962-73efb4ce0c58",
        code = "center",
        name = "مركز",
        monthlyPrice = 999.0,
        studentLimit = 2000,
        features = listOf("عدة مدرسين", "صلاحيات", "اشتراكات", "لوحة إدارة"),
        isActive = true
    )

    private class FakeTeacherRepository(
        private val teacher: Teacher? = Teacher(
            id = "teacher-1",
            email = "teacher@example.com",
            fullName = "أحمد محمد",
            accountStatus = "active",
            planId = "dfb4325b-33b8-4930-9250-8c5a2a389352",
            subscriptionStatus = "active"
        )
    ) : TeacherRepository {
        private val flow = MutableStateFlow(teacher)
        override fun getCurrentTeacher(): Flow<Teacher?> = flow
        override suspend fun fetchCurrentTeacher(): Teacher? = teacher
        override suspend fun updateTeacherProfile(
            fullName: String,
            phoneNumber: String?,
            subject: String?,
            centerName: String?,
            educationalStage: String?,
            avatarUrl: String?
        ): Result<Teacher> = Result.success(teacher!!)
        override suspend fun uploadAvatar(bytes: ByteArray, fileName: String, mimeType: String): Result<String> =
            Result.success("https://example.com/avatar.jpg")
        override suspend fun deleteAvatar(): Result<Unit> = Result.success(Unit)
    }

    private class FakeSubscriptionRepository(
        var plans: List<SubscriptionPlan> = emptyList(),
        var currentSubscription: TeacherSubscription? = null,
        var pendingRequest: TeacherSubscriptionRequest? = null,
        var history: List<TeacherSubscriptionRequest> = emptyList(),
        var uploadResult: Result<String> = Result.success("teacher-1/receipt.jpg"),
        var submitResult: Result<SubmitSubscriptionResult> = Result.success(
            SubmitSubscriptionResult(success = true, requestId = "req-1", status = "pending")
        ),
        var shouldThrowOnLoad: Boolean = false
    ) : SubscriptionRepository {

        var lastSubmittedPlanId: String? = null
        var lastSubmittedBillingPeriod: String? = null
        var lastSubmittedPaymentMethod: String? = null
        var lastSubmittedPayerPhone: String? = null
        var lastSubmittedTransferRef: String? = null
        var lastSubmittedReceiptPath: String? = null
        var lastSubmittedIdempotencyKey: String? = null

        override suspend fun getPlans(): List<SubscriptionPlan> {
            if (shouldThrowOnLoad) throw RuntimeException("Network error")
            return plans
        }

        override suspend fun getCurrentSubscription(teacherId: String): TeacherSubscription? = currentSubscription

        override suspend fun getPendingRequest(teacherId: String): TeacherSubscriptionRequest? = pendingRequest

        override suspend fun getRequestHistory(teacherId: String): List<TeacherSubscriptionRequest> = history

        override suspend fun uploadReceipt(bytes: ByteArray, fileName: String, teacherId: String): Result<String> =
            uploadResult

        override suspend fun submitSubscriptionRequest(
            planId: String,
            billingPeriod: String,
            paymentMethod: String,
            payerPhone: String,
            transferReference: String,
            receiptPath: String,
            idempotencyKey: String
        ): Result<SubmitSubscriptionResult> {
            lastSubmittedPlanId = planId
            lastSubmittedBillingPeriod = billingPeriod
            lastSubmittedPaymentMethod = paymentMethod
            lastSubmittedPayerPhone = payerPhone
            lastSubmittedTransferRef = transferReference
            lastSubmittedReceiptPath = receiptPath
            lastSubmittedIdempotencyKey = idempotencyKey
            return submitResult
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadData filters out center plan and sorts by price`() = runTest {
        val repo = FakeSubscriptionRepository(
            plans = listOf(proPlan, centerPlan, freePlan, teacherPlan)
        )
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository())
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(3, state.plans.size)
        assertTrue(state.plans.none { it.code == "center" })
        assertEquals(listOf("free", "teacher", "pro"), state.plans.map { it.code })
        assertEquals("free", state.currentPlan?.code)
    }

    @Test
    fun `loadData handles network error gracefully`() = runTest {
        val repo = FakeSubscriptionRepository(shouldThrowOnLoad = true)
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository())
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        assertTrue(state.plans.isEmpty())
    }

    @Test
    fun `pending request blocks new submission`() = runTest {
        val pending = TeacherSubscriptionRequest(
            id = "req-pending",
            teacherId = "teacher-1",
            planId = teacherPlan.id,
            status = "pending",
            billingPeriod = "monthly",
            priceAtRequest = 199.0
        )
        val repo = FakeSubscriptionRepository(
            plans = listOf(freePlan, teacherPlan, proPlan),
            pendingRequest = pending
        )
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository())
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hasPendingRequest)
        vm.selectPlan(teacherPlan)
        vm.updatePayerPhone("01012345678")
        vm.updateTransferReference("REF123")
        vm.setReceipt(byteArrayOf(1, 2, 3), "receipt.jpg")
        vm.submitRequest()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.submitErrorMessage)
        assertNull(repo.lastSubmittedPlanId)
    }

    @Test
    fun `paid plan validation requires phone, reference, and receipt`() = runTest {
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository())
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        vm.submitRequest()
        assertNotNull(vm.uiState.value.submitErrorMessage)

        vm.updatePayerPhone("01012345678")
        vm.submitRequest()
        assertNotNull(vm.uiState.value.submitErrorMessage)

        vm.updateTransferReference("TXN-999")
        vm.submitRequest()
        assertNotNull(vm.uiState.value.submitErrorMessage)
        assertNull(repo.lastSubmittedPlanId)
    }

    @Test
    fun `paid plan submission uploads receipt and calls RPC with correct params`() = runTest {
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository())
        advanceUntilIdle()

        vm.selectPlan(proPlan)
        vm.selectBillingPeriod("annual")
        vm.updatePayerPhone("01099887766")
        vm.updateTransferReference("TXN-555")
        vm.setReceipt(byteArrayOf(10, 20, 30), "receipt.png")
        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(proPlan.id, repo.lastSubmittedPlanId)
        assertEquals("annual", repo.lastSubmittedBillingPeriod)
        assertEquals("vodafone_cash", repo.lastSubmittedPaymentMethod)
        assertEquals("01099887766", repo.lastSubmittedPayerPhone)
        assertEquals("TXN-555", repo.lastSubmittedTransferRef)
        assertEquals("teacher-1/receipt.jpg", repo.lastSubmittedReceiptPath)
        assertFalse(repo.lastSubmittedIdempotencyKey.isNullOrBlank())
        assertNotNull(vm.uiState.value.submitSuccessMessage)
    }

    @Test
    fun `free plan submission does not require receipt or phone`() = runTest {
        val teacherOnPro = Teacher(
            id = "teacher-1",
            email = "t@example.com",
            fullName = "أحمد",
            accountStatus = "active",
            planId = proPlan.id
        )
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(teacherOnPro))
        advanceUntilIdle()

        vm.selectPlan(freePlan)
        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(freePlan.id, repo.lastSubmittedPlanId)
        assertEquals("monthly", repo.lastSubmittedBillingPeriod)
        assertEquals("", repo.lastSubmittedReceiptPath)
        assertEquals("", repo.lastSubmittedPayerPhone)
        assertNotNull(vm.uiState.value.submitSuccessMessage)
    }

    @Test
    fun `receipt upload failure prevents RPC call and surfaces error`() = runTest {
        val repo = FakeSubscriptionRepository(
            plans = listOf(freePlan, teacherPlan, proPlan),
            uploadResult = Result.failure(Exception("فشل رفع الإيصال"))
        )
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository())
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        vm.updatePayerPhone("01012345678")
        vm.updateTransferReference("REF-1")
        vm.setReceipt(byteArrayOf(1, 2), "r.jpg")
        vm.submitRequest()
        advanceUntilIdle()

        assertNull(repo.lastSubmittedPlanId)
        assertEquals("فشل رفع الإيصال", vm.uiState.value.submitErrorMessage)
    }
}
