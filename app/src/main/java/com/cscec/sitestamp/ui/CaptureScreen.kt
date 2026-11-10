package com.cscec.sitestamp.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cscec.sitestamp.data.FieldPresets
import com.cscec.sitestamp.data.PhotoRecord
import com.cscec.sitestamp.data.Project
import com.cscec.sitestamp.data.Template
import com.cscec.sitestamp.data.TemplateField
import com.cscec.sitestamp.data.WatermarkStyle
import com.cscec.sitestamp.ui.theme.BrandColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 拍摄主界面。
 *
 * 设计约束（来自现场实测反馈）：
 * 1) 全屏沉浸，取景画面延伸到状态栏与导航栏下方，不留白条；
 * 2) 快门必须有物理反馈——按压缩放 + 白闪 + 震动 + 保存进度；
 * 3) 字段值是有限枚举，改成胶囊一键选择，不做键盘输入；
 * 4) 水印所见即所得，左下角实时预览最终版式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    preview: @Composable (Modifier) -> Unit,
    state: CameraState,
    projects: List<Project>,
    templates: List<Template>,
    recentPhotos: List<PhotoRecord>,
    flashOn: Boolean,
    hasPermission: Boolean,
    onShutter: () -> Unit,
    onToggleFlash: () -> Unit,
    onSwitchLens: () -> Unit,
    onFieldChange: (String, String) -> Unit,
    onSelectProject: (Project) -> Unit,
    onDeleteProject: (Long) -> Unit,
    onCreateProject: (String, String) -> Unit,
    onSelectTemplate: (Template) -> Unit,
    onCreateTemplate: (String, List<String>) -> Unit,
    onDeleteTemplate: (Template) -> Unit,
    onOpenGallery: () -> Unit,
    onDismissBanner: () -> Unit,
    onRequestPermission: () -> Unit,
    watermarkStyle: WatermarkStyle,
    renderOverlay: suspend (Int, Int) -> android.graphics.Bitmap?,
    onStyleChange: (WatermarkStyle) -> Unit,
    onLogoPicked: (Uri) -> Unit,
    onClearLogo: () -> Unit,
    onFieldsChange: (List<Pair<String, String>>) -> Unit
) {
    val haptics = LocalHapticFeedback.current

    var showProjectSheet by remember { mutableStateOf(false) }
    var showTemplateSheet by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var showCreateProject by remember { mutableStateOf(false) }
    var showCreateTemplate by remember { mutableStateOf(false) }
    var pickingField by remember { mutableStateOf<TemplateField?>(null) }
    var showWatermarkSettings by remember { mutableStateOf(false) }

    // 快门白闪：每次拍摄递增序号触发一次
    var flashTick by remember { mutableIntStateOf(0) }
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashTick) {
        if (flashTick > 0) {
            flashAlpha.snapTo(0.85f)
            flashAlpha.animateTo(0f, tween(320))
        }
    }

    // 结果胶囊 2.6 秒后自动消失
    LaunchedEffect(state.feedback.bannerSeq) {
        if (state.feedback.banner != null) {
            delay(2600)
            onDismissBanner()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val portrait = maxWidth <= maxHeight
        val previewModifier =
            if (portrait) Modifier.fillMaxHeight().aspectRatio(3f / 4f)
            else Modifier.fillMaxWidth().aspectRatio(4f / 3f)

        // 取景区与照片严格同比例（4:3），水印层由 WatermarkRenderer 同一套代码渲染，
        // 位置、字号、配色与成品一致——界面所见即拍出结果。
        BoxWithConstraints(modifier = previewModifier.align(Alignment.Center)) {
            val density = LocalDensity.current
            val previewW = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
            val previewH = with(density) { maxHeight.toPx() }.toInt().coerceAtLeast(1)
            val overlayW = previewW.coerceIn(240, 900)
            val overlayH = (overlayW.toFloat() * previewH / previewW).toInt().coerceAtLeast(180)

            preview(Modifier.fillMaxSize())

            if (hasPermission) {
                // 构图参考线（1/3 与 2/3）
                Row(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(0.6.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.10f)))
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(0.6.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.10f)))
                    Spacer(Modifier.weight(1f))
                }

                val overlay by produceState<ImageBitmap?>(
                    initialValue = null,
                    state.style,
                    state.fieldValues,
                    state.captureTime,
                    overlayW,
                    overlayH
                ) {
                    value = renderOverlay(overlayW, overlayH)?.asImageBitmap()
                }
                overlay?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (hasPermission) {
            TopBar(
                projectName = state.currentProject?.name ?: "未选择项目",
                company = state.currentProject?.company.orEmpty(),
                flashOn = flashOn,
                onProjectClick = { showProjectSheet = true },
                onToggleFlash = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggleFlash()
                },
                onSwitchLens = onSwitchLens,
                onWatermarkSettings = { showWatermarkSettings = true },
                modifier = Modifier.align(Alignment.TopStart)
            )

            // 模板快捷切换
            LazyRow(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(templates, key = { it.id }) { tpl ->
                    val selected = state.currentTemplate?.id == tpl.id
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (selected) BrandColors.Primary else Color.White.copy(alpha = 0.14f),
                        modifier = Modifier.clickable { onSelectTemplate(tpl) }
                    ) {
                        Text(
                            tpl.name,
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.clickable { showTemplateSheet = true }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = "模板管理",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("管理", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }

            // 字段快捷胶囊
            if (state.currentFields.isNotEmpty()) {
                FieldQuickBar(
                    fields = state.currentFields,
                    values = state.fieldValues,
                    onPickField = { pickingField = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 156.dp)
                )
            }

            BottomControls(
                state = state,
                thumbPath = state.feedback.lastThumb
                    ?: recentPhotos.firstOrNull()?.watermarkedUri,
                onShutter = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    flashTick++
                    onShutter()
                },
                onThumbClick = {
                    onOpenGallery()
                    showGallery = true
                },
                onManageClick = { showTemplateSheet = true },
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 保存中遮罩
            AnimatedVisibility(
                visible = state.feedback.isSaving,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.72f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = BrandColors.Primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("正在生成水印…", color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            // 结果胶囊
            AnimatedVisibility(
                visible = state.feedback.banner != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 112.dp)
            ) {
                val ok = state.feedback.bannerOk
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (ok) BrandColors.Success.copy(alpha = 0.94f)
                    else BrandColors.Danger.copy(alpha = 0.94f)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (ok) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            state.feedback.banner.orEmpty(),
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 快门白闪
        if (flashAlpha.value > 0.01f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(flashAlpha.value)
                    .background(Color.White)
            )
        }

        if (!hasPermission) {
            PermissionPlaceholder(onRequestPermission = onRequestPermission)
        }
    }

    // ---------------------------------------------------------------- 弹层

    pickingField?.let { field ->
        ValuePickerSheet(
            field = field,
            current = state.fieldValues[field.fieldKey].orEmpty(),
            onPick = { value ->
                onFieldChange(field.fieldKey, value)
                pickingField = null
            },
            onDismiss = { pickingField = null }
        )
    }

    if (showProjectSheet) {
        ProjectSheet(
            projects = projects,
            currentId = state.currentProject?.id,
            onSelect = {
                onSelectProject(it)
                showProjectSheet = false
            },
            onDelete = onDeleteProject,
            onCreate = {
                showProjectSheet = false
                showCreateProject = true
            },
            onDismiss = { showProjectSheet = false }
        )
    }

    if (showTemplateSheet) {
        TemplateSheet(
            templates = templates,
            currentId = state.currentTemplate?.id,
            onSelect = {
                onSelectTemplate(it)
                showTemplateSheet = false
            },
            onDelete = onDeleteTemplate,
            onCreate = {
                showTemplateSheet = false
                showCreateTemplate = true
            },
            onOpenWatermarkSettings = {
                showTemplateSheet = false
                showWatermarkSettings = true
            },
            onDismiss = { showTemplateSheet = false }
        )
    }

    if (showGallery) {
        GallerySheet(records = recentPhotos, onDismiss = { showGallery = false })
    }

    if (showWatermarkSettings) {
        WatermarkSettingsSheet(
            style = watermarkStyle,
            fields = state.currentFields,
            onStyleChange = onStyleChange,
            onLogoPicked = onLogoPicked,
            onClearLogo = onClearLogo,
            onFieldsChange = onFieldsChange,
            onDismiss = { showWatermarkSettings = false }
        )
    }

    if (showCreateProject) {
        CreateProjectDialog(
            onCreate = { name, company ->
                onCreateProject(name, company)
                showCreateProject = false
            },
            onDismiss = { showCreateProject = false }
        )
    }

    if (showCreateTemplate) {
        CreateTemplateDialog(
            onCreate = { name, labels ->
                onCreateTemplate(name, labels)
                showCreateTemplate = false
            },
            onDismiss = { showCreateTemplate = false }
        )
    }
}

// ---------------------------------------------------------------------- 顶栏

@Composable
private fun TopBar(
    projectName: String,
    company: String,
    flashOn: Boolean,
    onProjectClick: () -> Unit,
    onToggleFlash: () -> Unit,
    onSwitchLens: () -> Unit,
    onWatermarkSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.46f),
            modifier = Modifier.clickable { onProjectClick() }
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = BrandColors.Primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        projectName,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(150.dp)
                    )
                    if (company.isNotBlank()) {
                        Text(
                            company,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(150.dp)
                        )
                    }
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "切换项目",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        RoundIconButton(onClick = onWatermarkSettings) {
            Icon(
                Icons.Default.Tune,
                contentDescription = "水印设置",
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        RoundIconButton(onClick = onToggleFlash) {
            Icon(
                if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = "闪光灯",
                tint = if (flashOn) BrandColors.Accent else Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        RoundIconButton(onClick = onSwitchLens) {
            Icon(
                Icons.Default.Cameraswitch,
                contentDescription = "切换镜头",
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.46f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

// ---------------------------------------------------------------------- 水印预览


/** 两字标签用全角空格撑成四字宽，与渲染端 spreadLabel 保持一致。 */
private fun spreadLabel(label: String): String {
    val t = label.trim().trimEnd('\uFF1A', ':')
    return if (t.length == 2) t[0] + "\u3000" + t[1] else t
}

// ---------------------------------------------------------------------- 字段快捷栏

@Composable
private fun FieldQuickBar(
    fields: List<TemplateField>,
    values: Map<String, String>,
    onPickField: (TemplateField) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(fields, key = { it.fieldKey }) { field ->
            val value = values[field.fieldKey].orEmpty()
            val filled = value.isNotBlank()
            Surface(
                shape = RoundedCornerShape(50),
                color = if (filled) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.10f),
                modifier = Modifier.clickable { onPickField(field) }
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        field.label,
                        color = Color.White.copy(alpha = 0.70f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (filled) value else "请选择",
                        color = if (filled) Color.White else BrandColors.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- 快门与底部

@Composable
private fun BottomControls(
    state: CameraState,
    thumbPath: String?,
    onShutter: () -> Unit,
    onThumbClick: () -> Unit,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                )
            )
            .padding(top = 26.dp, bottom = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 最近一张
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .clickable { onThumbClick() },
                contentAlignment = Alignment.Center
            ) {
                val thumb by rememberThumb(context, thumbPath)
                val shown = thumb
                if (shown != null) {
                    Image(
                        bitmap = shown,
                        contentDescription = "照片记录",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = "照片记录",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.width(38.dp))

            ShutterButton(isSaving = state.feedback.isSaving, onClick = onShutter)

            Spacer(Modifier.width(38.dp))

            // 模板/字段管理
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .clickable { onManageClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "模板",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ShutterButton(isSaving: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = tween(90),
        label = "shutterScale"
    )
    Box(
        Modifier
            .size(78.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.92f), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !isSaving,
                onClick = onClick
            )
            .padding(7.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(60.dp * scale)
                .clip(CircleShape)
                .background(if (isSaving) Color.White.copy(alpha = 0.35f) else Color.White)
        )
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(60.dp),
                strokeWidth = 3.dp,
                color = BrandColors.Primary
            )
        }
    }
}

// ---------------------------------------------------------------------- 面板

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValuePickerSheet(
    field: TemplateField,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presets = remember(field.label, field.options) {
        FieldPresets.merge(field.label, field.options)
    }
    var custom by remember {
        mutableStateOf(if (current.isNotBlank() && current !in presets) current else "")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandColors.SurfaceElevated,
        dragHandle = null
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                field.label,
                color = BrandColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            if (presets.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presets) { v ->
                        ChipItem(text = v, selected = v == current, onClick = { onPick(v) })
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("自定义值") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { if (custom.isNotBlank()) onPick(custom.trim()) },
                enabled = custom.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("使用该值") }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ChipItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) BrandColors.Primary else Color.White.copy(alpha = 0.10f),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text,
            color = if (selected) Color.White else BrandColors.TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectSheet(
    projects: List<Project>,
    currentId: Long?,
    onSelect: (Project) -> Unit,
    onDelete: (Long) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandColors.SurfaceElevated,
        dragHandle = null
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选择项目",
                    color = BrandColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
            }
            if (projects.isEmpty()) {
                Text("还没有项目，先新建一个。", color = BrandColors.TextSecondary, fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(projects, key = { it.id }) { p ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(p) }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.name,
                                    color = BrandColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (p.id == currentId) FontWeight.Bold else FontWeight.Normal
                                )
                                if (p.company.isNotBlank()) {
                                    Text(
                                        p.company,
                                        color = BrandColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (p.id == currentId) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "使用中",
                                    tint = BrandColors.Primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = BrandColors.TextSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onDelete(p.id) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateSheet(
    templates: List<Template>,
    currentId: Long?,
    onSelect: (Template) -> Unit,
    onDelete: (Template) -> Unit,
    onCreate: () -> Unit,
    onOpenWatermarkSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandColors.SurfaceElevated,
        dragHandle = null
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "模板管理",
                    color = BrandColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
            }
            if (templates.isEmpty()) {
                Text("当前项目还没有模板。", color = BrandColors.TextSecondary, fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(templates, key = { it.id }) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(t) }
                                .padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.name,
                                    color = BrandColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = if (t.id == currentId) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    if (t.id == currentId) "使用中" else "点击切换",
                                    color = if (t.id == currentId) BrandColors.Primary else BrandColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = BrandColors.TextSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onDelete(t) }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth().clickable { onOpenWatermarkSettings() }
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = BrandColors.Primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("水印设置", color = BrandColors.TextPrimary, fontSize = 13.sp)
                        Text(
                            "样式 / 底色 / 透明度 / 文字颜色 / logo / 字段",
                            color = BrandColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Text("修改", color = BrandColors.Primary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GallerySheet(records: List<PhotoRecord>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandColors.SurfaceElevated,
        dragHandle = null
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(
                "照片记录（${records.size}）",
                color = BrandColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            if (records.isEmpty()) {
                Text("还没有拍摄记录。", color = BrandColors.TextSecondary, fontSize = 13.sp)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(records, key = { it.id }) { r ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val thumbState = rememberThumb(context, r.watermarkedUri)
                            val thumb = thumbState.value
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    formatTime(r.capturedAt),
                                    color = BrandColors.TextPrimary,
                                    fontSize = 13.sp
                                )
                                Text(
                                    r.fieldSnapshot.replace(";", "  "),
                                    color = BrandColors.TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ---------------------------------------------------------------------- 其他

@Composable
private fun PermissionPlaceholder(onRequestPermission: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                tint = BrandColors.Primary,
                modifier = Modifier.size(54.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "相机权限未授予",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "需要相机与定位权限才能拍摄带水印照片。",
                color = BrandColors.TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRequestPermission) { Text("重新请求权限") }
        }
    }
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text("单位名称（可留空）") },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CreateTemplateDialog(
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("模板名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                listOf(
                    Triple(f1, { v: String -> f1 = v }, "字段 1"),
                    Triple(f2, { v: String -> f2 = v }, "字段 2"),
                    Triple(f3, { v: String -> f3 = v }, "字段 3（可留空）")
                ).forEach { (value, setter, label) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = setter,
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name, listOf(f1, f2, f3)) },
                enabled = name.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ---------------------------------------------------------------------- 工具

@Composable
private fun rememberThumb(context: Context, path: String?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, path, 240) }
    }

private fun loadThumbnail(context: Context, uriOrPath: String?, maxPx: Int): ImageBitmap? {
    if (uriOrPath.isNullOrBlank()) return null
    return try {
        val open: () -> java.io.InputStream = {
            val uri = Uri.parse(uriOrPath)
            when {
                uri.scheme == "content" -> context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开 $uriOrPath")
                uri.scheme == "file" -> java.io.File(uri.path ?: uriOrPath).inputStream()
                else -> java.io.File(uriOrPath).inputStream()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / sample > maxPx || bounds.outHeight / sample > maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            open().use { BitmapFactory.decodeStream(it, null, opts)?.asImageBitmap() }
        }
    } catch (_: Exception) {
        null
    }
}

private fun formatTime(millis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        .format(java.util.Date(millis))
