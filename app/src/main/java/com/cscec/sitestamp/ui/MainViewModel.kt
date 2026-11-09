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
    val fieldValues: MutableMap<String, String> = mutableMapOf(),
    val gpsText: String = "定位未获取",
    val addressText: String = "地址：正在解析",
    val captureTime: String = ""
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
        locationRepo.requestUpdate()
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
            longitude = snapshot.longitude
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
            true
        } catch (t: Throwable) {
            android.util.Log.e("SiteStamp", "saveAndRecord failed", t)
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
            longitude = snapshot.longitude
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
                    TemplateField(templateId = tplId, fieldKey = "area", label = "施工区域", sortOrder = 0),
                    TemplateField(templateId = tplId, fieldKey = "item", label = "检查事项", sortOrder = 1)
                )
            )
        }
    }
}
