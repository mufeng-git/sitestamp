package com.cscec.sitestamp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cscec.sitestamp.core.LocationRepository
import com.cscec.sitestamp.core.PhotoRepository
import com.cscec.sitestamp.core.WatermarkRenderer
import com.cscec.sitestamp.data.AppDatabase
import com.cscec.sitestamp.data.PhotoRecord
import com.cscec.sitestamp.data.Project
import com.cscec.sitestamp.data.ProjectDao
import com.cscec.sitestamp.data.Template
import com.cscec.sitestamp.data.TemplateDao
import com.cscec.sitestamp.data.TemplateField
import com.cscec.sitestamp.data.TemplateFieldDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CameraState(
    val currentProject: Project? = null,
    val currentTemplate: Template? = null,
    val currentFields: List<TemplateField> = emptyList(),
    val fieldValues: Map<String, String> = emptyMap(),
    val gpsText: String = "定位未获取",
    val addressText: String = "地址：正在解析",
    val captureTime: String = "",
    val saving: Boolean = false,
    val lastMessage: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db: AppDatabase = AppDatabase.getInstance(app)
    private val projectDao: ProjectDao = db.projectDao()
    private val templateDao: TemplateDao = db.templateDao()
    private val fieldDao: TemplateFieldDao = db.templateFieldDao()
    private val photoDao = db.photoRecordDao()

    private val locationRepo = LocationRepository(app)
    val locationRepoPublic: LocationRepository = locationRepo
    private val photoRepo = PhotoRepository(app)

    val location = locationRepo.location

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _templates = MutableStateFlow<List<Template>>(emptyList())
    val templates: StateFlow<List<Template>> = _templates.asStateFlow()

    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    init {
        observeProjects()
        observeLocation()
        startClock()
        locationRepo.requestUpdate()
    }

    // ---------------------------------------------------------------- 数据观察

    private fun observeProjects() {
        viewModelScope.launch {
            projectDao.observeAll().collect { list ->
                _projects.value = list
                val target = list.firstOrNull { it.isDefault } ?: list.firstOrNull()
                if (target == null) {
                    _cameraState.value = _cameraState.value.copy(currentProject = null)
                    return@collect
                }
                val previousId = _cameraState.value.currentProject?.id
                // 关键修复：currentProject 以前从未被赋值，导致拍照回调里 project 恒为 null，
                // 照片永远存不下来（界面一直显示「未选择项目」也是这个原因）。
                _cameraState.value = _cameraState.value.copy(currentProject = target)
                if (target.id != previousId) loadTemplates(target.id)
            }
        }
    }

    private fun loadTemplates(projectId: Long) {
        viewModelScope.launch {
            templateDao.observeByProject(projectId).collect { list ->
                _templates.value = list
                val target = list.firstOrNull { it.isDefault } ?: list.firstOrNull()
                if (target == null) {
                    _cameraState.value = _cameraState.value.copy(
                        currentTemplate = null,
                        currentFields = emptyList(),
                        fieldValues = emptyMap()
                    )
                    return@collect
                }
                if (target.id != _cameraState.value.currentTemplate?.id) {
                    loadFields(target)
                }
            }
        }
    }

    private fun loadFields(template: Template) {
        viewModelScope.launch {
            fieldDao.observeByTemplate(template.id).collect { fields ->
                val previousKey = _cameraState.value.currentTemplate?.id
                val values = fields.associate { it.fieldKey to (it.defaultValue ?: "") }
                _cameraState.value = _cameraState.value.copy(
                    currentTemplate = template,
                    currentFields = fields,
                    // 换模板才重置字段值，同一个模板内的编辑不要被 Flow 重发冲掉
                    fieldValues = if (previousKey != template.id) values else _cameraState.value.fieldValues
                )
            }
        }
    }

    private fun observeLocation() {
        viewModelScope.launch {
            locationRepo.location.collect { snap ->
                _cameraState.value = _cameraState.value.copy(
                    gpsText = locationRepo.formatGps(snap),
                    addressText = locationRepo.formatAddress(snap)
                )
                val lat = snap.latitude
                val lng = snap.longitude
                if (lat != null && lng != null && locationRepo.shouldGeocode(lat, lng)) {
                    // 旧实现里 resolveAddress 从未被调用，地址永远是「不可用」
                    val address = locationRepo.resolveAddress(lat, lng)
                    if (!address.isNullOrBlank()) {
                        locationRepo.setAddress(address)
                    }
                }
            }
        }
    }

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

    // ---------------------------------------------------------------- 拍摄

    fun createOriginalFile(): File = photoRepo.createOriginalFile()

    /** 相机回调里保存照片：渲染水印 + 写 EXIF + 存相册 + 落库。 */
    suspend fun savePhoto(originalFile: File): Boolean = withContext(Dispatchers.IO) {
        val state = _cameraState.value
        val project = state.currentProject ?: run {
            _cameraState.value = state.copy(lastMessage = "请先创建并选择项目")
            return@withContext false
        }
        val snapshot = locationRepo.location.value
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
            accuracy = snapshot.accuracy
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
            _cameraState.value = _cameraState.value.copy(
                lastMessage = if (result.watermarkRendered) "照片已保存" else "水印生成失败，已保存原图"
            )
            true
        } catch (t: Throwable) {
            android.util.Log.e("SiteStamp", "savePhoto failed", t)
            _cameraState.value = _cameraState.value.copy(lastMessage = "保存失败：" + (t.message ?: "未知错误"))
            false
        }
    }

    fun consumeMessage() {
        if (_cameraState.value.lastMessage != null) {
            _cameraState.value = _cameraState.value.copy(lastMessage = null)
        }
    }

    // ---------------------------------------------------------------- 项目/模板

    fun switchProject(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            // 旧实现用 copy(id = 0) 再 insert，每切一次就复制一条项目，这里改成原地改默认标记
            projectDao.setDefaultExclusive(project.id)
        }
    }

    fun switchTemplate(template: Template) {
        viewModelScope.launch(Dispatchers.IO) {
            templateDao.setDefaultExclusive(template.projectId, template.id)
        }
        loadFields(template)
    }

    fun updateField(key: String, value: String) {
        val current = _cameraState.value.fieldValues.toMutableMap()
        current[key] = value
        _cameraState.value = _cameraState.value.copy(fieldValues = current)
    }

    fun refreshLocation() {
        locationRepo.requestUpdate()
    }

    fun createProject(name: String, company: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val isFirst = projectDao.getDefault() == null
            val id = projectDao.insert(
                Project(
                    name = trimmedName,
                    company = company.trim(),
                    isDefault = isFirst,
                    createdAt = System.currentTimeMillis()
                )
            )
            projectDao.setDefaultExclusive(id)
            seedTemplates(id)
        }
    }

    fun createTemplate(projectId: Long, name: String, fields: List<Pair<String, String>>) {
        viewModelScope.launch(Dispatchers.IO) {
            val isFirst = templateDao.countByProject(projectId) == 0
            val tplId = templateDao.insert(
                Template(
                    projectId = projectId,
                    name = name,
                    layoutConfig = "{}",
                    styleConfig = "{}",
                    isDefault = isFirst,
                    sortOrder = 0
                )
            )
            val fieldList = fields.mapIndexed { index, pair ->
                TemplateField(
                    templateId = tplId,
                    fieldKey = pair.first,
                    label = pair.second,
                    sortOrder = index
                )
            }
            if (fieldList.isNotEmpty()) fieldDao.insertAll(fieldList)
        }
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
        val defaults = listOf("日常巡查", "隐患整改", "施工进度", "专项检查", "通用模板")
        val existing = templateDao.countByProject(projectId)
        if (existing > 0) return
        defaults.forEachIndexed { index, name ->
            val isFirst = index == 0
            val tplId = templateDao.insert(
                Template(
                    projectId = projectId,
                    name = name,
                    layoutConfig = "{}",
                    styleConfig = "{}",
                    isDefault = isFirst,
                    sortOrder = index
                )
            )
            fieldDao.insertAll(
                listOf(
                    TemplateField(templateId = tplId, fieldKey = "area", label = "施工区域", sortOrder = 0),
                    TemplateField(templateId = tplId, fieldKey = "item", label = "检查事项", sortOrder = 1)
                )
            )
        }
    }
}
