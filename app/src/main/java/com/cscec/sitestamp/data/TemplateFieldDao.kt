package com.cscec.sitestamp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateFieldDao {
    @Query("SELECT * FROM template_fields WHERE templateId = :templateId ORDER BY sortOrder ASC")
    fun observeByTemplate(templateId: Long): Flow<List<TemplateField>>

    @Insert
    suspend fun insertAll(fields: List<TemplateField>)

    @Query("DELETE FROM template_fields WHERE templateId = :templateId")
    suspend fun deleteByTemplate(templateId: Long)

    @Query("DELETE FROM template_fields WHERE templateId IN (SELECT id FROM templates WHERE projectId = :projectId)")
    suspend fun deleteByProject(projectId: Long)
}
