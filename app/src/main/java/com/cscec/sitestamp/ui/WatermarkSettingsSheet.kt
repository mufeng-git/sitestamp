package com.cscec.sitestamp.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cscec.sitestamp.data.TemplateField
import com.cscec.sitestamp.data.WatermarkStyle
import com.cscec.sitestamp.ui.theme.BrandColors
import kotlin.math.roundToInt

/**
 * 水印设置面板：样式 / 底色 / 透明度 / 主色 / 文字色 / 显示项 / logo / 字段，全部可调。
 * 改动即时生效（onStyleChange 每次变化就写回模板）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkSettingsSheet(
    style: WatermarkStyle,
    fields: List<TemplateField>,
    onStyleChange: (WatermarkStyle) -> Unit,
    onLogoPicked: (Uri) -> Unit,
    onClearLogo: () -> Unit,
    onFieldsChange: (List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onLogoPicked) }

    // 字段草稿：改完点"应用字段"才落库，避免边打字边重建表
    var drafts by remember(fields) {
        mutableStateOf(fields.map { it.label to (it.options ?: "") })
    }
    var editingIndex by remember { mutableStateOf(-1) }
    var editLabel by remember { mutableStateOf("") }
    var editOptions by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrandColors.SurfaceElevated,
        dragHandle = null
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                "水印设置",
                color = BrandColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                // ---------------- 样式
                item {
                    SectionTitle("样式")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(STYLE_ITEMS) { item ->
                            StyleChip(
                                id = item.first,
                                name = item.second,
                                selected = style.styleId == item.first,
                                style = style
                            ) {
                                val newId = item.first
                                val bg = if (style.bgColor == WatermarkStyle.defaultBgFor(style.styleId)) {
                                    WatermarkStyle.defaultBgFor(newId)
                                } else {
                                    style.bgColor
                                }
                                val alpha =
                                    if (style.bgAlpha == WatermarkStyle.defaultAlphaFor(style.styleId)) {
                                        WatermarkStyle.defaultAlphaFor(newId)
                                    } else {
                                        style.bgAlpha
                                    }
                                onStyleChange(style.copy(styleId = newId, bgColor = bg, bgAlpha = alpha))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ---------------- 底色 + 透明度
                item {
                    SectionTitle("底色")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WatermarkStyle.BG_PRESETS.forEach { (rgb, _) ->
                            ColorDot(
                                rgb = rgb,
                                selected = style.bgColor == rgb,
                                onClick = { onStyleChange(style.copy(bgColor = rgb)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "透明度",
                            color = BrandColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(52.dp)
                        )
                        Slider(
                            value = style.bgAlpha / 255f,
                            onValueChange = {
                                onStyleChange(style.copy(bgAlpha = (it * 255).roundToInt()))
                            },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(style.bgAlpha / 255f * 100).roundToInt()}%",
                            color = BrandColors.TextPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.width(44.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ---------------- 主色
                item {
                    SectionTitle("主色（标题条 / 落款）")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WatermarkStyle.PRIMARY_PRESETS.forEach { (rgb, _) ->
                            ColorDot(
                                rgb = rgb,
                                selected = style.primaryColor == rgb,
                                onClick = { onStyleChange(style.copy(primaryColor = rgb)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ---------------- 文字色
                item {
                    SectionTitle("文字颜色")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextChip("自动", style.textColor == 0) {
                            onStyleChange(style.copy(textColor = 0))
                        }
                        TextChip("白色", style.textColor == 0xFFFFFF) {
                            onStyleChange(style.copy(textColor = 0xFFFFFF))
                        }
                        TextChip("深色", style.textColor == 0x181A1E) {
                            onStyleChange(style.copy(textColor = 0x181A1E))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ---------------- 显示内容
                item {
                    SectionTitle("显示内容")
                    ToggleRow("拍摄时间", style.showTime) {
                        onStyleChange(style.copy(showTime = it))
                    }
                    ToggleRow("天气", style.showWeather) {
                        onStyleChange(style.copy(showWeather = it))
                    }
                    ToggleRow("地址", style.showAddress) {
                        onStyleChange(style.copy(showAddress = it))
                    }
                    ToggleRow("经纬度", style.showCoords) {
                        onStyleChange(style.copy(showCoords = it))
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ---------------- logo
                item {
                    SectionTitle("Logo")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (style.logoPath.isBlank()) "当前：内置（中国建筑占位）" else "当前：自定义图片",
                            color = BrandColors.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { pickImage.launch("image/*") }) {
                            Text("选择图片")
                        }
                        if (style.logoPath.isNotBlank()) {
                            TextButton(onClick = onClearLogo) { Text("恢复内置") }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ---------------- 字段
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("字段（可增删改、调顺序）", Modifier.weight(1f))
                        TextButton(onClick = {
                            drafts = drafts + ("" to "")
                            editingIndex = drafts.size - 1
                            editLabel = ""
                            editOptions = ""
                        }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("添加")
                        }
                    }
                }

                itemsIndexed(drafts) { index, item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.first.ifBlank { "（未命名字段）" },
                                color = BrandColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (item.second.isBlank()) "可选值：自由输入" else "可选值：${item.second}",
                                color = BrandColors.TextSecondary,
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                        }
                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    drafts = drafts.toMutableList().also {
                                        val t = it[index]; it[index] = it[index - 1]; it[index - 1] = t
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = "上移",
                                tint = BrandColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                if (index < drafts.size - 1) {
                                    drafts = drafts.toMutableList().also {
                                        val t = it[index]; it[index] = it[index + 1]; it[index + 1] = t
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = "下移",
                                tint = BrandColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(onClick = {
                            editingIndex = index
                            editLabel = drafts[index].first
                            editOptions = drafts[index].second
                        }) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "编辑",
                                tint = BrandColors.Primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(onClick = {
                            drafts = drafts.toMutableList().also { it.removeAt(index) }
                        }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = BrandColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            onFieldsChange(drafts.filter { it.first.isNotBlank() })
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("应用字段") }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    if (editingIndex >= 0 && editingIndex < drafts.size) {
        AlertDialog(
            onDismissRequest = { editingIndex = -1 },
            title = { Text("编辑字段") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editLabel,
                        onValueChange = { editLabel = it },
                        label = { Text("字段名（如：施工区域）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editOptions,
                        onValueChange = { editOptions = it },
                        label = { Text("快捷选项（逗号分隔，可留空）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val idx = editingIndex
                    if (idx in drafts.indices) {
                        drafts = drafts.toMutableList().also {
                            it[idx] = editLabel.trim() to editOptions.trim()
                        }
                    }
                    editingIndex = -1
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editingIndex = -1 }) { Text("取消") }
            }
        )
    }
}

// ---------------------------------------------------------------------- 小组件

private val STYLE_ITEMS = listOf(
    WatermarkStyle.STYLE_BLOCK to "三段式",
    WatermarkStyle.STYLE_CARD to "深色卡",
    WatermarkStyle.STYLE_WHITE to "白卡",
    WatermarkStyle.STYLE_TIMEBAR to "大时间",
    WatermarkStyle.STYLE_COMPACT to "角标"
)

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = BrandColors.TextSecondary,
        fontSize = 12.sp,
        modifier = modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun StyleChip(
    id: Int,
    name: String,
    selected: Boolean,
    style: WatermarkStyle,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A2F36))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) BrandColors.Primary else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            // 极简示意图：用色块表达各款骨架
            Column(Modifier.fillMaxWidth().padding(5.dp)) {
                when (id) {
                    WatermarkStyle.STYLE_BLOCK -> {
                        Row {
                            Box(Modifier.size(9.dp).background(Color.White.copy(alpha = 0.9f)))
                            Box(Modifier.weight(1f).height(9.dp).background(Color(style.primaryColor)))
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .background(Color(style.bgColor).copy(alpha = style.bgAlpha / 255f))
                        )
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(6.dp).background(Color(style.primaryColor)))
                    }
                    WatermarkStyle.STYLE_CARD -> {
                        Row {
                            Box(Modifier.width(2.dp).height(28.dp).background(Color(style.primaryColor)))
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .background(Color(style.bgColor).copy(alpha = style.bgAlpha / 255f))
                            )
                        }
                    }
                    WatermarkStyle.STYLE_WHITE -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(Color(style.bgColor).copy(alpha = style.bgAlpha / 255f))
                        ) {
                            Box(Modifier.fillMaxWidth().height(3.dp).background(Color(style.primaryColor)))
                        }
                    }
                    WatermarkStyle.STYLE_TIMEBAR -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(Color(style.bgColor).copy(alpha = style.bgAlpha / 255f))
                        ) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(Color(style.primaryColor)))
                        }
                    }
                    else -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .background(Color(style.bgColor).copy(alpha = style.bgAlpha / 255f))
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            name,
            color = if (selected) BrandColors.Primary else BrandColors.TextSecondary,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ColorDot(rgb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xFF000000.toInt() or rgb))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) BrandColors.Primary else Color.White.copy(alpha = 0.25f),
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = if (red(rgb) * 0.299 + green(rgb) * 0.587 + blue(rgb) * 0.114 > 150) {
                    Color.Black
                } else {
                    Color.White
                },
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun red(color: Int) = (color shr 16) and 0xFF
private fun green(color: Int) = (color shr 8) and 0xFF
private fun blue(color: Int) = color and 0xFF

@Composable
private fun TextChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) BrandColors.Primary else Color.White.copy(alpha = 0.10f),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text,
            color = if (selected) Color.White else BrandColors.TextPrimary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = BrandColors.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
