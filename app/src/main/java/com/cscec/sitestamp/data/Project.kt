package com.cscec.sitestamp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val company: String,
    val logoPath: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long
)
