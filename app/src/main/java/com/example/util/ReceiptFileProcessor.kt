package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

data class ProcessedReceipt(
    val bytes: ByteArray,
    val displayName: String,
    val storageFileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val cacheFilePath: String?
) {
    val isPdf: Boolean get() = mimeType.equals(ReceiptFileProcessor.MIME_PDF, ignoreCase = true)
}

object ReceiptFileProcessor {

    const val MIME_JPEG = "image/jpeg"
    const val MIME_PNG = "image/png"
    const val MIME_WEBP = "image/webp"
    const val MIME_PDF = "application/pdf"

    const val MAX_UPLOAD_BYTES = 5 * 1024 * 1024 // 5 MB
    private const val MAX_RAW_READ_BYTES = 25 * 1024 * 1024 // 25 MB safety cap before compression
    private const val MAX_IMAGE_DIMENSION_PX = 1920
    private const val SHARE_CACHE_DIR = "receipt_share"

    val PICKER_MIME_TYPES = arrayOf("image/*", MIME_PDF)

    private val SUPPORTED_IMAGE_MIMES = setOf(
        MIME_JPEG,
        "image/jpg",
        MIME_PNG,
        MIME_WEBP,
        "image/heic",
        "image/heif",
        "image/bmp"
    )

    suspend fun processUri(
        context: Context,
        uri: Uri,
        ioDispatcher: CoroutineDispatcher = AppDispatchers.io
    ): Result<ProcessedReceipt> = withContext(ioDispatcher) {
        try {
            val contentResolver = context.contentResolver
            var queriedName: String? = null
            var queriedSize: Long? = null

            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) {
                        queriedName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1 && !cursor.isNull(sizeIdx)) {
                        queriedSize = cursor.getLong(sizeIdx)
                    }
                }
            }

            val resolverMime = contentResolver.getType(uri)?.lowercase()?.trim()

            // Early reject obviously oversized PDFs before reading into memory
            if (resolverMime == MIME_PDF && queriedSize != null && queriedSize!! > MAX_UPLOAD_BYTES) {
                return@withContext Result.failure(
                    IllegalArgumentException("حجم ملف PDF يتجاوز الحد المسموح به (5 ميجابايت).")
                )
            }

            val rawBytes = contentResolver.openInputStream(uri)?.use { input ->
                readStreamWithLimit(input, MAX_RAW_READ_BYTES)
            } ?: return@withContext Result.failure(
                IOException("تعذر فتح الملف المحدد. يرجى اختيار الملف مرة أخرى.")
            )

            if (rawBytes.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("الملف المختار فارغ أو تالف.")
                )
            }

            val detectedMime = resolveEffectiveMimeType(
                resolverMime = resolverMime,
                fileName = queriedName ?: uri.lastPathSegment,
                headerBytes = rawBytes
            ) ?: return@withContext Result.failure(
                IllegalArgumentException("نوع الملف غير مدعوم. يرجى اختيار صورة (JPG, PNG, WEBP) أو ملف PDF.")
            )

            val processed = if (detectedMime == MIME_PDF) {
                if (rawBytes.size > MAX_UPLOAD_BYTES) {
                    return@withContext Result.failure(
                        IllegalArgumentException("حجم ملف PDF يتجاوز الحد المسموح به (5 ميجابايت).")
                    )
                }
                val displayName = ensureDisplayName(queriedName, "receipt.pdf", "pdf")
                val storageFileName = buildSafeStorageFileName("pdf")
                val cachePath = writeTempShareFile(context, storageFileName, rawBytes)
                ProcessedReceipt(
                    bytes = rawBytes,
                    displayName = displayName,
                    storageFileName = storageFileName,
                    mimeType = MIME_PDF,
                    sizeBytes = rawBytes.size.toLong(),
                    cacheFilePath = cachePath
                )
            } else {
                val (finalBytes, finalMime, ext) = compressImageIfNeeded(rawBytes, detectedMime)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("تعذر قراءة الصورة المختارة. يرجى اختيار صورة صالحة.")
                    )

                if (finalBytes.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("تعذر معالجة الصورة المختارة.")
                    )
                }
                if (finalBytes.size > MAX_UPLOAD_BYTES) {
                    return@withContext Result.failure(
                        IllegalArgumentException("حجم الصورة بعد الضغط ما زال يتجاوز 5 ميجابايت.")
                    )
                }

                val displayName = ensureDisplayName(queriedName, "receipt.$ext", ext)
                val storageFileName = buildSafeStorageFileName(ext)
                val cachePath = writeTempShareFile(context, storageFileName, finalBytes)
                ProcessedReceipt(
                    bytes = finalBytes,
                    displayName = displayName,
                    storageFileName = storageFileName,
                    mimeType = finalMime,
                    sizeBytes = finalBytes.size.toLong(),
                    cacheFilePath = cachePath
                )
            }

            Result.success(processed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            Result.failure(SecurityException("لا توجد صلاحية لقراءة الملف المحدد. يرجى إعادة المحاولة."))
        } catch (_: OutOfMemoryError) {
            Result.failure(IllegalArgumentException("الصورة كبيرة جداً ولا يمكن معالجتها. يرجى اختيار صورة أصغر."))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (_: Exception) {
            Result.failure(IOException("تعذر قراءة الملف المختار. يرجى اختيار صورة أو ملف PDF صالح."))
        }
    }

    fun cleanupCacheFile(cacheFilePath: String?) {
        if (cacheFilePath.isNullOrBlank()) return
        try {
            val file = File(cacheFilePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun resolveEffectiveMimeType(
        resolverMime: String?,
        fileName: String?,
        headerBytes: ByteArray
    ): String? {
        val normalizedResolver = resolverMime?.lowercase()?.trim()
        val magicMime = detectMimeTypeFromBytes(headerBytes)

        // Magic bytes take priority when unambiguous so mislabeled URIs work reliably
        if (magicMime != null) {
            return magicMime
        }

        if (normalizedResolver == MIME_PDF) return MIME_PDF
        if (normalizedResolver != null && normalizedResolver in SUPPORTED_IMAGE_MIMES) {
            return if (normalizedResolver == "image/jpg") MIME_JPEG else normalizedResolver
        }

        val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.trim()
        return when (ext) {
            "pdf" -> MIME_PDF
            "jpg", "jpeg" -> MIME_JPEG
            "png" -> MIME_PNG
            "webp" -> MIME_WEBP
            "heic", "heif", "bmp" -> MIME_JPEG
            else -> null
        }
    }

    fun detectMimeTypeFromBytes(bytes: ByteArray): String? {
        if (bytes.size >= 5) {
            // %PDF-
            if (bytes[0] == 0x25.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x44.toByte() &&
                bytes[3] == 0x46.toByte() &&
                bytes[4] == 0x2D.toByte()
            ) {
                return MIME_PDF
            }
        }
        if (bytes.size >= 3) {
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) {
                return MIME_JPEG
            }
        }
        if (bytes.size >= 8) {
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if (bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == 0x0A.toByte()
            ) {
                return MIME_PNG
            }
        }
        if (bytes.size >= 12) {
            // WEBP: RIFF....WEBP
            if (bytes[0] == 0x52.toByte() &&
                bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() &&
                bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() &&
                bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() &&
                bytes[11] == 0x50.toByte()
            ) {
                return MIME_WEBP
            }
        }
        return null
    }

    fun buildSafeStorageFileName(extension: String, nowMs: Long = System.currentTimeMillis()): String {
        val cleanExt = extension.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "jpg" }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "receipt_${nowMs}_$suffix.$cleanExt"
    }

    fun formatFileSizeLabel(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> "${(bytes / 1024L).coerceAtLeast(1)} KB"
            else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun calculateInSampleSize(width: Int, height: Int, maxDim: Int = MAX_IMAGE_DIMENSION_PX): Int {
        var inSampleSize = 1
        if (height > maxDim || width > maxDim) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= maxDim && (halfWidth / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun readStreamWithLimit(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("حجم الملف المختار كبير جداً (الحد الأقصى 25 ميجابايت قبل المعالجة).")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun ensureDisplayName(rawName: String?, fallback: String, ext: String): String {
        val cleaned = rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return fallback

        return if (cleaned.contains('.')) cleaned else "$cleaned.$ext"
    }

    private fun writeTempShareFile(context: Context, fileName: String, bytes: ByteArray): String? {
        return try {
            val shareDir = File(context.cacheDir, SHARE_CACHE_DIR)
            if (!shareDir.exists()) {
                shareDir.mkdirs()
            }
            // Clear stale previous receipt temp files
            shareDir.listFiles()?.forEach { existing ->
                try {
                    if (existing.isFile) existing.delete()
                } catch (_: Exception) {
                }
            }
            val file = File(shareDir, fileName)
            FileOutputStream(file).use { fos ->
                fos.write(bytes)
                fos.flush()
            }
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun compressImageIfNeeded(
        rawBytes: ByteArray,
        detectedMime: String
    ): Triple<ByteArray, String, String>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val rotationDegrees = readExifRotationDegrees(rawBytes)

        // Preserve compact PNG/WEBP without re-encoding when already crisp and small
        if ((detectedMime == MIME_PNG || detectedMime == MIME_WEBP) &&
            rawBytes.size <= 1_024 * 1024 &&
            bounds.outWidth <= MAX_IMAGE_DIMENSION_PX &&
            bounds.outHeight <= MAX_IMAGE_DIMENSION_PX &&
            rotationDegrees == 0
        ) {
            val ext = if (detectedMime == MIME_PNG) "png" else "webp"
            return Triple(rawBytes, detectedMime, ext)
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION_PX)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts) ?: return null

        try {
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                    bitmap = rotated
                }
            }

            val maxSide = maxOf(bitmap.width, bitmap.height)
            if (maxSide > MAX_IMAGE_DIMENSION_PX) {
                val scale = MAX_IMAGE_DIMENSION_PX.toFloat() / maxSide.toFloat()
                val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (scaled != bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            // Encode JPEG with high legibility for receipt text; only lower quality if size > 1.8 MB
            val qualitySteps = intArrayOf(88, 78, 68, 55)
            var bestBytes: ByteArray? = null
            for (q in qualitySteps) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
                val candidate = out.toByteArray()
                bestBytes = candidate
                if (candidate.size <= 1_800 * 1024) {
                    break
                }
            }

            val finalBytes = bestBytes ?: return null
            return Triple(finalBytes, MIME_JPEG, "jpg")
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun readExifRotationDegrees(rawBytes: ByteArray): Int {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(rawBytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }
    }
}
