package com.cscec.sitestamp

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.cscec.sitestamp.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 验证 MIGRATION_1_2 不丢数据。
 *
 * 不依赖 MigrationTestHelper / schema json：手工按 v1 结构建表，插入存量数据，
 * 直接执行迁移 SQL，再断言四张表的行数与字段值未变、新列可用。
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(V1_PROJECTS)
                    db.execSQL(V1_TEMPLATES)
                    db.execSQL(V1_TEMPLATE_FIELDS)
                    db.execSQL(V1_PHOTO_RECORDS)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migrate1To2_keepsAllRowsAndFieldValues() {
        val db = helper.writableDatabase

        db.execSQL(
            "INSERT INTO projects (id,name,company,logoPath,isDefault,createdAt) " +
                "VALUES (1,'博物馆项目','中建国际乌兹公司',NULL,1,1762700000000)"
        )
        db.execSQL(
            "INSERT INTO templates (id,projectId,name,layoutConfig,styleConfig,isDefault,sortOrder) " +
                "VALUES (1,1,'三段式','{}','{}',1,0)"
        )
        db.execSQL(
            "INSERT INTO template_fields (id,templateId,fieldKey,label,defaultValue,fieldType,options,isVisible,sortOrder) " +
                "VALUES (1,1,'zone','施工区域',NULL,'select','4A,4B',1,0)"
        )
        db.execSQL(
            "INSERT INTO template_fields (id,templateId,fieldKey,label,defaultValue,fieldType,options,isVisible,sortOrder) " +
                "VALUES (2,1,'type','检查类型',NULL,'select',NULL,1,1)"
        )
        db.execSQL(
            "INSERT INTO template_fields (id,templateId,fieldKey,label,defaultValue,fieldType,options,isVisible,sortOrder) " +
                "VALUES (3,1,'matter','检查事项',NULL,'text',NULL,1,2)"
        )
        db.execSQL(
            "INSERT INTO photo_records (id,projectId,templateId,originalUri,watermarkedUri,capturedAt,latitude,longitude,accuracy,fieldSnapshot) " +
                "VALUES (1,1,1,'/dcim/a.jpg','/dcim/a_wm.jpg',1762700000000,41.2995,69.2401,5.0,'{}')"
        )
        db.execSQL(
            "INSERT INTO photo_records (id,projectId,templateId,originalUri,watermarkedUri,capturedAt,latitude,longitude,accuracy,fieldSnapshot) " +
                "VALUES (2,1,1,'/dcim/b.jpg','/dcim/b_wm.jpg',1762700001000,41.2996,69.2402,4.5,'{}')"
        )

        AppDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT COUNT(*) FROM projects").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM templates").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM template_fields").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM photo_records").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }

        db.query("SELECT name FROM projects WHERE id=1").use {
            it.moveToFirst(); assertEquals("博物馆项目", it.getString(0))
        }
        db.query("SELECT label FROM template_fields WHERE id=1").use {
            it.moveToFirst(); assertEquals("施工区域", it.getString(0))
        }
        db.query("SELECT watermarkedUri FROM photo_records WHERE id=2").use {
            it.moveToFirst(); assertEquals("/dcim/b_wm.jpg", it.getString(0))
        }

        db.query("SELECT isFixed, contractKey FROM template_fields WHERE id=1").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
            assertTrue(it.isNull(1))
        }
        db.close()
    }

    @Test
    fun newColumnsAreWritableAfterMigration() {
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_1_2.migrate(db)

        db.execSQL(
            "INSERT INTO template_fields (templateId,fieldKey,label,isVisible,sortOrder,contractKey,isFixed) " +
                "VALUES (1,'zone','施工区域',1,0,'f_zone',1)"
        )
        db.query("SELECT contractKey, isFixed FROM template_fields").use {
            it.moveToFirst()
            assertEquals("f_zone", it.getString(0))
            assertEquals(1, it.getInt(1))
        }
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"

        const val V1_PROJECTS =
            "CREATE TABLE IF NOT EXISTS `projects` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `company` TEXT NOT NULL, `logoPath` TEXT, " +
                "`isDefault` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"

        const val V1_TEMPLATES =
            "CREATE TABLE IF NOT EXISTS `templates` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, `layoutConfig` TEXT NOT NULL, `styleConfig` TEXT NOT NULL, " +
                "`isDefault` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)"

        const val V1_TEMPLATE_FIELDS =
            "CREATE TABLE IF NOT EXISTS `template_fields` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `templateId` INTEGER NOT NULL, " +
                "`fieldKey` TEXT NOT NULL, `label` TEXT NOT NULL, `defaultValue` TEXT, " +
                "`fieldType` TEXT NOT NULL, `options` TEXT, `isVisible` INTEGER NOT NULL, " +
                "`sortOrder` INTEGER NOT NULL)"

        const val V1_PHOTO_RECORDS =
            "CREATE TABLE IF NOT EXISTS `photo_records` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `projectId` INTEGER NOT NULL, " +
                "`templateId` INTEGER NOT NULL, `originalUri` TEXT NOT NULL, " +
                "`watermarkedUri` TEXT NOT NULL, `capturedAt` INTEGER NOT NULL, " +
                "`latitude` REAL, `longitude` REAL, `accuracy` REAL, `fieldSnapshot` TEXT NOT NULL)"
    }
}
