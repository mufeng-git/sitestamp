package com.cscec.sitestamp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
    private var locationRepo: com.cscec.sitestamp.core.LocationRepository? = null
    private var showProjectDialog by androidx.compose.runtime.mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (granted) {
            locationRepo?.requestUpdate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val context = LocalContext.current
                locationRepo = com.cscec.sitestamp.core.LocationRepository(context)

                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CameraScreen(vm = vm, onSwitchToProjects = { showProjectDialog = true })
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
    private fun CameraScreen(vm: MainViewModel, onSwitchToProjects: () -> Unit) {
        val context = LocalContext.current
        val cameraState by vm.cameraState.collectAsState()
        val projects by vm.projects.collectAsState()
        val templates by vm.templates.collectAsState()
        val locationSnapshot by vm.location.collectAsState()
        var previewView by remember { mutableStateOf<PreviewView?>(null) }
        var cameraReady by remember { mutableStateOf(false) }
        // 权限状态直接驱动警告显示：授权后自动消失
        val camPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val showPermissionWarning = !camPermission

        var projectName by remember { mutableStateOf("") }
        var companyName by remember { mutableStateOf("") }
        var showCreateDialog by remember { mutableStateOf(false) }

        // 相机生命周期：previewView 就绪 + ON_RESUME 时绑定
        LaunchedEffect(previewView, context) {
            if (previewView != null) {
                val lifecycleOwner = context as? androidx.activity.ComponentActivity
                if (lifecycleOwner != null) {
                    if (lifecycleOwner.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED) {
                        startCamera(context, lifecycleOwner, previewView!!)
                    }
                }
            }
        }

        // 生命周期 observer：ON_RESUME 时绑定相机 + 刷新定位
        DisposableEffect(context) {
            val lifecycleOwner = context as? androidx.activity.ComponentActivity
            if (lifecycleOwner != null) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        previewView?.let { startCamera(context, lifecycleOwner, it) }
                        locationRepo?.requestUpdate()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            } else {
                onDispose { }
            }
        }

        // 项目选择对话框：切换 / 删除
        if (showProjectDialog) {
            AlertDialog(
                onDismissRequest = { showProjectDialog = false },
                title = { Text("选择项目") },
                text = {
                    if (projects.isEmpty()) {
                        Text("暂无项目，请先新建")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(projects, key = { it.id }) { project ->
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            vm.switchProject(project)
                                            showProjectDialog = false
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(project.name)
                                        if (project.company.isNotBlank()) {
                                            Text(
                                                project.company,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.Gray
                                            )
                                        }
                                    }
                                    if (project.isDefault) {
                                        Text(
                                            "默认",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { vm.deleteProject(project.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "删除项目")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showProjectDialog = false
                        showCreateDialog = true
                    }) { Text("新建项目") }
                },
                dismissButton = {
                    TextButton(onClick = { showProjectDialog = false }) { Text("关闭") }
                }
            )
        }

        // 项目创建对话框
        if (showCreateDialog) {
            AlertDialog(
                onDismissRequest = { showCreateDialog = false },
                title = { Text("新建项目") },
                text = {
                    Column {
                        TextField(
                            value = projectName,
                            onValueChange = { projectName = it },
                            label = { Text("项目名称") },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                        TextField(
                            value = companyName,
                            onValueChange = { companyName = it },
                            label = { Text("公司名称") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.createProject(projectName, companyName)
                        showCreateDialog = false
                        projectName = ""
                        companyName = ""
                    }) { Text("创建") }
                },
                dismissButton = {
                    TextButton(onClick = { showCreateDialog = false }) { Text("取消") }
                }
            )
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

            // 权限警告遮罩
            if (showPermissionWarning) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.85f)
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Camera,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.size(16.dp))
                        Text(
                            "相机权限未授予",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "请在系统设置中允许 SiteStamp 使用相机权限，否则无法拍摄。",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.size(24.dp))
                        Button(onClick = { requestPermissionsIfNeeded() }) {
                            Text("重新请求权限")
                        }
                    }
                }
            }

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
                    val act = context as? androidx.activity.ComponentActivity
                    if (act != null && ::imageCapture.isInitialized) {
                        takePhoto(act, vm)
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

    private fun takePhoto(activity: ComponentActivity, vm: MainViewModel) {
        val originalFile = vm.photoRepoPublic.createOriginalFile()
        imageCapture.takePicture(
            androidx.camera.core.ImageCapture.OutputFileOptions.Builder(
                originalFile
            ).build(),
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val state = vm.cameraState.value
                    val project = state.currentProject ?: run {
                        activity.runOnUiThread {
                            Toast.makeText(activity, "请先创建并选择项目", Toast.LENGTH_LONG).show()
                        }
                        return
                    }
                    val data = WatermarkRenderer.WatermarkData(
                        projectName = project.name,
                        company = project.company,
                        fields = state.currentFields
                            .filter { it.isVisible }
                            .map { it.fieldKey to (state.fieldValues[it.fieldKey] ?: "") },
                        captureTime = WatermarkRenderer.nowTime(),
                        gpsText = vm.locationRepoPublic.formatGps(vm.location.value),
                        addressText = vm.locationRepoPublic.formatAddress(vm.location.value),
                        latitude = vm.location.value.latitude,
                        longitude = vm.location.value.longitude
                    )
                    try {
                        vm.photoRepoPublic.saveWatermarked(originalFile, data)
                        Toast.makeText(activity, "照片已保存", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(activity, "保存失败：${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(activity, "拍摄失败：${exc.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun startCamera(context: android.content.Context, lifecycleOwner: LifecycleOwner, pv: PreviewView) {
        if (cameraProvider != null) return
        android.util.Log.d("SiteStamp", "startCamera: beginning camera init")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                android.util.Log.d("SiteStamp", "startCamera: cameraProvider ready")
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
                android.util.Log.d("SiteStamp", "startCamera: camera bound to lifecycle")
            } catch (e: Exception) {
                android.util.Log.e("SiteStamp", "startCamera: FAILED", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
