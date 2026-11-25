package com.example.ui.attendance

import android.Manifest
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalGetImage::class)
@ExperimentalPermissionsApi
@Composable
fun BarcodeCameraScannerView(
    onBarcodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    if (cameraPermissionState.status.isGranted) {
        CameraPreviewScanner(
            onBarcodeScanned = onBarcodeScanned,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "نحتاج إلى السماح باستخدام الكاميرا\nلمسح رمز QR أو الباركود.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { cameraPermissionState.launchPermissionRequest() }
                ) {
                    Text(text = "السماح بالكاميرا")
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreviewScanner(
    onBarcodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnBarcodeScanned by rememberUpdatedState(onBarcodeScanned)
    val coroutineScope = rememberCoroutineScope()

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Configure ML Kit once for both 2D QR Codes and 1D Code 128 barcodes
    val options = remember {
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128)
            .build()
    }
    val barcodeScanner: BarcodeScanner = remember { BarcodeScanning.getClient(options) }

    // Same-code cooldown (2500ms) while allowing different student codes immediately
    val lastScannedCodeRef = remember { AtomicReference("") }
    val lastScannedTimeRef = remember { AtomicLong(0L) }
    val cameraControlRef = remember { AtomicReference<androidx.camera.core.CameraControl?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var retryNonce by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraControlRef.get()?.enableTorch(false)
            } catch (_: Exception) {}
            try {
                cameraExecutor.shutdown()
                barcodeScanner.close()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (cameraError != null) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = cameraError ?: "تعذر تشغيل الكاميرا",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = {
                    cameraError = null
                    retryNonce++
                }) {
                    Text("إعادة المحاولة")
                }
            }
        } else {
            androidx.compose.runtime.key(retryNonce) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            setOnTouchListener { view, event ->
                                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                    val factory = (view as PreviewView).meteringPointFactory
                                    val point = factory.createPoint(event.x, event.y)
                                    val action = androidx.camera.core.FocusMeteringAction.Builder(
                                        point,
                                        androidx.camera.core.FocusMeteringAction.FLAG_AF
                                    ).build()
                                    cameraControlRef.get()?.startFocusAndMetering(action)
                                    true
                                } else false
                            }
                        }

                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            try {
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                    .build()

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val mediaImage = imageProxy.image
                                    if (mediaImage != null) {
                                        val inputImage = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees
                                        )

                                        barcodeScanner.process(inputImage)
                                            .addOnSuccessListener { barcodes ->
                                                for (barcode in barcodes) {
                                                    val rawValue = barcode.rawValue?.trim() ?: continue
                                                    if (rawValue.isNotBlank()) {
                                                        val currentTime = System.currentTimeMillis()
                                                        val lastCode = lastScannedCodeRef.get()
                                                        val lastTime = lastScannedTimeRef.get()

                                                        if (rawValue != lastCode || (currentTime - lastTime > 2500L)) {
                                                            lastScannedCodeRef.set(rawValue)
                                                            lastScannedTimeRef.set(currentTime)

                                                            coroutineScope.launch {
                                                                currentOnBarcodeScanned(rawValue)
                                                            }
                                                            break
                                                        }
                                                    }
                                                }
                                            }
                                            .addOnFailureListener { e ->
                                                if (com.example.BuildConfig.DEBUG) {
                                                    android.util.Log.w("BarcodeScanner", "Frame analysis failed: ${e.javaClass.simpleName}")
                                                }
                                            }
                                            .addOnCompleteListener {
                                                imageProxy.close()
                                            }
                                    } else {
                                        imageProxy.close()
                                    }
                                }

                                cameraProvider.unbindAll()
                                val camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                                cameraControlRef.set(camera.cameraControl)
                                hasFlashUnit = camera.cameraInfo.hasFlashUnit()
                                camera.cameraControl.setLinearZoom(0f)
                            } catch (_: Exception) {
                                cameraError = "تعذر تشغيل الكاميرا، تأكد من عدم استخدامها في تطبيق آخر"
                            }
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Viewfinder box sized for both QR (square) and Code 128 (horizontal)
            Box(
                modifier = Modifier
                    .size(210.dp, 160.dp)
                    .border(2.dp, Color.Green.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .background(Color.Transparent)
            )

            if (hasFlashUnit) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val control = cameraControlRef.get() ?: return@TextButton
                            val newState = !isTorchOn
                            try {
                                control.enableTorch(newState)
                                isTorchOn = newState
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.testTag("scanner_torch_button")
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                            contentDescription = if (isTorchOn) "إطفاء الفلاش" else "تشغيل الفلاش",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = if (isTorchOn) "إيقاف الفلاش" else "تشغيل الفلاش",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

