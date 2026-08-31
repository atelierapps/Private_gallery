package com.atelierapps.vault.data.db

import android.content.Context
import android.util.Log
import com.atelierapps.vault.crypto.DbKeyStore
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Turns an existing plaintext `vault.db` into an encrypted one, once.
 *
 * The metadata table was plaintext by design in v1 (spec §2.1): filenames, tag
 * names, album names and dates sat readable next to encrypted media. This
 * closes that gap.
 *
 * ### The migration cannot half-finish
 * There are only two outcomes: fully migrated, or unchanged. The encrypted copy
 * is built in a temp file and verified table by table against the original
 * before anything is swapped; on any failure the temp file is deleted and the
 * original is left exactly as it was. If that happens the app keeps running on
 * the plaintext database rather than refusing to start — a vault you cannot
 * open is worse than one readable by someone who already has your filesystem.
 */
object DbCipher {

    private const val TAG = "DbCipher"
    private const val NAME = "vault.db"

    /** A plaintext SQLite file starts with this; an encrypted one starts random. */
    private val SQLITE_MAGIC = "SQLite format 3 ".toByteArray(Charsets.US_ASCII)

    private val TABLES = listOf("media", "tags", "media_tag", "auto_tag_rule", "album")

    /**
     * Ensures the database is encrypted, migrating it on the first run after the
     * upgrade.
     *
     * @return true if the database is encrypted and Room should open it through
     *   SQLCipher; false if a migration failed and it is still plaintext.
     */
    fun ensureEncrypted(context: Context): Boolean {
        loadNativeLibrary()
        val db = context.getDatabasePath(NAME)
        // No database yet: Room creates one, encrypted from the first byte.
        if (!db.exists()) return true
        if (!isPlaintext(db)) return true

        return runCatching { migrate(context, db) }
            .onFailure { Log.e(TAG, "metadata encryption failed; staying on plaintext", it) }
            .getOrDefault(false)
    }

    private fun loadNativeLibrary() {
        runCatching { System.loadLibrary("sqlcipher") }
            .onFailure { Log.e(TAG, "sqlcipher native library missing", it) }
    }

    private fun isPlaintext(file: File): Boolean {
        val head = ByteArray(SQLITE_MAGIC.size)
        val read = file.inputStream().use { it.read(head) }
        return read == head.size && head.contentEquals(SQLITE_MAGIC)
    }

    private fun migrate(context: Context, plain: File): Boolean {
        val hex = DbKeyStore.passphraseHex(context)
        val tmp = File(plain.parentFile, NAME + ".enc.tmp")
        tmp.delete()

        val before: Map<String, Int>
        val userVersion: Int

        // An empty password opens the file as ordinary SQLite, which is what
        // makes sqlcipher_export the supported way across this boundary.
        val source = SQLiteDatabase.openOrCreateDatabase(plain.absolutePath, "", null, null)
        try {
            before = source.rowCounts()
            userVersion = source.version
            source.rawExecSQL("ATTACH DATABASE '" + tmp.absolutePath + "' AS enc KEY \"x'" + hex + "'\"")
            source.rawExecSQL("SELECT sqlcipher_export('enc')")
            // sqlcipher_export copies tables and rows but not pragmas, and Room
            // reads user_version to decide whether to run migrations. Left at 0
            // it would try to migrate a schema that is already current, and fail.
            source.rawExecSQL("PRAGMA enc.user_version = " + userVersion)
            source.rawExecSQL("DETACH DATABASE enc")
        } finally {
            runCatching { source.close() }
        }

        // Verify against the original before anything is destroyed.
        val check = SQLiteDatabase.openOrCreateDatabase(tmp.absolutePath, "x'" + hex + "'", null, null)
        val after: Map<String, Int>
        val checkVersion: Int
        try {
            after = check.rowCounts()
            checkVersion = check.version
        } finally {
            runCatching { check.close() }
        }

        if (after != before || checkVersion != userVersion) {
            Log.e(TAG, "verification failed: " + before + " -> " + after)
            tmp.delete()
            return false
        }

        // Only now is the plaintext copy expendable. Keep it until the swap has
        // actually happened, so a failed rename can't leave no database at all.
        val backup = File(plain.parentFile, NAME + ".pre-encrypt")
        backup.delete()
        if (!plain.renameTo(backup)) {
            tmp.delete()
            return false
        }
        if (!tmp.renameTo(plain)) {
            backup.renameTo(plain)
            tmp.delete()
            return false
        }
        // The journals belong to the old database and must not be left beside
        // the new one, where SQLite would try to replay them.
        File(plain.parentFile, NAME + "-wal").delete()
        File(plain.parentFile, NAME + "-shm").delete()
        backup.delete()
        Log.i(TAG, "metadata database encrypted (" + before.values.sum() + " rows)")
        return true
    }

    private fun SQLiteDatabase.rowCounts(): Map<String, Int> =
        TABLES.associateWith { table ->
            // A table missing on both sides reports -1 on both sides and still
            // compares equal; one that failed to copy will not.
            runCatching {
                rawQuery("SELECT COUNT(*) FROM `" + table + "`", emptyArray<String>()).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else -1
                }
            }.getOrDefault(-1)
        }
}
