package com.atelierapps.vault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.atelierapps.vault.data.entity.AutoTagRuleEntity
import com.atelierapps.vault.data.entity.MediaItemEntity
import com.atelierapps.vault.data.entity.MediaTagCrossRef
import com.atelierapps.vault.data.entity.TagEntity

/**
 * Metadata store (spec §2). Media blobs and thumbnails are NOT here — only rows.
 * SQLCipher is intentionally out of scope for v1 (§14); the plaintext-DB
 * trade-off is documented in §2.1.
 */
@Database(
    entities = [MediaItemEntity::class, TagEntity::class, MediaTagCrossRef::class, AutoTagRuleEntity::class],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun tagDao(): TagDao
    abstract fun autoTagRuleDao(): AutoTagRuleDao

    companion object {
        @Volatile private var instance: VaultDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Recycle bin: nullable soft-delete timestamp + supporting index.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN deletedAtMillis INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_deletedAtMillis ON media(deletedAtMillis)")
            }
        }

        // Auto-tag rules table. Column defs must match what Room generates for
        // AutoTagRuleEntity (types, NOT NULL, DEFAULT 1 on enabled, PK on id).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `auto_tag_rule` (" +
                        "`id` TEXT NOT NULL, " +
                        "`matchKind` TEXT NOT NULL, " +
                        "`matchValue` TEXT NOT NULL, " +
                        "`tagNames` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "`createdAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        fun get(context: Context): VaultDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
