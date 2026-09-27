package com.bro.lotteryledger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 拍照界面（§2.2 步骤 1 + §3.1 隐私）。
 *
 * 关键：照片**不进系统相册**，只把 Bitmap 交给上层处理，
 * 由 ImagePipeline 写进 App 私有缓存并重新编码（顺带剥掉 EXIF）。
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun CaptureScreen(
    onCaptured: (Bitmap) -> Unit,
    onPickGallery: () -> Unit,
    /** 一次选多张照片批量识别（§8） */
    onBatch: () -> Unit,
    /** 不走识别，直接开一张空白票手工填（复式票的兜底路径） */
    onManual: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("拍摄彩票") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = onPickGallery) {
                        Icon(Icons.Default.PhotoLibrary, "从相册选择")
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "把彩票放平、光线均匀，号码区尽量占满画面",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            imageCapture.takePicture(
                                executor,
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bmp = image.toBitmapSafe()
                                        image.close()
                                        if (bmp != null) {
                                            ContextCompat.getMainExecutor(ctx).execute {
                                                onCaptured(bmp)
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        // 拍照失败不弹崩溃，用户重试即可
                                    }
                                }
                            )
                        },
                        enabled = hasPermission,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) { Text(if (hasPermission) "拍照" else "需要相机权限") }

                    // 两条不用相机的备选路，并排放在拍照按钮下面。
                    // 放在这里而不是首页：用户正是在这一步「拍不出来」或「票太多」，
                    // 抬眼就能看到别的办法，不用退出去找。
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onBatch,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("批量导入照片")
                        }
                        OutlinedButton(
                            onClick = onManual,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("手工填写")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "批量导入：一次选多张照片，逐张自动识别。" +
                            "识别读不出来、或复式票号码太多怕读错时用「手工填写」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (hasPermission) {
                AndroidView(
                    factory = { c ->
                        PreviewView(c).also { pv ->
                            val providerFuture = ProcessCameraProvider.getInstance(c)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(pv.surfaceProvider)
                                }
                                try {
                                    provider.unbindAll()
                                    provider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture
                                    )
                                } catch (_: Exception) { /* 设备无后置相机等 */ }
                            }, ContextCompat.getMainExecutor(c))
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    "没有相机权限，无法拍照。可以点右上角从相册选择。",
                    Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * ImageProxy → Bitmap，并把旋转信息应用到像素上。
 * CameraX 给的 buffer 常是 YUV/JPEG，这里统一走 JPEG 解码路径。
 */
private fun ImageProxy.toBitmapSafe(): Bitmap? {
    return try {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val raw = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

        val matrix = Matrix().apply { postRotate(imageInfo.rotationDegrees.toFloat()) }
        if (imageInfo.rotationDegrees == 0) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    } catch (_: Exception) {
        null
    }
}

/** 把 Bitmap 压成少量字节，仅用于界面上快速预览（真正的识别图由 ImagePipeline 生成）。 */
fun Bitmap.toSmallByteArray(): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 60, out)
    return out.toByteArray()
}
