package com.cscec.sitestamp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates WHERE projectId = :projectId ORDER BY sortOrder ASC, isDefault DESC")
    fun observeByProject(projectId: Long): Flow<List<Template>>

    @Query("SELECT * FROM templates WHERE projectId = :projectId AND isDefault = 1 LIMIT 1")
    suspend fun getDefault(projectId: Long): Template?

    @Insert
    suspend fun insert(template: Template): Long

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE templates SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: Long)
}
