package com.commacompliance.archiver.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The plaintext->encrypted migration's decision + file orchestration, verified with
 * a fake exporter (the real SQLCipher export is device-only). The matrix:
 *   - no legacy file -> nothing to do
 *   - legacy present, no encrypted -> transcribe + retire plaintext
 *   - encrypted already present -> no-op (and clean any stale plaintext leftover)
 *   - exporter fails mid-run -> partial encrypted removed, plaintext kept for retry
 * plus that the WAL/SHM sidecars are removed on success and the same key bytes are
 * handed to the exporter (the key-divergence trap). Runs under Robolectric so the
 * migrator's android.util.Log housekeeping calls resolve.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseEncryptionMigratorTest {

    private lateinit var dir: File
    private lateinit var plaintext: File
    private lateinit var encrypted: File
    private val key = ByteArray(32) { it.toByte() }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("db-migrate").toFile()
        plaintext = File(dir, "archiver.db")
        encrypted = File(dir, "archiver-enc.db")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writePlaintextWithSidecars() {
        plaintext.writeText("plaintext-db")
        File(plaintext.path + "-wal").writeText("wal")
        File(plaintext.path + "-shm").writeText("shm")
    }

    /** An exporter that just writes the temp file, so the rename/cleanup path runs. */
    private val writingExporter = DatabaseEncryptionMigrator.Exporter { _, encryptedTemp, _ ->
        encryptedTemp.writeText("encrypted-bytes")
    }

    private fun migrator(exporter: DatabaseEncryptionMigrator.Exporter) =
        DatabaseEncryptionMigrator(plaintext, encrypted, key, exporter)

    @Test
    fun no_legacy_db_is_a_no_op() {
        val outcome = migrator(writingExporter).migrateIfNeeded()
        assertEquals(DatabaseEncryptionMigrator.Outcome.NO_LEGACY_DB, outcome)
        assertFalse(encrypted.exists())
    }

    @Test
    fun legacy_present_no_encrypted_transcribes_and_retires_plaintext() {
        writePlaintextWithSidecars()

        val outcome = migrator(writingExporter).migrateIfNeeded()

        assertEquals(DatabaseEncryptionMigrator.Outcome.MIGRATED, outcome)
        assertTrue("encrypted db should exist after migrate", encrypted.exists())
        assertFalse("plaintext retired", plaintext.exists())
        assertFalse("wal sidecar removed", File(plaintext.path + "-wal").exists())
        assertFalse("shm sidecar removed", File(plaintext.path + "-shm").exists())
        // No leftover temp.
        assertFalse(File(dir, "archiver-enc.db.migrating").exists())
    }

    @Test
    fun encrypted_already_present_is_a_no_op() {
        encrypted.writeText("already-encrypted")

        val outcome = migrator(writingExporter).migrateIfNeeded()

        assertEquals(DatabaseEncryptionMigrator.Outcome.ALREADY_ENCRYPTED, outcome)
        assertEquals("already-encrypted", encrypted.readText())
    }

    @Test
    fun encrypted_present_with_stale_plaintext_cleans_leftovers() {
        encrypted.writeText("already-encrypted")
        writePlaintextWithSidecars()

        val outcome = migrator(writingExporter).migrateIfNeeded()

        assertEquals(
            DatabaseEncryptionMigrator.Outcome.ALREADY_ENCRYPTED_CLEANED_LEFTOVERS,
            outcome,
        )
        // The authoritative encrypted DB is untouched; the stale plaintext is gone.
        assertEquals("already-encrypted", encrypted.readText())
        assertFalse(plaintext.exists())
        assertFalse(File(plaintext.path + "-wal").exists())
        assertFalse(File(plaintext.path + "-shm").exists())
    }

    @Test
    fun exporter_failure_keeps_plaintext_and_removes_partial_encrypted() {
        writePlaintextWithSidecars()
        // Fails AFTER writing a partial temp, to prove the partial is cleaned up.
        val failingExporter = DatabaseEncryptionMigrator.Exporter { _, encryptedTemp, _ ->
            encryptedTemp.writeText("partial")
            error("simulated mid-export crash")
        }

        val outcome = migrator(failingExporter).migrateIfNeeded()

        assertEquals(DatabaseEncryptionMigrator.Outcome.FAILED_WILL_RETRY, outcome)
        assertTrue("plaintext kept for retry", plaintext.exists())
        assertFalse("final encrypted db not created", encrypted.exists())
        assertFalse("partial temp removed", File(dir, "archiver-enc.db.migrating").exists())
    }

    @Test
    fun a_leftover_temp_from_a_prior_crash_is_discarded_before_retry() {
        writePlaintextWithSidecars()
        // Simulate a temp file stranded by a previous crashed attempt.
        File(dir, "archiver-enc.db.migrating").writeText("stale-temp")

        val outcome = migrator(writingExporter).migrateIfNeeded()

        assertEquals(DatabaseEncryptionMigrator.Outcome.MIGRATED, outcome)
        assertEquals("encrypted-bytes", encrypted.readText())
    }

    @Test
    fun the_exporter_receives_the_exact_key_bytes() {
        writePlaintextWithSidecars()
        var seenKey: ByteArray? = null
        val keyCapturingExporter = DatabaseEncryptionMigrator.Exporter { _, encryptedTemp, k ->
            seenKey = k
            encryptedTemp.writeText("enc")
        }

        migrator(keyCapturingExporter).migrateIfNeeded()

        // A re-encoded/copied key here would orphan the DB at runtime; it must be the
        // identical bytes the open-helper factory uses.
        assertArrayEquals(key, seenKey)
    }
}
