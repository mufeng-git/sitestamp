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

    @Query("SELECT COUNT(*) FROM templates WHERE projectId = :projectId")
    suspend fun countByProject(projectId: Long): Int

    @Query("DELETE FROM templates WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: Long)

    /** 水印样式（颜色/透明度/logo/显示项）随模板保存。 */
    @Query("UPDATE templates SET styleConfig = :json WHERE id = :id")
    suspend fun updateStyleConfig(id: Long, json: String)

    @Query("SELECT * FROM templates WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Template?

    /** 项目内互斥：只有一个模板是默认模板 */
    @Query("UPDATE templates SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END WHERE projectId = :projectId")
    suspend fun setDefaultExclusive(projectId: Long, id: Long)
}
