package com.cscec.sitestamp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cscec.sitestamp.core.WatermarkRenderer
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var imageCapture: ImageCapture
    private var cameraProvider: ProcessCameraProvider? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (!granted) {
            // 权限被拒，UI 会提示
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val context = LocalContext.current

                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CameraScreen(vm = vm, onSwitchToProjects = { /* TODO */ },
                            onSwitchToPhotos = { /* TODO */ })
                    }
                }
            }
        }
    }

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
        }
    }

    @Composable
    private fun CameraScreen(vm: MainViewModel, onSwitchToProjects: () -> Unit, onSwitchToPhotos: () -> Unit) {
        val context = LocalContext.current
        val cameraState by vm.cameraState.collectAsState()
        val projects by vm.projects.collectAsState()
        val templates by vm.templates.collectAsState()
        val locationSnapshot by vm.location.collectAsState()
        var previewView by remember { mutableStateOf<PreviewView?>(null) }
        var cameraReady by remember { mutableStateOf(false) }

        // 相机生命周期：预览视图就绪后绑定
        DisposableEffect(Unit) {
            val lifecycleOwner = context as LifecycleOwner
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    previewView?.let { startCamera(context, lifecycleOwner, it) }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // 相机预览
            AndroidView(
                factory = { ctx ->
                    val pv = PreviewView(ctx)
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = pv
                    pv
                },
                modifier = Modifier.fillMaxSize()
            )

            // 顶部栏：项目名 + 切换
            TopAppBar(
                title = { Text(cameraState.currentProject?.name ?: "未选择项目", color = Color.White) },
                actions = {
                    IconButton(onClick = onSwitchToProjects) {
                        Icon(Icons.Default.Settings, contentDescription = "项目")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                    titleContentColor = Color.White, navigationIconContentColor = Color.White)
            )

            // 模板快捷栏
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                templates.forEach { tpl ->
                    FilterChip(
                        selected = cameraState.currentTemplate?.id == tpl.id,
                        onClick = { vm.switchTemplate(tpl) },
                        label = { Text(tpl.name, color = Color.White) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                            labelColor = Color.White
                        )
                    )
                }
            }

            // 字段编辑区
            if (cameraState.currentFields.isNotEmpty()) {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    color = Color.Black.copy(alpha = 0.6f)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        cameraState.currentFields.forEach { field ->
                            val value = cameraState.fieldValues[field.fieldKey] ?: ""
                            TextField(
                                value = value,
                                onValueChange = { vm.updateField(field.fieldKey, it) },
                                label = { Text(field.label, color = Color.White) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // 预览信息条
            Surface(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "${cameraState.currentProject?.company ?: ""} | ${cameraState.captureTime}",
                        color = Color.White, style = MaterialTheme.typography.labelSmall
                    )
                    Text(cameraState.gpsText, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Text(cameraState.addressText, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }

            // 拍摄按钮
            FloatingActionButton(
                onClick = {
                    val result = vm.captureAndSave()
                    if (result != null) {
                        vm.recentPhotos()
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Camera, contentDescription = "拍摄")
            }
        }
    }

    private fun startCamera(context: android.content.Context, lifecycleOwner: LifecycleOwner, pv: PreviewView) {
        if (cameraProvider != null) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3)
                .build()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            cameraProvider = provider
        }, ContextCompat.getMainExecutor(context))
    }
}
