package com.example

import com.example.core.config.PaymentSupportConfig
import com.example.core.model.SubscriptionPlan
import com.example.core.model.Teacher
import com.example.core.model.TeacherSubscription
import com.example.core.model.TeacherSubscriptionRequest
import com.example.data.repository.SubmitSubscriptionResult
import com.example.data.repository.SubscriptionRepository
import com.example.data.repository.SupabaseSubscriptionRepository
import com.example.data.repository.TeacherRepository
import com.example.ui.subscription.ReceiptUploadState
import com.example.ui.subscription.SubscriptionPlansViewModel
import com.example.util.ProcessedReceipt
import com.example.util.ReceiptFileProcessor
import com.sun.net.httpserver.HttpServer
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

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
            phoneNumber = "01022334455",
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
        var uploadHandler: suspend (ByteArray, String, String, String) -> Result<String> = { _, fileName, teacherId, _ ->
            Result.success("$teacherId/$fileName")
        },
        var submitDelayMs: Long = 0L,
        var submitResult: Result<SubmitSubscriptionResult> = Result.success(
            SubmitSubscriptionResult(
                success = true,
                requestId = "req-1",
                status = "pending",
                durationDays = 30,
                price = 199.0
            )
        ),
        var shouldThrowOnLoad: Boolean = false
    ) : SubscriptionRepository {

        val uploadCallCount = AtomicInteger(0)
        val submitCallCount = AtomicInteger(0)
        val deletedReceiptPaths = mutableListOf<String>()

        var lastUploadedFileName: String? = null
        var lastUploadedMimeType: String? = null
        var lastSubmittedPlanId: String? = null
        var lastSubmittedBillingPeriod: String? = null
        var lastSubmittedPaymentMethod: String? = null
        var lastSubmittedPayerPhone: String? = null
        var lastSubmittedTransferRef: String? = "__UNSET__"
        var lastSubmittedReceiptPath: String? = null
        var lastSubmittedIdempotencyKey: String? = null

        override suspend fun getPlans(): List<SubscriptionPlan> {
            if (shouldThrowOnLoad) throw RuntimeException("Network error")
            return plans
        }

        override suspend fun getCurrentSubscription(teacherId: String): TeacherSubscription? = currentSubscription

        override suspend fun getPendingRequest(teacherId: String): TeacherSubscriptionRequest? = pendingRequest

        override suspend fun getRequestHistory(teacherId: String): List<TeacherSubscriptionRequest> = history

        override suspend fun uploadReceipt(
            bytes: ByteArray,
            fileName: String,
            teacherId: String,
            mimeType: String
        ): Result<String> {
            uploadCallCount.incrementAndGet()
            lastUploadedFileName = fileName
            lastUploadedMimeType = mimeType
            return uploadHandler(bytes, fileName, teacherId, mimeType)
        }

        override suspend fun deleteReceipt(receiptPath: String): Result<Unit> {
            deletedReceiptPaths.add(receiptPath)
            return Result.success(Unit)
        }

        override suspend fun submitSubscriptionRequest(
            planId: String,
            billingPeriod: String,
            paymentMethod: String,
            payerPhone: String,
            transferReference: String?,
            receiptPath: String,
            idempotencyKey: String
        ): Result<SubmitSubscriptionResult> {
            submitCallCount.incrementAndGet()
            if (submitDelayMs > 0L) {
                delay(submitDelayMs)
            }
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

    private fun sampleProcessedImage(name: String = "receipt.jpg"): ProcessedReceipt = ProcessedReceipt(
        bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3),
        displayName = name,
        storageFileName = "receipt_1700000000_abcd1234.jpg",
        mimeType = ReceiptFileProcessor.MIME_JPEG,
        sizeBytes = 7L,
        cacheFilePath = null
    )

    private fun sampleProcessedPdf(name: String = "invoice.pdf"): ProcessedReceipt = ProcessedReceipt(
        bytes = "%PDF-1.4\n%fake-pdf".toByteArray(),
        displayName = name,
        storageFileName = "receipt_1700000000_pdf12345.pdf",
        mimeType = ReceiptFileProcessor.MIME_PDF,
        sizeBytes = 18L,
        cacheFilePath = null
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `central transfer number matches required configuration`() {
        assertEquals("01040129414", PaymentSupportConfig.TRANSFER_NUMBER)
        assertEquals("201040129414", PaymentSupportConfig.WHATSAPP_TARGET_E164_NO_PLUS)
        assertEquals("201040129414@s.whatsapp.net", PaymentSupportConfig.WHATSAPP_TARGET_JID)
    }

    @Test
    fun `loadData filters out center plan and sorts by price`() = runTest {
        val repo = FakeSubscriptionRepository(
            plans = listOf(proPlan, centerPlan, freePlan, teacherPlan)
        )
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(3, state.plans.size)
        assertTrue(state.plans.none { it.code == "center" })
        assertEquals(listOf("free", "teacher", "pro"), state.plans.map { it.code })
        assertEquals("free", state.currentPlan?.code)
        assertEquals(PaymentSupportConfig.TRANSFER_NUMBER, state.transferNumber)
    }

    @Test
    fun `loadData handles network error gracefully`() = runTest {
        val repo = FakeSubscriptionRepository(shouldThrowOnLoad = true)
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.errorMessage)
        assertTrue(state.plans.isEmpty())
    }

    @Test
    fun `image receipt upload succeeds and unlocks paid plan submission`() = runTest {
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        assertFalse(vm.uiState.value.canSubmitRequest)

        val receipt = sampleProcessedImage("camera_receipt.jpg")
        vm.setProcessedReceiptAndUpload(receipt)
        advanceUntilIdle()

        val uploadState = vm.uiState.value.receiptUploadState
        assertTrue(uploadState is ReceiptUploadState.Uploaded)
        uploadState as ReceiptUploadState.Uploaded
        assertEquals("teacher-1/${receipt.storageFileName}", uploadState.storagePath)
        assertEquals(ReceiptFileProcessor.MIME_JPEG, repo.lastUploadedMimeType)
        assertTrue(vm.uiState.value.canSubmitRequest)
    }

    @Test
    fun `pdf receipt upload succeeds and passes pdf storage path to RPC`() = runTest {
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(proPlan)
        vm.updatePayerPhone("01099887766")

        val pdfReceipt = sampleProcessedPdf("bank_transfer.pdf")
        vm.setProcessedReceiptAndUpload(pdfReceipt)
        advanceUntilIdle()

        assertEquals(ReceiptFileProcessor.MIME_PDF, repo.lastUploadedMimeType)
        val expectedPath = "teacher-1/${pdfReceipt.storageFileName}"
        assertTrue(expectedPath.endsWith(".pdf"))

        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(expectedPath, repo.lastSubmittedReceiptPath)
        assertNull(repo.lastSubmittedTransferRef)
        assertNotNull(vm.uiState.value.submittedConfirmation)
    }

    @Test
    fun `receipt upload failure when offline shows failed state and blocks RPC`() = runTest {
        val repo = FakeSubscriptionRepository(
            plans = listOf(freePlan, teacherPlan, proPlan),
            uploadHandler = { _, _, _, _ ->
                Result.failure(Exception("لا يوجد اتصال بالإنترنت، تعذر رفع الإيصال."))
            }
        )
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        vm.updatePayerPhone("01012345678")
        vm.setProcessedReceiptAndUpload(sampleProcessedImage())
        advanceUntilIdle()

        val uploadState = vm.uiState.value.receiptUploadState
        assertTrue(uploadState is ReceiptUploadState.Failed)
        assertEquals("لا يوجد اتصال بالإنترنت، تعذر رفع الإيصال.", (uploadState as ReceiptUploadState.Failed).errorMessage)
        assertFalse(vm.uiState.value.canSubmitRequest)

        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(0, repo.submitCallCount.get())
        assertNull(repo.lastSubmittedPlanId)
        assertNotNull(vm.uiState.value.submitErrorMessage)
    }

    @Test
    fun `cannot submit paid subscription without uploaded receipt`() = runTest {
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        vm.updatePayerPhone("01012345678")
        vm.updateTransferReference("REF-999")

        assertFalse(vm.uiState.value.canSubmitRequest)
        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(0, repo.submitCallCount.get())
        assertNull(repo.lastSubmittedPlanId)
        assertNotNull(vm.uiState.value.submitErrorMessage)
    }

    @Test
    fun `cannot submit paid subscription while receipt is still uploading`() = runTest {
        val uploadGate = CompletableDeferred<Unit>()
        val repo = FakeSubscriptionRepository(
            plans = listOf(freePlan, teacherPlan, proPlan),
            uploadHandler = { _, fileName, teacherId, _ ->
                uploadGate.await()
                Result.success("$teacherId/$fileName")
            }
        )
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        vm.updatePayerPhone("01012345678")
        vm.setProcessedReceiptAndUpload(sampleProcessedImage())
        testDispatcher.scheduler.runCurrent()

        assertTrue(vm.uiState.value.receiptUploadState is ReceiptUploadState.Uploading)
        assertFalse(vm.uiState.value.canSubmitRequest)

        vm.submitRequest()
        testDispatcher.scheduler.runCurrent()
        assertEquals(0, repo.submitCallCount.get())

        uploadGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.receiptUploadState is ReceiptUploadState.Uploaded)
    }

    @Test
    fun `submit with transfer reference sends trimmed reference to RPC`() = runTest {
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(proPlan)
        vm.selectBillingPeriod("annual")
        vm.updatePayerPhone("01099887766")
        vm.updateTransferReference("  TXN-555  ")
        val receipt = sampleProcessedImage()
        vm.setProcessedReceiptAndUpload(receipt)
        advanceUntilIdle()

        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(proPlan.id, repo.lastSubmittedPlanId)
        assertEquals("annual", repo.lastSubmittedBillingPeriod)
        assertEquals("01099887766", repo.lastSubmittedPayerPhone)
        assertEquals("TXN-555", repo.lastSubmittedTransferRef)
        assertEquals("teacher-1/${receipt.storageFileName}", repo.lastSubmittedReceiptPath)
        assertEquals("TXN-555", vm.uiState.value.submittedConfirmation?.transferReference)
    }

    @Test
    fun `submit without transfer reference succeeds and sends null to RPC`() = runTest {
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        vm.updatePayerPhone("01012345678")
        vm.updateTransferReference("   ")
        val receipt = sampleProcessedImage()
        vm.setProcessedReceiptAndUpload(receipt)
        advanceUntilIdle()

        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(1, repo.submitCallCount.get())
        assertNull(repo.lastSubmittedTransferRef)
        assertEquals("teacher-1/${receipt.storageFileName}", repo.lastSubmittedReceiptPath)
        val confirmation = vm.uiState.value.submittedConfirmation
        assertNotNull(confirmation)
        assertNull(confirmation?.transferReference)
        assertEquals("أحمد محمد", confirmation?.teacherName)
        assertEquals("01022334455", confirmation?.teacherRegisteredPhone)
    }

    @Test
    fun `double tap on submitRequest triggers RPC only once`() = runTest {
        val repo = FakeSubscriptionRepository(
            plans = listOf(freePlan, teacherPlan, proPlan),
            submitDelayMs = 500L
        )
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        vm.updatePayerPhone("01012345678")
        vm.setProcessedReceiptAndUpload(sampleProcessedImage())
        advanceUntilIdle()

        vm.submitRequest()
        assertTrue(vm.uiState.value.isSubmitting)
        vm.submitRequest()
        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(1, repo.submitCallCount.get())
    }

    @Test
    fun `replacing or removing receipt deletes old remote object path`() = runTest {
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(teacherPlan)
        val first = sampleProcessedImage("first.jpg")
        vm.setProcessedReceiptAndUpload(first)
        advanceUntilIdle()

        val second = sampleProcessedPdf("second.pdf")
        vm.setProcessedReceiptAndUpload(second)
        advanceUntilIdle()

        assertEquals(listOf("teacher-1/${first.storageFileName}"), repo.deletedReceiptPaths)

        vm.removeReceipt()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.receiptUploadState is ReceiptUploadState.Idle)
        assertEquals(
            listOf("teacher-1/${first.storageFileName}", "teacher-1/${second.storageFileName}"),
            repo.deletedReceiptPaths
        )
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
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hasPendingRequest)
        vm.selectPlan(teacherPlan)
        vm.updatePayerPhone("01012345678")
        vm.setProcessedReceiptAndUpload(sampleProcessedImage())
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canSubmitRequest)
        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(0, repo.submitCallCount.get())
        assertNotNull(vm.uiState.value.submitErrorMessage)
    }

    @Test
    fun `free plan submission does not require receipt or phone and sends null reference`() = runTest {
        val teacherOnPro = Teacher(
            id = "teacher-1",
            email = "t@example.com",
            fullName = "أحمد",
            accountStatus = "active",
            planId = proPlan.id
        )
        val repo = FakeSubscriptionRepository(plans = listOf(freePlan, teacherPlan, proPlan))
        val vm = SubscriptionPlansViewModel(repo, FakeTeacherRepository(teacherOnPro), ioDispatcher = testDispatcher)
        advanceUntilIdle()

        vm.selectPlan(freePlan)
        vm.submitRequest()
        advanceUntilIdle()

        assertEquals(freePlan.id, repo.lastSubmittedPlanId)
        assertEquals("monthly", repo.lastSubmittedBillingPeriod)
        assertEquals("", repo.lastSubmittedReceiptPath)
        assertEquals("", repo.lastSubmittedPayerPhone)
        assertNull(repo.lastSubmittedTransferRef)
        assertTrue(vm.uiState.value.submittedConfirmation?.isFree == true)
    }

    @Test
    fun `supabase repository uploads image and PDF with explicit Content-Type, x-upsert false, and sends explicit null transfer_reference to RPC`() = runBlocking {
        var capturedUploadUri: String? = null
        var capturedUploadMethod: String? = null
        var capturedUploadContentType: String? = null
        var capturedUploadUpsertHeader: String? = null
        var capturedUploadBytes: ByteArray? = null
        var capturedRpcBody: String? = null

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/storage/v1/object/") { exchange ->
            capturedUploadUri = exchange.requestURI.path
            capturedUploadMethod = exchange.requestMethod
            capturedUploadContentType = exchange.requestHeaders.getFirst("Content-Type")
            capturedUploadUpsertHeader = exchange.requestHeaders.getFirst("x-upsert")
            capturedUploadBytes = exchange.requestBody.readBytes()
            val responseJson = """{"Key":"subscription-receipts/ok","Id":"1"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseJson.size.toLong())
            exchange.responseBody.use { it.write(responseJson) }
        }
        server.createContext("/rest/v1/rpc/submit_teacher_subscription_request") { exchange ->
            capturedRpcBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val responseJson = """{"success":true,"request_id":"req-srv-1","status":"pending","duration_days":30,"price":199.0}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseJson.size.toLong())
            exchange.responseBody.use { it.write(responseJson) }
        }
        server.start()

        val client = createSupabaseClient(
            supabaseUrl = "http://127.0.0.1:${server.address.port}",
            supabaseKey = "test-publishable-key"
        ) {
            install(Postgrest)
            install(Storage)
            defaultSerializer = KotlinXSerializer(Json { ignoreUnknownKeys = true; isLenient = true })
        }

        try {
            val repo = SupabaseSubscriptionRepository(
                client = client,
                teacherIdProvider = { "teacher-123" },
                sessionActiveChecker = { true },
                ioDispatcher = Dispatchers.IO
            )

            val pdfBytes = "%PDF-1.4\n1 0 obj\n".toByteArray()
            val pdfUploadResult = repo.uploadReceipt(
                bytes = pdfBytes,
                fileName = "receipt_no_ext",
                teacherId = "teacher-123",
                mimeType = ReceiptFileProcessor.MIME_PDF
            )
            assertTrue(
                "PDF upload failed: ${pdfUploadResult.exceptionOrNull()} cause=${pdfUploadResult.exceptionOrNull()?.cause}",
                pdfUploadResult.isSuccess
            )
            val storedPdfPath = pdfUploadResult.getOrThrow()
            assertTrue(storedPdfPath.startsWith("teacher-123/"))
            assertTrue(storedPdfPath.endsWith(".pdf"))
            assertFalse("Must return private object path, never public URL", storedPdfPath.contains("http"))
            assertEquals("/storage/v1/object/subscription-receipts/$storedPdfPath", capturedUploadUri)
            assertTrue(capturedUploadMethod == "POST" || capturedUploadMethod == "PUT")
            assertEquals("application/pdf", capturedUploadContentType?.substringBefore(';')?.trim()?.lowercase())
            assertEquals("false", capturedUploadUpsertHeader?.lowercase())
            assertArrayEquals(pdfBytes, capturedUploadBytes)

            val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 42)
            val imgUploadResult = repo.uploadReceipt(
                bytes = jpegBytes,
                fileName = "10000123",
                teacherId = "teacher-123",
                mimeType = ReceiptFileProcessor.MIME_JPEG
            )
            assertTrue(
                "Image upload failed: ${imgUploadResult.exceptionOrNull()} cause=${imgUploadResult.exceptionOrNull()?.cause}",
                imgUploadResult.isSuccess
            )
            val storedImgPath = imgUploadResult.getOrThrow()
            assertTrue(storedImgPath.endsWith(".jpg"))
            assertEquals("image/jpeg", capturedUploadContentType?.substringBefore(';')?.trim()?.lowercase())
            assertEquals("false", capturedUploadUpsertHeader?.lowercase())

            val rpcResult = repo.submitSubscriptionRequest(
                planId = teacherPlan.id,
                billingPeriod = "monthly",
                paymentMethod = "vodafone_cash",
                payerPhone = "01012345678",
                transferReference = null,
                receiptPath = storedImgPath,
                idempotencyKey = "idem-1"
            )
            assertTrue("RPC failed: ${rpcResult.exceptionOrNull()}", rpcResult.isSuccess)
            assertEquals("req-srv-1", rpcResult.getOrThrow().requestId)

            val rpcJson = Json.parseToJsonElement(capturedRpcBody!!).jsonObject
            assertEquals(teacherPlan.id, rpcJson["p_plan_id"]?.jsonPrimitive?.content)
            assertEquals(storedImgPath, rpcJson["p_receipt_path"]?.jsonPrimitive?.content)
            assertTrue(
                "p_transfer_reference must be explicitly null in RPC payload",
                rpcJson.containsKey("p_transfer_reference") && rpcJson["p_transfer_reference"] is JsonNull
            )
        } finally {
            client.close()
            server.stop(0)
        }
    }

    @Test
    fun `supabase repository upload fails cleanly when server returns 403 or network error`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/storage/v1/object/") { exchange ->
            val err = """{"statusCode":"403","error":"Unauthorized","message":"new row violates row-level security policy"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(403, err.size.toLong())
            exchange.responseBody.use { it.write(err) }
        }
        server.start()

        val client = createSupabaseClient(
            supabaseUrl = "http://127.0.0.1:${server.address.port}",
            supabaseKey = "test-publishable-key"
        ) {
            install(Postgrest)
            install(Storage)
        }

        try {
            val repo = SupabaseSubscriptionRepository(
                client = client,
                teacherIdProvider = { "teacher-123" },
                sessionActiveChecker = { true },
                ioDispatcher = Dispatchers.IO
            )
            val result = repo.uploadReceipt(
                bytes = byteArrayOf(1, 2, 3),
                fileName = "receipt.jpg",
                teacherId = "teacher-123",
                mimeType = ReceiptFileProcessor.MIME_JPEG
            )
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull()?.message.isNullOrBlank())

            // Also verify empty bytes / >5MB fail before hitting network
            val tooLarge = ByteArray(ReceiptFileProcessor.MAX_UPLOAD_BYTES + 1)
            val sizeErr = repo.uploadReceipt(tooLarge, "big.jpg", "teacher-123", ReceiptFileProcessor.MIME_JPEG)
            assertTrue(sizeErr.isFailure)
            assertTrue(sizeErr.exceptionOrNull()?.message?.contains("5 ميجابايت") == true)
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
