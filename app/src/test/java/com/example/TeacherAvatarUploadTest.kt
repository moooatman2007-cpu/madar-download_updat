package com.example

import com.example.core.model.Teacher
import com.example.data.repository.TeacherRepository
import com.example.ui.profile.TeacherProfileViewModel
import com.example.util.ReceiptFileProcessor
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
class TeacherAvatarUploadTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeTeacherRepository : TeacherRepository {
        private val _teacher = MutableStateFlow<Teacher?>(
            Teacher(id = "teacher-1", fullName = "أحمد", email = "a@b.com")
        )

        var uploadCalls = 0
        var nextUploadResult: Result<String> = Result.success("https://example.supabase.co/storage/v1/object/public/teacher-avatars/teacher-1/avatar.jpg")
        var lastUploadedMimeType: String? = null

        override fun getCurrentTeacher(): Flow<Teacher?> = _teacher
        override suspend fun fetchCurrentTeacher(): Teacher? = _teacher.value
        override suspend fun updateTeacherProfile(
            fullName: String,
            phoneNumber: String?,
            subject: String?,
            centerName: String?,
            educationalStage: String?,
            avatarUrl: String?
        ): Result<Teacher> = Result.success(_teacher.value!!)

        override suspend fun uploadAvatar(bytes: ByteArray, fileName: String, mimeType: String): Result<String> {
            uploadCalls++
            lastUploadedMimeType = mimeType
            return nextUploadResult
        }

        override suspend fun deleteAvatar(): Result<Unit> = Result.success(Unit)
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
    fun `ReceiptFileProcessor rejects empty bytes`() {
        val result = ReceiptFileProcessor.resolveEffectiveMimeType(
            resolverMime = null,
            fileName = "unknown.bin",
            headerBytes = byteArrayOf(0x00, 0x01, 0x02)
        )
        assertNull(result)
    }

    @Test
    fun `ReceiptFileProcessor detects JPEG, PNG, and WEBP from magic bytes`() {
        val jpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val webpHeader = "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)

        assertEquals(ReceiptFileProcessor.MIME_JPEG, ReceiptFileProcessor.resolveEffectiveMimeType(null, null, jpegHeader))
        assertEquals(ReceiptFileProcessor.MIME_PNG, ReceiptFileProcessor.resolveEffectiveMimeType(null, null, pngHeader))
        assertEquals(ReceiptFileProcessor.MIME_WEBP, ReceiptFileProcessor.resolveEffectiveMimeType(null, null, webpHeader))
    }

    @Test
    fun `ReceiptFileProcessor buildSafeStorageFileName produces valid extension`() {
        val jpgName = ReceiptFileProcessor.buildSafeStorageFileName("jpg")
        val pngName = ReceiptFileProcessor.buildSafeStorageFileName("png")
        val webpName = ReceiptFileProcessor.buildSafeStorageFileName("webp")

        assertTrue(jpgName.endsWith(".jpg"))
        assertTrue(pngName.endsWith(".png"))
        assertTrue(webpName.endsWith(".webp"))
    }
}
