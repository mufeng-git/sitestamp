package com.cscec.sitestamp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY isDefault DESC, createdAt ASC")
    fun observeAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): Project?

    @Insert
    suspend fun insert(project: Project): Long

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE projects SET isDefault = :isDefault")
    suspend fun setDefault(isDefault: Boolean)

    /** 原地更新默认标记：仅 id 对应的项目为默认，且保证互斥。 */
    @Query("UPDATE projects SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setDefaultOnly(id: Long)
}
