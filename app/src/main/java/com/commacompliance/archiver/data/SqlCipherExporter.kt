package com.commacompliance.archiver.data

import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * The real, device-only plaintext->encrypted transcription used by
 * [DatabaseEncryptionMigrator]. Loads the SQLCipher native library, opens the
 * legacy plaintext DB, and copies it wholesale into a freshly-keyed encrypted file
 * via `sqlcipher_export`.
 *
 * This cannot run off-device: the SQLCipher `.so` is an Android-only native, so
 * host (Robolectric) tests inject a fake [DatabaseEncryptionMigrator.Exporter]
 * instead. The migration orchestration around this step is unit-tested; THIS step
 * is exercised by the private end-to-end runbook.
 */
object SqlCipherExporter : DatabaseEncryptionMigrator.Exporter {

    /**
     * Load the SQLCipher JNI native. `System.loadLibrary` is idempotent (the runtime
     * no-ops a second load of the same library), so this is safe to call on every
     * database open. Must run before any SQLCipher [SQLiteDatabase] open or the
     * native methods are unlinked.
     */
    fun loadNative() {
        System.loadLibrary("sqlcipher")
    }

    override fun export(plaintext: File, encryptedTemp: File, key: ByteArray) {
        // Open the plaintext source with an EMPTY key: SQLCipher reads a plain,
        // unencrypted SQLite file when keyed with no bytes.
        val source = SQLiteDatabase.openDatabase(
            plaintext.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
            null,
        )
        try {
            // Fold any rows that live only in the -wal sidecar into the main DB so
            // the export sees the complete queue, never a stale snapshot.
            source.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE)")

            // Carry the Room schema version across. sqlcipher_export copies schema +
            // data but NOT PRAGMA user_version, so without this the encrypted DB would
            // open at user_version 0 and Room would demand a 0->N migration it has no
            // path for, failing the open on an otherwise-complete copy.
            val sourceUserVersion = readUserVersion(source)

            // ATTACH the destination, binding the key bytes as a STATEMENT PARAMETER -
            // identical key handling to SupportOpenHelperFactory(byte[]) (both pass the
            // same bytes to sqlite3_key, so SQLCipher derives the same key), so the
            // produced DB opens with the same passphrase at runtime. Re-encoding the
            // key to hex/text here would derive a different key and orphan the DB.
            source.rawExecSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(encryptedTemp.absolutePath, key))
            try {
                source.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                source.rawExecSQL("PRAGMA encrypted.user_version = $sourceUserVersion")
            } finally {
                source.rawExecSQL("DETACH DATABASE encrypted")
            }
        } finally {
            source.close()
        }

        // The bytes must be durable before the migrator renames the temp into place.
        fsyncFile(encryptedTemp)
    }

    /** Read PRAGMA user_version from the (already-open) source DB. */
    private fun readUserVersion(db: SQLiteDatabase): Int =
        db.rawQuery("PRAGMA user_version", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    @Suppress("TooGenericExceptionCaught")
    private fun fsyncFile(file: File) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val fd: FileDescriptor = raf.fd
                fd.sync()
            }
        } catch (e: Exception) {
            // Fail closed: a failed fsync on the exported temp is treated as fatal.
            // The bytes are not proven durable, so we must NOT let the migrator rename
            // a possibly-unflushed file over the live DB. Wrapping and rethrowing aborts
            // the migration; it then retries from the still-intact plaintext source. The
            // original cause is chained (not swallowed), so the failure is diagnosable.
            throw IllegalStateException("could not fsync migrated db", e)
        }
    }
}
