package com.cscec.sitestamp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cscec.sitestamp.core.LocationRepository
import com.cscec.sitestamp.core.PhotoRepository
import com.cscec.sitestamp.core.WatermarkRenderer
import com.cscec.sitestamp.data.AppDatabase
import com.cscec.sitestamp.data.PhotoRecordDao
import com.cscec.sitestamp.data.Project
import com.cscec.sitestamp.data.ProjectDao
import com.cscec.sitestamp.data.Template
import com.cscec.sitestamp.data.TemplateDao
import com.cscec.sitestamp.data.TemplateField
import com.cscec.sitestamp.data.TemplateFieldDao
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CameraState(
    val currentProject: Project? = null,
    val currentTemplate: Template? = null,
    val currentFields: List<TemplateField> = emptyList(),
    val fieldValues: MutableMap<String, String> = mutableMapOf(),
    val gpsText: String = "GPS：定位未获取",
    val addressText: String = "地址：不可用",
    val captureTime: String = ""
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db: AppDatabase = AppDatabase.getInstance(app)
    private val projectDao: ProjectDao = db.projectDao()
    private val templateDao: TemplateDao = db.templateDao()
    private val fieldDao: TemplateFieldDao = db.templateFieldDao()
    val photoDao = db.photoRecordDao()

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

    init {
        observeData()
        locationRepo.requestUpdate()
    }

    private fun observeData() {
        viewModelScope.launch {
            projectDao.observeAll().collect {
                _projects.value = it
                val def = it.firstOrNull { p -> p.isDefault } ?: it.firstOrNull()
                if (def != null && def.id != _cameraState.value.currentProject?.id) {
                    loadTemplates(def.id)
                }
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
                _cameraState.value = _cameraState.value.copy(
                    currentTemplate = template,
                    currentFields = fields,
                    fieldValues = fields.associate { it.fieldKey to (it.defaultValue ?: "") }
                        .toMutableMap()
                )
            }
        }
    }

    fun switchProject(project: Project) {
        viewModelScope.launch(Dispatchers.IO) {
            projectDao.setDefault(false)
            projectDao.insert(project.copy(id = 0, isDefault = true))
        }
    }

    fun switchTemplate(template: Template) {
        viewModelScope.launch(Dispatchers.IO) {
            templateDao.setDefault(template.id)
        }
        loadFields(template)
    }
    fun updateField(key: String, value: String) {
        _cameraState.value = _cameraState.value.copy(
            fieldValues = _cameraState.value.fieldValues.apply { put(key, value) }
        )
    }

    fun refreshLocation() {
        locationRepo.requestUpdate()
    }

    fun captureAndSave(): PhotoRepository.SaveResult? {
        val state = _cameraState.value
        val project = state.currentProject ?: return null
        val template = state.currentTemplate ?: return null
        val originalFile = photoRepo.createOriginalFile()

        val data = WatermarkRenderer.WatermarkData(
            projectName = project.name,
            company = project.company,
            fields = state.currentFields
                .filter { it.isVisible }
                .map { it.fieldKey to (state.fieldValues[it.fieldKey] ?: "") },
            captureTime = WatermarkRenderer.nowTime(),
            gpsText = locationRepo.formatGps(location.value),
            addressText = locationRepo.formatAddress(location.value)
        )

        return try {
            photoRepo.saveWatermarked(originalFile, data)
        } catch (_: Exception) {
            null
        }
    }

    // 模板管理
    fun createProject(name: String, company: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val isFirst = projectDao.observeAll().firstOrNull()?.isEmpty() ?: true
            val id = projectDao.insert(
                Project(name = name, company = company, isDefault = isFirst, createdAt = now)
            )
            seedTemplates(id)
        }
    }

    fun createTemplate(projectId: Long, name: String, fields: List<Pair<String, String>>) {
        viewModelScope.launch(Dispatchers.IO) {
            val tpl = Template(projectId = projectId, name = name, layoutConfig = "{}", styleConfig = "{}",
                isDefault = templateDao.observeByProject(projectId).firstOrNull()?.isEmpty() ?: true, sortOrder = 0)
            val tplId = templateDao.insert(tpl)
            val fieldList = fields.mapIndexed { i, (key, label) ->
                TemplateField(templateId = tplId, fieldKey = key, label = label, sortOrder = i)
            }
            fieldDao.insertAll(fieldList)
        }
    }

    fun deleteProject(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { projectDao.delete(id) }
    }

    fun deleteTemplate(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            templateDao.delete(id)
            fieldDao.deleteByTemplate(id)
        }
    }

    private fun seedTemplates(projectId: Long) {
        val defaults = listOf(
            "日常巡查", "隐患整改", "施工进度", "专项检查", "通用模板"
        )
        defaults.forEach { name ->
            createTemplate(projectId, name, listOf("area" to "施工区域", "item" to "检查事项"))
        }
    }

    fun recentPhotos(limit: Int = 100) = viewModelScope.launch(Dispatchers.IO) {
        photoDao.getRecent(limit)
    }
}
