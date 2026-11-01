package com.cscec.sitestamp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photo_records")
data class PhotoRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val templateId: Long,
    val originalUri: String,
    val watermarkedUri: String,
    val capturedAt: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val fieldSnapshot: String // JSON 字段快照
)
