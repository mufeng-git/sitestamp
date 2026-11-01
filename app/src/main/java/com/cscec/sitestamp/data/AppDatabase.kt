package com.cscec.sitestamp.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Project::class, Template::class, TemplateField::class, PhotoRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun templateDao(): TemplateDao
    abstract fun templateFieldDao(): TemplateFieldDao
    abstract fun photoRecordDao(): PhotoRecordDao
}
