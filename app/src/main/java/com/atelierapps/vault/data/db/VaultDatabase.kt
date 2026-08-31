package com.atelierapps.vault.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.atelierapps.vault.data.entity.AlbumEntity
import com.atelierapps.vault.data.entity.AutoTagRuleEntity
import com.atelierapps.vault.data.entity.MediaItemEntity
import com.atelierapps.vault.data.entity.MediaTagCrossRef
import com.atelierapps.vault.data.entity.TagEntity
import com.atelierapps.vault.crypto.DbKeyStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import android.util.Log

/**
 * Metadata store (spec §2). Media blobs and thumbnails are NOT here — only rows.
 *
 * Encrypted with SQLCipher, closing the plaintext-metadata gap that §2.1
 * documented as a known v1 trade-off: filenames, tag names, album names and
 * dates used to sit readable next to the encrypted media they describe. See
 * [DbCipher] for the one-time migration and [com.atelierapps.vault.crypto.DbKeyStore]
 * for why the database key, unlike the media key, is not gated on biometrics.
 */
@Database(
    entities = [
        MediaItemEntity::class, TagEntity::class, MediaTagCrossRef::class,
        AutoTagRuleEntity::class, AlbumEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun tagDao(): TagDao
    abstract fun autoTagRuleDao(): AutoTagRuleDao
    abstract fun albumDao(): AlbumDao

    companion object {
        private const val TAG = "VaultDatabase"

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

        // Albums. The media.albumId column already exists from v1, so only the
        // album table is new. Column defs must match AlbumEntity.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `album` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        // Albums gain an explicitly chosen cover (nullable, no DB default).
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE album ADD COLUMN coverId TEXT")
            }
        }

        fun get(context: Context): VaultDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(app: Context): VaultDatabase {
            // Migrates a plaintext database if there is one. Returns false only
            // when that failed, in which case the file is still plaintext and
            // must be opened without the cipher.
            val encrypted = DbCipher.ensureEncrypted(app)
            // Try the mode we believe the file is in, then the other one. Room
            // opens lazily, so getting this wrong doesn't fail here — it fails
            // later at whatever screen happens to query first, as a crash with
            // no obvious connection to the database. Being wrong is survivable;
            // being wrong silently is not.
            return open(app, cipher = encrypted)
                ?: open(app, cipher = !encrypted)
                ?: error("vault.db could not be opened encrypted or plaintext")
        }

        private fun open(app: Context, cipher: Boolean): VaultDatabase? {
            val builder = Room.databaseBuilder(app, VaultDatabase::class.java, "vault.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            if (cipher) {
                builder.openHelperFactory(SupportOpenHelperFactory(DbKeyStore.passphraseBytes(app)))
            }
            val db = builder.build()
            // Force the open now rather than at the first query, so a bad guess
            // is caught here where there is still a second option.
            return runCatching { db.openHelper.readableDatabase.version; db }
                .getOrElse {
                    Log.e(TAG, "open failed with cipher=" + cipher, it)
                    runCatching { db.close() }
                    null
                }
        }
    }
}
