package com.cscec.sitestamp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PhotoRecordDao {
    @Insert
    suspend fun insert(record: PhotoRecord): Long

    @Query("SELECT * FROM photo_records ORDER BY capturedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<PhotoRecord>

    @Query("SELECT * FROM photo_records ORDER BY capturedAt DESC")
    suspend fun getAll(): List<PhotoRecord>
}
