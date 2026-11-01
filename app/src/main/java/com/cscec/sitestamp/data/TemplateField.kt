package com.cscec.sitestamp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "template_fields")
data class TemplateField(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val fieldKey: String,
    val label: String,
    val defaultValue: String? = null,
    val fieldType: String = "text", // text | select | date
    val options: String? = null,   // select 时逗号分隔
    val isVisible: Boolean = true,
    val sortOrder: Int = 0
)
