package com.atelierapps.vault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.atelierapps.vault.data.entity.MediaItemEntity
import com.atelierapps.vault.data.entity.MediaTagCrossRef
import com.atelierapps.vault.data.entity.TagEntity

/**
 * Metadata store (spec §2). Media blobs and thumbnails are NOT here — only rows.
 * SQLCipher is intentionally out of scope for v1 (§14); the plaintext-DB
 * trade-off is documented in §2.1.
 */
@Database(
    entities = [MediaItemEntity::class, TagEntity::class, MediaTagCrossRef::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun tagDao(): TagDao

    companion object {
        @Volatile private var instance: VaultDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): VaultDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
