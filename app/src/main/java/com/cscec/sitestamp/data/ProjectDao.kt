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

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Project?

    @Insert
    suspend fun insert(project: Project): Long

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE projects SET isDefault = :isDefault")
    suspend fun setDefault(isDefault: Boolean)

    /**
     * 切换项目：只把 id 这条置为默认，其余清零。
     * 旧实现用 copy(id = 0) 重新插入，每切一次就多一条重复项目，数据会被污染。
     */
    @Query("UPDATE projects SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setDefaultExclusive(id: Long)
}
