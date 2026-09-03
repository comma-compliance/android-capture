package com.commacompliance.archiver.data

import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile

/**
 * One-shot, in-place migration of a legacy PLAINTEXT on-device queue to a
 * SQLCipher-encrypted database, run once BEFORE Room opens.
 *
 * Pre-encryption builds wrote `archiver.db` as a plain SQLite file. An encrypted
 * build cannot open that file, and we must never drop a captured-but-unsent event,
 * so on first encrypted start we transcribe the plaintext queue into an encrypted
 * file and then retire the plaintext one.
 *
 * The sequence (each ordering choice defends a specific crash window):
 *  1. If no plaintext file exists, OR the encrypted file already exists, there is
 *     nothing to do - the migration is idempotent by file existence. (An encrypted
 *     file present means a prior run finished; we only ever do cleanup of any
 *     leftover plaintext sidecars in that case.)
 *  2. Open the plaintext DB with SQLCipher and an EMPTY key (SQLCipher reads an
 *     unencrypted file when keyed empty), then `PRAGMA wal_checkpoint(TRUNCATE)` so
 *     any rows still living only in a `-wal` sidecar are folded into the main DB -
 *     otherwise the export would silently omit them.
 *  3. `ATTACH DATABASE ? AS encrypted KEY ?` to a TEMP encrypted file (not the final
 *     path), binding the SAME raw key bytes the open-helper factory uses (bound as a
 *     statement parameter, so SQLCipher derives the key identically in both places -
 *     a hex/string re-encoding here could produce a DB the factory cannot open).
 *  4. `SELECT sqlcipher_export('encrypted')` copies the whole schema + data across.
 *  5. DETACH, close, fsync the temp file AND its parent directory (so the bytes and
 *     the directory entry are both durable before we rely on them).
 *  6. Atomically rename the temp encrypted file onto the final encrypted path. After
 *     this single commit point the encrypted DB is authoritative.
 *  7. ONLY NOW delete the plaintext file and its `-wal`/`-shm` sidecars.
 *
 * Crash recovery, by window:
 *  - Crash before the rename (step 6): the final encrypted file does not exist, so
 *    the next start re-runs from scratch. Any temp file is deleted first, so a
 *    half-written temp never poisons the retry.
 *  - Crash after the rename but before plaintext cleanup: the next start sees the
 *    encrypted file present, treats it as authoritative, and just finishes deleting
 *    the stale plaintext sidecars. The plaintext is never removed before the
 *    encrypted file is durable, so the queue is never stranded with neither a
 *    complete source nor a usable target.
 *
 * The actual SQLCipher export path is device-only (the native library cannot load
 * off-device), so the decision/file logic is unit-tested with a fake exporter; the
 * real export is exercised by the private end-to-end runbook.
 */
class DatabaseEncryptionMigrator(
    private val plaintextDb: File,
    private val encryptedDb: File,
    private val passphrase: ByteArray,
    private val exporter: Exporter = SqlCipherExporter,
) {
    /** The device-only export step, abstracted so the orchestration is testable. */
    fun interface Exporter {
        /**
         * Transcribe the plaintext DB at [plaintext] into a NEW encrypted DB at
         * [encryptedTemp], encrypted under [key]. Must checkpoint the plaintext WAL
         * before exporting and fsync the produced file before returning. Throws on
         * any failure (the orchestration deletes the temp and retries next start).
         */
        fun export(plaintext: File, encryptedTemp: File, key: ByteArray)
    }

    /**
     * Run the migration if due. Returns the [Outcome] so callers (and tests) can see
     * which branch fired. Never throws: an export failure is logged (class name
     * only - never the path's contents) and reported as [Outcome.FAILED_WILL_RETRY],
     * leaving the plaintext intact for the next start.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    fun migrateIfNeeded(): Outcome {
        if (encryptedDb.exists()) {
            // A prior run already produced the authoritative encrypted DB. Whatever
            // plaintext remains is stale leftovers from a crash between rename and
            // cleanup; remove it so a future start sees a clean "no legacy" state.
            return if (plaintextDb.exists()) {
                deletePlaintextAndSidecars()
                Outcome.ALREADY_ENCRYPTED_CLEANED_LEFTOVERS
            } else {
                Outcome.ALREADY_ENCRYPTED
            }
        }
        if (!plaintextDb.exists()) {
            // Fresh install (or already-migrated-and-cleaned): Room will create the
            // encrypted DB directly. Nothing to transcribe.
            return Outcome.NO_LEGACY_DB
        }

        val tempEncrypted = File(encryptedDb.parentFile, encryptedDb.name + TEMP_SUFFIX)
        // A leftover temp from a prior crashed attempt is never trustworthy - start clean.
        deleteQuietly(tempEncrypted)

        return try {
            exporter.export(plaintextDb, tempEncrypted, passphrase)
            fsyncDir(encryptedDb.parentFile)
            // Single commit point: after this rename the encrypted DB is authoritative.
            if (!tempEncrypted.renameTo(encryptedDb)) {
                error("atomic rename of encrypted db failed")
            }
            fsyncDir(encryptedDb.parentFile)
            deletePlaintextAndSidecars()
            Outcome.MIGRATED
        } catch (e: Exception) {
            // Leave the plaintext untouched; drop the partial encrypted file so the
            // next start retries from a clean state. Log the class only - the
            // message could echo a path or payload fragment.
            Log.w(TAG, "queue encryption migration failed, will retry next start: ${e.javaClass.simpleName}")
            deleteQuietly(tempEncrypted)
            deleteQuietly(encryptedDb)
            Outcome.FAILED_WILL_RETRY
        }
    }

    private fun deletePlaintextAndSidecars() {
        deleteQuietly(plaintextDb)
        deleteQuietly(File(plaintextDb.path + WAL_SUFFIX))
        deleteQuietly(File(plaintextDb.path + SHM_SUFFIX))
    }

    /** Distinguishable outcomes so the decision matrix is unit-assertable. */
    enum class Outcome {
        /** No legacy plaintext file: fresh install or already cleaned. */
        NO_LEGACY_DB,

        /** Encrypted DB already present and no plaintext leftovers: nothing to do. */
        ALREADY_ENCRYPTED,

        /** Encrypted DB present; stale plaintext leftovers found and removed. */
        ALREADY_ENCRYPTED_CLEANED_LEFTOVERS,

        /** Plaintext transcribed to encrypted and the plaintext retired this run. */
        MIGRATED,

        /** Export failed; partial encrypted file removed, plaintext kept for retry. */
        FAILED_WILL_RETRY,
    }

    companion object {
        private const val TAG = "DbEncryptionMigrator"
        private const val TEMP_SUFFIX = ".migrating"
        private const val WAL_SUFFIX = "-wal"
        private const val SHM_SUFFIX = "-shm"

        private fun deleteQuietly(file: File) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "could not delete ${file.name} during migration housekeeping")
            }
        }

        /**
         * fsync a directory so a freshly-created/renamed entry is durable. On a
         * filesystem that refuses to open a directory for sync this is best-effort;
         * the rename itself is the correctness boundary, the fsync only narrows the
         * crash window.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun fsyncDir(dir: File?) {
            if (dir == null) return
            try {
                RandomAccessFile(dir, "r").use { raf ->
                    val fd: FileDescriptor = raf.fd
                    fd.sync()
                }
            } catch (e: Exception) {
                Log.d(TAG, "directory fsync skipped: ${e.javaClass.simpleName}")
            }
        }
    }
}
