package com.cscec.sitestamp.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cscec.sitestamp.core.LocationRepository
import com.cscec.sitestamp.core.PhotoRepository
import com.cscec.sitestamp.core.WatermarkRenderer
import com.cscec.sitestamp.core.WeatherRepository
import com.cscec.sitestamp.data.AppDatabase
import com.cscec.sitestamp.data.FieldPresets
import com.cscec.sitestamp.data.PhotoRecord
import com.cscec.sitestamp.data.Project
import com.cscec.sitestamp.data.ProjectDao
import com.cscec.sitestamp.data.Template
import com.cscec.sitestamp.data.TemplateDao
import com.cscec.sitestamp.data.TemplateField
import com.cscec.sitestamp.data.TemplateFieldDao
import com.cscec.sitestamp.data.WatermarkStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 拍摄反馈状态：驱动快门动画、保存遮罩和结果胶囊。
 * [bannerSeq] 每次自增，UI 用它触发一次性提示动画，避免同文案不刷新。
 */
data class CaptureFeedback(
    val isSaving: Boolean = false,
    val lastThumb: String? = null,
    val banner: String? = null,
    val bannerOk: Boolean = true,
    val bannerSeq: Long = 0L
)

data class CameraState(
    val currentProject: Project? = null,
    val currentTemplate: Template? = null,
    val currentFields: List<TemplateField> = emptyList(),
    val fieldValues: MutableMap<String, String> = mutableMapOf(),
    val gpsText: String = "定位未获取",
    val addressText: String = "地址：正在解析",
    val captureTime: String = "",
    val weatherText: String = "",
    val style: WatermarkStyle = WatermarkStyle(),
    val recentValues: Map<String, List<String>> = emptyMap(),
    val feedback: CaptureFeedback = CaptureFeedback()
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db: AppDatabase = AppDatabase.getInstance(app)
    private val projectDao: ProjectDao = db.projectDao()
    private val templateDao: TemplateDao = db.templateDao()
    private val fieldDao: TemplateFieldDao = db.templateFieldDao()
    private val photoDao = db.photoRecordDao()

    private val locationRepo = LocationRepository(app)
    val locationRepoPublic = locationRepo
    private val photoRepo = PhotoRepository(app)
    val photoRepoPublic = photoRepo

    /** 最近使用过的字段值，现场同一个区域会连拍十几张，避免每次都翻选项。 */
    private val recentPrefs = app.getSharedPreferences("sitestamp_recent", Context.MODE_PRIVATE)

    private val weatherRepo = WeatherRepository(app)


    val location = locationRepo.location

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _templates = MutableStateFlow<List<Template>>(emptyList())
    val templates: StateFlow<List<Template>> = _templates.asStateFlow()

    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private val _recentPhotos = MutableStateFlow<List<PhotoRecord>>(emptyList())
    val recentPhotos: StateFlow<List<PhotoRecord>> = _recentPhotos.asStateFlow()

    init {
        observeData()
        observeLocation()
        startClock()
        loadRecentValues()
        observeWeather()
        locationRepo.requestUpdate()
    }

    // ------------------------------------------------------------ 字段快捷选项

    private fun loadRecentValues() {
        val map = HashMap<String, List<String>>()
        recentPrefs.all.forEach { (key, raw) ->
            if (key.startsWith(PREFIX_RECENT) && raw is String) {
                val values = raw.split(SEP).filter { it.isNotBlank() }
                if (values.isNotEmpty()) map[key.removePrefix(PREFIX_RECENT)] = values
            }
        }
        _cameraState.value = _cameraState.value.copy(recentValues = map)
    }

    private fun rememberValue(fieldKey: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        val current = _cameraState.value.recentValues[fieldKey].orEmpty()
        val updated = (listOf(trimmed) + current.filter { it != trimmed }).take(6)
        recentPrefs.edit()
            .putString(PREFIX_RECENT + fieldKey, updated.joinToString(SEP))
            .apply()
        _cameraState.value = _cameraState.value.copy(
            recentValues = _cameraState.value.recentValues + (fieldKey to updated)
        )
    }

    /** 字段的可选值：数据库显式配置 + 内置预设。 */
    fun optionsFor(field: TemplateField): List<String> = FieldPresets.merge(field.label, field.options)

    /** 该字段最近用过的值，排在选项最前面。 */
    fun recentFor(fieldKey: String): List<String> =
        _cameraState.value.recentValues[fieldKey].orEmpty()

    // ------------------------------------------------------------ 水印样式与字段自定义

    private var cachedLogoPath: String = "\u0000"
    private var cachedLogo: android.graphics.Bitmap? = null

    private fun assetLogo(): android.graphics.Bitmap? = try {
        getApplication<Application>().assets.open("cscec_logo.png").use {
            android.graphics.BitmapFactory.decodeStream(it)
        }
    } catch (_: Exception) {
        null
    }

    /** 用户自定义 logo 优先，未设置或读不出来时回落到内置素材，再回落到占位画法。 */
    private fun currentLogo(): android.graphics.Bitmap? {
        val path = _cameraState.value.style.logoPath
        if (path == cachedLogoPath) return cachedLogo
        cachedLogoPath = path
        cachedLogo = if (path.isBlank()) {
            assetLogo()
        } else {
            try {
                android.graphics.BitmapFactory.decodeFile(path) ?: assetLogo()
            } catch (_: Exception) {
                assetLogo()
            }
        }
        return cachedLogo
    }

    /** 改样式：立即生效并写回当前模板，下次切回来还是这套。 */
    fun updateStyle(style: WatermarkStyle) {
        val tpl = _cameraState.value.currentTemplate
        _cameraState.value = _cameraState.value.copy(
            style = style,
            currentTemplate = tpl?.copy(styleConfig = style.toJson())
        )
        if (tpl != null) {
            viewModelScope.launch(Dispatchers.IO) {
                templateDao.updateStyleConfig(tpl.id, style.toJson())
            }
        }
    }

    /** 从相册导入 logo：复制进应用私有目录，避免原图被删后水印失效。 */
    fun importLogo(uri: android.net.Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(app.filesDir, "logos").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, "logo_" + System.currentTimeMillis() + ".png")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                cachedLogoPath = "\u0000"
                withContext(Dispatchers.Main) {
                    updateStyle(_cameraState.value.style.copy(logoPath = dest.absolutePath))
                }
            } catch (t: Throwable) {
                android.util.Log.e("SiteStamp", "importLogo failed", t)
                withContext(Dispatchers.Main) { showBanner("logo 导入失败", false) }
            }
        }
    }

    fun clearLogo() {
        cachedLogoPath = "\u0000"
        updateStyle(_cameraState.value.style.copy(logoPath = ""))
    }

    /**
     * 重建模板字段（增删改排序都走这里）。
     * 标签没改的字段保留原来的填写值，改名的按新字段重填。
     */
    fun replaceFields(drafts: List<Pair<String, String>>) {
        val tpl = _cameraState.value.currentTemplate ?: return
        val oldByLabel = _cameraState.value.currentFields.associate {
            it.label.trim() to _cameraState.value.fieldValues[it.fieldKey].orEmpty()
        }
        viewModelScope.launch(Dispatchers.IO) {
            fieldDao.deleteByTemplate(tpl.id)
            val list = drafts
                .filter { it.first.isNotBlank() }
                .mapIndexed { index, (label, options) ->
                    TemplateField(
                        templateId = tpl.id,
                        fieldKey = "f" + index,
                        label = label.trim(),
                        options = options.trim().takeIf { it.isNotEmpty() },
                        sortOrder = index
                    )
                }
            if (list.isNotEmpty()) fieldDao.insertAll(list)
            val carried = HashMap<String, String>()
            list.forEach { f ->
                val old = oldByLabel[f.label]
                if (!old.isNullOrBlank()) carried[f.fieldKey] = old
            }
            withContext(Dispatchers.Main) {
                _cameraState.value = _cameraState.value.copy(fieldValues = carried)
            }
        }
    }

    // ------------------------------------------------------------ 拍摄反馈

    fun beginCapture() {
        _cameraState.value = _cameraState.value.copy(
            feedback = _cameraState.value.feedback.copy(isSaving = true, banner = null)
        )
    }

    fun showBanner(text: String, ok: Boolean) {
        val fb = _cameraState.value.feedback
        _cameraState.value = _cameraState.value.copy(
            feedback = fb.copy(
                isSaving = false,
                banner = text,
                bannerOk = ok,
                bannerSeq = fb.bannerSeq + 1
            )
        )
    }

    fun dismissBanner() {
        _cameraState.value = _cameraState.value.copy(
            feedback = _cameraState.value.feedback.copy(banner = null)
        )
    }

    private fun observeData() {
        viewModelScope.launch {
            projectDao.observeAll().collect {
                _projects.value = it
                val def = it.firstOrNull { p -> p.isDefault } ?: it.firstOrNull()
                if (def != null) {
                    val changed = def.id != _cameraState.value.currentProject?.id
                    _cameraState.value = _cameraState.value.copy(currentProject = def)
                    if (changed) loadTemplates(def.id)
                } else {
                    _cameraState.value = _cameraState.value.copy(currentProject = null)
                }
            }
        }
    }

    private fun observeLocation() {
        viewModelScope.launch {
            locationRepo.location.collect { snapshot ->
                _cameraState.value = _cameraState.value.copy(
                    gpsText = locationRepo.formatGps(snapshot),
                    addressText = locationRepo.formatAddress(snapshot)
                )
                val lat = snapshot.latitude
                val lng = snapshot.longitude
                if (lat != null && lng != null) {
                    viewModelScope.launch { weatherRepo.refresh(lat, lng) }
                }
            }
        }
    }

    /** 天气是水印要素之一，取不到就空着，绝不阻塞拍摄。 */
    private fun observeWeather() {
        viewModelScope.launch {
            weatherRepo.weatherText.collect { text ->
                if (_cameraState.value.weatherText != text) {
                    _cameraState.value = _cameraState.value.copy(weatherText = text)
                }
            }
        }
    }

    /** 预览条上的时间要跟着走，否则永远停在进入界面的那一刻。 */
    private fun startClock() {
        viewModelScope.launch {
            while (true) {
                val now = WatermarkRenderer.nowTime()
                if (_cameraState.value.captureTime != now) {
                    _cameraState.value = _cameraState.value.copy(captureTime = now)
                }
                delay(1000L)
            }
        }
    }

    private fun loadTemplates(projectId: Long) {
        viewModelScope.launch {
            templateDao.observeByProject(projectId).collect { list ->
                _templates.value = list
                val def = list.firstOrNull { t -> t.isDefault } ?: list.firstOrNull()
                if (def != null && def.id != _cameraState.value.currentTemplate?.id) {
                    loadFields(def)
                }
            }
        }
    }

    private fun loadFields(template: Template) {
        viewModelScope.launch {
            fieldDao.observeByTemplate(template.id).collect { fields ->
                val sameTemplate = _cameraState.value.currentTemplate?.id == template.id
                _cameraState.value = _cameraState.value.copy(
                    currentTemplate = template,
                    currentFields = fields,
                    style = WatermarkStyle.fromJson(template.styleConfig),
                    // 同一模板内的编辑不要被 Flow 重发覆盖
                    fieldValues = if (sameTemplate) _cameraState.value.fieldValues
                    else fields.associate { it.fieldKey to (it.defaultValue ?: "") }.toMutableMap()
                )
            }
        }
    }

    fun switchProject(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            // 原地切换默认标记，避免复制项目造成重复数据
            projectDao.setDefaultOnly(project.id)
        }
    }

    fun switchTemplate(template: Template) {
        viewModelScope.launch(Dispatchers.IO) {
            templateDao.setDefaultExclusive(template.projectId, template.id)
        }
        loadFields(template)
    }

    fun updateField(key: String, value: String) {
        val current = _cameraState.value.fieldValues
        _cameraState.value = _cameraState.value.copy(fieldValues = current.apply { put(key, value) })
        rememberValue(key, value)
    }

    fun refreshLocation() {
        locationRepo.requestUpdate()
    }

    // ------------------------------------------------------------ 拍摄与落库

    fun createOriginalFile(): File = photoRepo.createOriginalFile()

    /** 相机回调里调用：水印渲染 + 存相册 + 写 PhotoRecord，全程 IO 线程。 */
    suspend fun saveAndRecord(originalFile: File): Boolean = withContext(Dispatchers.IO) {
        val state = _cameraState.value
        val project = state.currentProject ?: return@withContext false
        val snapshot = location.value
        val data = WatermarkRenderer.WatermarkData(
            projectName = project.name,
            company = project.company,
            fields = state.currentFields
                .filter { it.isVisible }
                .map { it.fieldKey to (state.fieldValues[it.fieldKey] ?: "") },
            captureTime = WatermarkRenderer.nowTime(),
            gpsText = locationRepo.formatGps(snapshot),
            addressText = locationRepo.formatAddress(snapshot),
            latitude = snapshot.latitude,
            longitude = snapshot.longitude,
            weatherText = if (state.style.showWeather) state.weatherText else "",
            style = state.style.styleId,
            logo = currentLogo(),
            bgArgb = state.style.bgArgb(),
            primaryColor = state.style.primaryColor,
            textColor = state.style.textColor.takeIf { it != 0 },
            showCoords = state.style.showCoords,
            showGps = state.style.showCoords,
            showTime = state.style.showTime,
            showAddress = state.style.showAddress
        )
        try {
            val result = photoRepo.saveWatermarked(originalFile, data)
            photoDao.insert(
                PhotoRecord(
                    projectId = project.id,
                    templateId = state.currentTemplate?.id ?: 0L,
                    originalUri = result.originalUri,
                    watermarkedUri = result.watermarkedUri,
                    capturedAt = System.currentTimeMillis(),
                    latitude = snapshot.latitude,
                    longitude = snapshot.longitude,
                    accuracy = snapshot.accuracy,
                    fieldSnapshot = data.fields.joinToString(";") { it.first + "=" + it.second }
                )
            )
            refreshRecentPhotosSync()
            val fb = _cameraState.value.feedback
            _cameraState.value = _cameraState.value.copy(
                feedback = fb.copy(
                    isSaving = false,
                    lastThumb = result.watermarkedFile.absolutePath,
                    banner = "已保存到相册",
                    bannerOk = true,
                    bannerSeq = fb.bannerSeq + 1
                )
            )
            true
        } catch (t: Throwable) {
            android.util.Log.e("SiteStamp", "saveAndRecord failed", t)
            val fb = _cameraState.value.feedback
            _cameraState.value = _cameraState.value.copy(
                feedback = fb.copy(
                    isSaving = false,
                    banner = "保存失败：" + (t.message ?: "未知错误"),
                    bannerOk = false,
                    bannerSeq = fb.bannerSeq + 1
                )
            )
            false
        }
    }

    /** 保留旧调用路径，方便其他入口复用。 */
    fun captureAndSave(): PhotoRepository.SaveResult? {
        val state = _cameraState.value
        val project = state.currentProject ?: return null
        val originalFile = photoRepo.createOriginalFile()
        val snapshot = location.value
        val data = WatermarkRenderer.WatermarkData(
            projectName = project.name,
            company = project.company,
            fields = state.currentFields
                .filter { it.isVisible }
                .map { it.fieldKey to (state.fieldValues[it.fieldKey] ?: "") },
            captureTime = WatermarkRenderer.nowTime(),
            gpsText = locationRepo.formatGps(snapshot),
            addressText = locationRepo.formatAddress(snapshot),
            latitude = snapshot.latitude,
            longitude = snapshot.longitude,
            weatherText = if (state.style.showWeather) state.weatherText else "",
            style = state.style.styleId,
            logo = currentLogo(),
            bgArgb = state.style.bgArgb(),
            primaryColor = state.style.primaryColor,
            textColor = state.style.textColor.takeIf { it != 0 },
            showCoords = state.style.showCoords,
            showGps = state.style.showCoords,
            showTime = state.style.showTime,
            showAddress = state.style.showAddress
        )
        return try {
            photoRepo.saveWatermarked(originalFile, data)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ 照片记录

    fun refreshRecentPhotos() {
        viewModelScope.launch(Dispatchers.IO) { refreshRecentPhotosSync() }
    }

    private suspend fun refreshRecentPhotosSync() {
        _recentPhotos.value = photoDao.getRecent(100)
    }

    // ------------------------------------------------------------ 项目/模板

    fun createProject(name: String, company: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val isFirst = projectDao.getDefault() == null
            val id = projectDao.insert(
                Project(
                    name = trimmed,
                    company = company.trim(),
                    isDefault = isFirst,
                    createdAt = System.currentTimeMillis()
                )
            )
            projectDao.setDefaultOnly(id)
            seedTemplates(id)
        }
    }

    fun addTemplate(name: String, fieldLabels: List<String>) {
        val project = _cameraState.value.currentProject ?: return
        val trimmedName = name.trim()
        val labels = fieldLabels.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmedName.isEmpty() || labels.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val isFirst = templateDao.countByProject(project.id) == 0
            val tplId = templateDao.insert(
                Template(
                    projectId = project.id,
                    name = trimmedName,
                    layoutConfig = "{}",
                    styleConfig = "{}",
                    isDefault = isFirst,
                    sortOrder = 0
                )
            )
            fieldDao.insertAll(
                labels.mapIndexed { index, label ->
                    TemplateField(
                        templateId = tplId,
                        fieldKey = "f" + index,
                        label = label,
                        sortOrder = index
                    )
                }
            )
        }
    }

    fun setDefaultTemplate(template: Template) {
        viewModelScope.launch(Dispatchers.IO) {
            templateDao.setDefaultExclusive(template.projectId, template.id)
        }
        loadFields(template)
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            fieldDao.deleteByProject(id)
            templateDao.deleteByProject(id)
            projectDao.delete(id)
        }
    }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            fieldDao.deleteByTemplate(id)
            templateDao.delete(id)
        }
    }

    private suspend fun seedTemplates(projectId: Long) {
        if (templateDao.countByProject(projectId) > 0) return
        val defaults = listOf("日常巡查", "隐患整改", "施工进度", "专项检查", "通用模板")
        defaults.forEachIndexed { index, name ->
            val tplId = templateDao.insert(
                Template(
                    projectId = projectId,
                    name = name,
                    layoutConfig = "{}",
                    styleConfig = "{}",
                    isDefault = index == 0,
                    sortOrder = index
                )
            )
            fieldDao.insertAll(
                listOf(
                    TemplateField(
                        templateId = tplId,
                        fieldKey = "area",
                        label = "施工区域",
                        options = FieldPresets.forLabel("施工区域").joinToString(","),
                        sortOrder = 0
                    ),
                    TemplateField(
                        templateId = tplId,
                        fieldKey = "item",
                        label = "检查事项",
                        options = FieldPresets.forLabel("检查事项").joinToString(","),
                        sortOrder = 1
                    )
                )
            )
        }
    }

    private companion object {
        const val PREFIX_RECENT = "recent:"
        const val SEP = "\u0001"
    }
}
