package com.cscec.sitestamp.ui

import android.Manifest
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PhotoLibrary
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
import com.cscec.sitestamp.data.PhotoRecord
import com.cscec.sitestamp.data.Project
import com.cscec.sitestamp.data.Template
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val context = LocalContext.current
                locationRepo = vm.locationRepoPublic

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color.Black
                ) {
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
        val recentPhotos by vm.recentPhotos.collectAsState()

        var previewView by remember { mutableStateOf<PreviewView?>(null) }
        var showTemplateDialog by remember { mutableStateOf(false) }
        var showTemplateCreate by remember { mutableStateOf(false) }
        var showHistory by remember { mutableStateOf(false) }
        var showCreateProject by remember { mutableStateOf(false) }

        val camPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        // 相机生命周期：previewView 就绪 + ON_RESUME 时绑定
        LaunchedEffect(previewView, context) {
            val lifecycleOwner = context as? androidx.activity.ComponentActivity
            val pv = previewView
            if (pv != null && lifecycleOwner != null &&
                lifecycleOwner.lifecycle.currentState == androidx.lifecycle.Lifecycle.State.RESUMED
            ) {
                startCamera(context, lifecycleOwner, pv)
            }
        }

        // 生命周期 observer：ON_RESUME 时绑定相机 + 刷新定位 + 刷新照片记录
        DisposableEffect(context) {
            val lifecycleOwner = context as? androidx.activity.ComponentActivity
            if (lifecycleOwner != null) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        previewView?.let { startCamera(context, lifecycleOwner, it) }
                        locationRepo?.requestUpdate()
                        vm.refreshRecentPhotos()
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
                        showCreateProject = true
                    }) { Text("新建项目") }
                },
                dismissButton = {
                    TextButton(onClick = { showProjectDialog = false }) { Text("关闭") }
                }
            )
        }

        if (showCreateProject) {
            ProjectCreateDialog(
                onCreate = { name, company ->
                    vm.createProject(name, company)
                    showCreateProject = false
                },
                onDismiss = { showCreateProject = false }
            )
        }

        // 模板管理
        if (showTemplateDialog) {
            TemplateManagerDialog(
                templates = templates,
                currentId = cameraState.currentTemplate?.id,
                canCreate = cameraState.currentProject != null,
                onSelect = {
                    vm.setDefaultTemplate(it)
                    showTemplateDialog = false
                },
                onDelete = { vm.deleteTemplate(it.id) },
                onCreateNew = {
                    showTemplateDialog = false
                    showTemplateCreate = true
                },
                onDismiss = { showTemplateDialog = false }
            )
        }

        if (showTemplateCreate) {
            TemplateCreateDialog(
                onCreate = { name, labels ->
                    vm.addTemplate(name, labels)
                    showTemplateCreate = false
                },
                onDismiss = { showTemplateCreate = false }
            )
        }

        // 照片记录
        if (showHistory) {
            PhotoHistoryDialog(
                records = recentPhotos,
                projectName = { id ->
                    projects.firstOrNull { it.id == id }?.name ?: "已删除项目"
                },
                onDismiss = { showHistory = false }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    val pv = PreviewView(ctx)
                    pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewView = pv
                    pv
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!camPermission) {
                Surface(Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.85f)) {
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
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "请在系统设置中允许 SiteStamp 使用相机权限，否则无法拍摄。",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.size(24.dp))
                        Button(onClick = { requestPermissionsIfNeeded() }) {
                            Text("重新请求权限")
                        }
                    }
                }
            }

            TopAppBar(
                title = {
                    Text(cameraState.currentProject?.name ?: "未选择项目", color = Color.White)
                },
                actions = {
                    IconButton(onClick = {
                        vm.refreshRecentPhotos()
                        showHistory = true
                    }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "照片记录", tint = Color.White)
                    }
                    IconButton(onClick = { showTemplateDialog = true }) {
                        Icon(Icons.Default.List, contentDescription = "模板", tint = Color.White)
                    }
                    IconButton(onClick = onSwitchToProjects) {
                        Icon(Icons.Default.Settings, contentDescription = "项目")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
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
                                singleLine = true,
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
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(cameraState.gpsText, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Text(cameraState.addressText, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }

            FloatingActionButton(
                onClick = {
                    val act = context as? androidx.activity.ComponentActivity
                    when {
                        act == null -> Unit
                        !camPermission ->
                            Toast.makeText(context, "请先授予相机权限", Toast.LENGTH_SHORT).show()
                        !::imageCapture.isInitialized ->
                            Toast.makeText(context, "相机尚未就绪，请稍候", Toast.LENGTH_SHORT).show()
                        else -> takePhoto(act, vm)
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
        val originalFile = vm.createOriginalFile()
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(originalFile).build(),
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // 渲染水印 + 写盘不能占主线程
                    activity.lifecycleScope.launch {
                        val ok = vm.saveAndRecord(originalFile)
                        Toast.makeText(
                            activity,
                            if (ok) "照片已保存" else "保存失败，请检查项目是否已选择",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        activity, "拍摄失败：${exc.message}", Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun startCamera(
        context: android.content.Context,
        lifecycleOwner: LifecycleOwner,
        pv: PreviewView
    ) {
        if (cameraProvider != null) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
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
            } catch (e: Exception) {
                android.util.Log.e("SiteStamp", "startCamera failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

@Composable
private fun ProjectCreateDialog(
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
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
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

@Composable
private fun TemplateManagerDialog(
    templates: List<Template>,
    currentId: Long?,
    canCreate: Boolean,
    onSelect: (Template) -> Unit,
    onDelete: (Template) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模板管理") },
        text = {
            if (templates.isEmpty()) {
                Text("当前项目还没有模板，点右下角新建。")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(templates, key = { it.id }) { tpl ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSelect(tpl) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tpl.name)
                                Text(
                                    if (tpl.id == currentId) "使用中" else "点击设为默认",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (tpl.id == currentId) MaterialTheme.colorScheme.primary
                                    else Color.Gray
                                )
                            }
                            IconButton(onClick = { onDelete(tpl) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除模板")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateNew, enabled = canCreate) { Text("新建模板") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun TemplateCreateDialog(
    onCreate: (String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var f1 by remember { mutableStateOf("施工区域") }
    var f2 by remember { mutableStateOf("检查事项") }
    var f3 by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建模板") },
        text = {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模板名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                listOf(
                    Triple(f1, { v: String -> f1 = v }, "字段 1"),
                    Triple(f2, { v: String -> f2 = v }, "字段 2"),
                    Triple(f3, { v: String -> f3 = v }, "字段 3（可留空）")
                ).forEach { (value, setter, label) ->
                    TextField(
                        value = value,
                        onValueChange = setter,
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name, listOf(f1, f2, f3)) },
                enabled = name.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PhotoHistoryDialog(
    records: List<PhotoRecord>,
    projectName: (Long) -> String,
    onDismiss: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("照片记录（${records.size}）") },
        text = {
            if (records.isEmpty()) {
                Text("还没有拍摄记录。")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(records, key = { it.id }) { record ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(
                                fmt.format(Date(record.capturedAt)) + "  " + projectName(record.projectId),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val gps = if (record.latitude != null && record.longitude != null) {
                                String.format(
                                    Locale.US, "%.6f, %.6f",
                                    record.latitude, record.longitude
                                )
                            } else "无定位"
                            Text(
                                gps + "  " + record.fieldSnapshot,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
