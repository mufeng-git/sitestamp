package com.cscec.sitestamp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true
        if (cameraGranted) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    CameraScreen(vm = vm)
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
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    @Composable
    private fun CameraScreen(vm: MainViewModel) {
        val context = LocalContext.current
        val cameraState by vm.cameraState.collectAsState()
        val projects by vm.projects.collectAsState()
        val templates by vm.templates.collectAsState()

        var previewView by remember { mutableStateOf<PreviewView?>(null) }
        var showProjectPicker by remember { mutableStateOf(false) }
        var showProjectCreate by remember { mutableStateOf(false) }

        val camPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        // 权限授予后立刻绑相机（旧代码只在视图就绪时绑一次，授权后要等重启 Activity）
        LaunchedEffect(previewView, camPermission) {
            val activity = context as? ComponentActivity ?: return@LaunchedEffect
            val pv = previewView ?: return@LaunchedEffect
            if (camPermission && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                startCamera(context, activity, pv)
            }
        }

        DisposableEffect(context) {
            val activity = context as? ComponentActivity
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val pv = previewView
                    if (activity != null && pv != null) startCamera(context, activity, pv)
                    vm.refreshLocation()
                }
            }
            activity?.lifecycle?.addObserver(observer)
            onDispose { activity?.lifecycle?.removeObserver(observer) }
        }

        // 保存结果提示
        LaunchedEffect(cameraState.lastMessage) {
            val msg = cameraState.lastMessage ?: return@LaunchedEffect
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also {
                        it.scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = it
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!camPermission) {
                PermissionOverlay(onRetry = { requestPermissionsIfNeeded() })
            }

            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(cameraState.currentProject?.name ?: "未选择项目", color = Color.White)
                    },
                    actions = {
                        IconButton(onClick = { showProjectPicker = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "项目", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                // 模板快捷栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    templates.forEach { tpl ->
                        FilterChip(
                            selected = cameraState.currentTemplate?.id == tpl.id,
                            onClick = { vm.switchTemplate(tpl) },
                            label = { Text(tpl.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.size(4.dp))

                // 字段编辑区
                if (cameraState.currentFields.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        color = Color.Black.copy(alpha = 0.55f)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            cameraState.currentFields.forEach { field ->
                                val value = cameraState.fieldValues[field.fieldKey] ?: ""
                                TextField(
                                    value = value,
                                    onValueChange = { vm.updateField(field.fieldKey, it) },
                                    label = { Text(field.label) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 实时信息条：拍摄时间 / GPS / 地址
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Black.copy(alpha = 0.7f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            (cameraState.currentProject?.company ?: "未设置公司") +
                                "  " + cameraState.captureTime,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            cameraState.gpsText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            cameraState.addressText,
                            color = Color.LightGray,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            FloatingActionButton(
                onClick = {
                    val activity = context as? ComponentActivity
                    val capture = imageCapture
                    when {
                        activity == null -> Unit
                        !camPermission -> Toast.makeText(context, "请先授予相机权限", Toast.LENGTH_SHORT).show()
                        capture == null -> Toast.makeText(context, "相机尚未就绪，请稍候", Toast.LENGTH_SHORT).show()
                        else -> takePhoto(activity, vm, capture)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Camera, contentDescription = "拍摄")
            }
        }

        if (showProjectPicker) {
            ProjectPickerDialog(
                projects = projects,
                currentId = cameraState.currentProject?.id,
                onSelect = {
                    vm.switchProject(it)
                    showProjectPicker = false
                },
                onDelete = { vm.deleteProject(it.id) },
                onCreateNew = {
                    showProjectPicker = false
                    showProjectCreate = true
                },
                onDismiss = { showProjectPicker = false }
            )
        }

        if (showProjectCreate) {
            CreateProjectDialog(
                onCreate = { name, company ->
                    vm.createProject(name, company)
                    showProjectCreate = false
                },
                onDismiss = { showProjectCreate = false }
            )
        }
    }

    private fun takePhoto(activity: ComponentActivity, vm: MainViewModel, capture: ImageCapture) {
        val originalFile = vm.createOriginalFile()
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(originalFile).build(),
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // 渲染水印 + 写盘不能占主线程
                    activity.lifecycleScope.launch {
                        vm.savePhoto(originalFile)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        activity, "拍摄失败：" + (exc.message ?: "未知错误"), Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun startCamera(context: Context, lifecycleOwner: LifecycleOwner, pv: PreviewView) {
        if (cameraProvider != null && imageCapture != null) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val capture = ImageCapture.Builder()
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
                    capture
                )
                imageCapture = capture
                cameraProvider = provider
                android.util.Log.d("SiteStamp", "camera bound")
            } catch (e: Exception) {
                android.util.Log.e("SiteStamp", "startCamera failed", e)
                Toast.makeText(context, "相机启动失败：" + (e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

@Composable
private fun PermissionOverlay(onRetry: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.85f)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Camera,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.size(16.dp))
            Text(
                "相机权限未授予",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                "请在系统设置中允许 SiteStamp 使用相机权限，否则无法拍摄。",
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.size(24.dp))
            Button(onClick = onRetry) { Text("重新请求权限") }
        }
    }
}

@Composable
private fun ProjectPickerDialog(
    projects: List<com.cscec.sitestamp.data.Project>,
    currentId: Long?,
    onSelect: (com.cscec.sitestamp.data.Project) -> Unit,
    onDelete: (com.cscec.sitestamp.data.Project) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择项目") },
        text = {
            if (projects.isEmpty()) {
                Text("还没有项目，先新建一个。")
            } else {
                LazyColumn {
                    items(projects) { project ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(project) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = project.id == currentId,
                                onClick = { onSelect(project) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.bodyLarge)
                                if (project.company.isNotBlank()) {
                                    Text(
                                        project.company,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = { onDelete(project) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除项目")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateNew) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("新建项目")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun CreateProjectDialog(
    onCreate: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目") },
        text = {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )
                TextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text("公司名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name, company) },
                enabled = name.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
