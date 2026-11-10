package com.cscec.sitestamp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cscec.sitestamp.core.LocationRepository
import com.cscec.sitestamp.ui.theme.SiteStampTheme
import kotlinx.coroutines.launch

/**
 * 相机宿主：只负责相机硬件、权限与沉浸式窗口，界面全部交给 [CaptureScreen]。
 *
 * 沉浸式口径：窗口延伸到状态栏/导航栏下方（decorFitsSystemWindows=false），
 * 系统栏透明但保留，避免弹层与对话框被遮挡。
 */
class MainActivity : ComponentActivity() {

    private lateinit var imageCapture: ImageCapture
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var boundPreviewView: PreviewView? = null
    private var locationRepo: LocationRepository? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val flashOn = mutableStateOf(false)
    private val permissionGranted = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraOk = result[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        permissionGranted.value = cameraOk
        if (cameraOk) {
            locationRepo?.requestUpdate()
            boundPreviewView?.let { bindCamera(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveWindow()
        permissionGranted.value = hasCameraPermission()
        requestPermissionsIfNeeded()

        setContent {
            SiteStampTheme {
                val vm: MainViewModel = viewModel()
                locationRepo = vm.locationRepoPublic

                val cameraState by vm.cameraState.collectAsState()
                val projects by vm.projects.collectAsState()
                val templates by vm.templates.collectAsState()
                val photos by vm.recentPhotos.collectAsState()

                var previewView by remember { mutableStateOf<PreviewView?>(null) }
                val granted = permissionGranted.value

                LaunchedEffect(previewView, granted) {
                    val pv = previewView
                    if (pv != null && granted) bindCamera(pv)
                }

                DisposableEffect(Unit) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            locationRepo?.requestUpdate()
                            vm.refreshRecentPhotos()
                            if (permissionGranted.value) {
                                boundPreviewView?.let { bindCamera(it) }
                            }
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                CaptureScreen(
                    preview = { modifier ->
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    previewView = this
                                }
                            },
                            modifier = modifier
                        )
                    },
                    state = cameraState,
                    projects = projects,
                    templates = templates,
                    recentPhotos = photos,
                    flashOn = flashOn.value,
                    hasPermission = granted,
                    onShutter = { takePhoto(vm) },
                    onToggleFlash = { toggleFlash() },
                    onSwitchLens = { switchLens() },
                    onFieldChange = { key, value -> vm.updateField(key, value) },
                    onSelectProject = { vm.switchProject(it) },
                    onDeleteProject = { vm.deleteProject(it) },
                    onCreateProject = { name, company -> vm.createProject(name, company) },
                    onSelectTemplate = { vm.switchTemplate(it) },
                    onCreateTemplate = { name, labels -> vm.addTemplate(name, labels) },
                    onDeleteTemplate = { vm.deleteTemplate(it.id) },
                    onOpenGallery = { vm.refreshRecentPhotos() },
                    onDismissBanner = { vm.dismissBanner() },
                    onRequestPermission = { requestPermissionsIfNeeded() },
                    watermarkStyle = cameraState.style,
                    onStyleChange = { vm.updateStyle(it) },
                    onLogoPicked = { vm.importLogo(it) },
                    onClearLogo = { vm.clearLogo() },
                    onFieldsChange = { vm.replaceFields(it) }
                )
            }
        }
    }

    // ---------------------------------------------------------------- 窗口

    private fun enableImmersiveWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    // ---------------------------------------------------------------- 权限

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        val toRequest = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        } else {
            permissionGranted.value = true
        }
    }

    // ---------------------------------------------------------------- 相机

    private fun bindCamera(pv: PreviewView) {
        boundPreviewView = pv
        val context = this
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                    .build()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
                applyTorch()
            } catch (e: Exception) {
                android.util.Log.e("SiteStamp", "bindCamera failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun switchLens() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        boundPreviewView?.let { bindCamera(it) }
    }

    private fun toggleFlash() {
        flashOn.value = !flashOn.value
        applyTorch()
    }

    private fun applyTorch() {
        val c = camera ?: return
        if (!c.cameraInfo.hasFlashUnit()) return
        try {
            c.cameraControl.enableTorch(flashOn.value)
        } catch (e: Exception) {
            android.util.Log.e("SiteStamp", "enableTorch failed", e)
        }
    }

    // ---------------------------------------------------------------- 拍摄

    private fun takePhoto(vm: MainViewModel) {
        if (!::imageCapture.isInitialized) {
            vm.showBanner("相机尚未就绪，请稍候", false)
            return
        }
        vm.beginCapture()
        imageCapture.flashMode = if (flashOn.value) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        val originalFile = vm.createOriginalFile()
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(originalFile).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // 渲染水印 + 写盘 + 落库都在 ViewModel 的 IO 线程完成
                    lifecycleScope.launch {
                        vm.saveAndRecord(originalFile)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    vm.showBanner("拍摄失败：${exc.message}", false)
                }
            }
        )
    }
}
