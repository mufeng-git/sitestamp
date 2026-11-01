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
}
