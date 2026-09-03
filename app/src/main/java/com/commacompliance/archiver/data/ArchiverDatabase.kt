package com.commacompliance.archiver.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.commacompliance.archiver.crypto.KeyManager
import com.commacompliance.archiver.crypto.LazySodiumNaclBox
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Schema versions, each named for what it introduced, so a migration edge reads as
 * a pair of meanings rather than a pair of bare numbers.
 */
private const val VERSION_META_TABLE = 2
private const val VERSION_PENDING_ATTACHMENTS = 3
private const val VERSION_ATTACHMENT_FINGERPRINT = 4

/** Current Room schema version. Bump + add a Migration for every schema change. */
private const val SCHEMA_VERSION = VERSION_ATTACHMENT_FINGERPRINT

/**
 * On-disk name of the legacy PLAINTEXT queue (pre-encryption builds) and of the
 * encrypted queue. They are DISTINCT files: the encrypted DB has its own name so
 * "encrypted file exists" is an unambiguous migration-done marker, and the legacy
 * plaintext file can be detected and transcribed independently.
 */
private const val LEGACY_PLAINTEXT_DB_NAME = "archiver.db"
private const val ENCRYPTED_DB_NAME = "archiver-enc.db"

@Database(
    entities = [CapturedEvent::class, TelephonySnapshot::class, ArchiverMeta::class, PendingAttachment::class],
    version = SCHEMA_VERSION,
    exportSchema = false,
)
abstract class ArchiverDatabase : RoomDatabase() {
    abstract fun capturedEventDao(): CapturedEventDao
    abstract fun telephonySnapshotDao(): TelephonySnapshotDao
    abstract fun archiverMetaDao(): ArchiverMetaDao
    abstract fun pendingAttachmentDao(): PendingAttachmentDao

    companion object {
        @Volatile
        private var instance: ArchiverDatabase? = null

        /**
         * Open the durable queue, building the singleton on first call.
         *
         * [openHelperFactory] is the at-rest-encryption seam. In production it is
         * left null, and [buildEncrypted] supplies a SQLCipher
         * [SupportOpenHelperFactory] keyed from the device passphrase after running
         * the one-time plaintext->encrypted migration. Host (Robolectric) tests pass
         * the framework default factory (e.g. via `Room.inMemoryDatabaseBuilder`,
         * which never reaches this method) OR an explicit framework factory here -
         * the SQLCipher native cannot load off-device. The null default means
         * existing tests, which build their own in-memory database directly, are
         * untouched by this seam.
         */
        fun get(
            context: Context,
            openHelperFactory: SupportSQLiteOpenHelper.Factory? = null,
        ): ArchiverDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, openHelperFactory).also { instance = it }
            }

        private fun build(
            appContext: Context,
            openHelperFactory: SupportSQLiteOpenHelper.Factory?,
        ): ArchiverDatabase =
            if (openHelperFactory != null) {
                // An explicit factory was injected (a test using the framework
                // default). Open under the encrypted DB's name so the schema/version
                // bookkeeping matches production; the framework factory just won't
                // encrypt the bytes off-device.
                builder(appContext, ENCRYPTED_DB_NAME).openHelperFactory(openHelperFactory).build()
            } else {
                buildEncrypted(appContext)
            }

        /**
         * Production open path: ensure the SQLCipher native is loaded, run the
         * one-time plaintext->encrypted migration, then open the encrypted DB with a
         * [SupportOpenHelperFactory] keyed from the device passphrase.
         *
         * Fail-closed posture: the passphrase is the ONLY thing that can decrypt the
         * queue. If an encrypted DB file already exists but the passphrase entry has
         * vanished (e.g. EncryptedSharedPreferences/Keystore was reset by an OS or
         * credential change), we DO NOT silently regenerate a passphrase - that would
         * orphan the durable queue while presenting a brand-new empty one as if
         * capture were healthy. We let [KeyManager.dbPassphrase] regenerate ONLY when
         * no encrypted DB exists yet (first install); otherwise the open throws so
         * the failure is loud rather than a silent data-loss masquerading as success.
         */
        private fun buildEncrypted(appContext: Context): ArchiverDatabase {
            SqlCipherExporter.loadNative()

            val keyManager = KeyManager(appContext, LazySodiumNaclBox(LazySodiumAndroid(SodiumAndroid())))
            val encryptedDb = appContext.getDatabasePath(ENCRYPTED_DB_NAME)
            if (encryptedDb.exists() && !keyManager.hasDbPassphrase()) {
                error(
                    "encrypted queue exists but its passphrase is gone; refusing to " +
                        "regenerate and orphan the durable queue (fail-closed)",
                )
            }
            val passphrase = keyManager.dbPassphrase()

            // First encrypted start transcribes the legacy plaintext archiver.db into
            // the encrypted archiver-enc.db before Room opens. Thereafter the
            // encrypted file exists and the migrator is a no-op (it only cleans up any
            // stale plaintext leftover from a crash between rename and cleanup).
            val outcome = DatabaseEncryptionMigrator(
                plaintextDb = appContext.getDatabasePath(LEGACY_PLAINTEXT_DB_NAME),
                encryptedDb = encryptedDb,
                passphrase = passphrase,
            ).migrateIfNeeded()

            // Fail-closed on a transient export failure: the migrator has dropped the
            // partial encrypted file and kept the plaintext queue intact for a retry.
            // If we fell through to Room here it would CREATE a fresh empty encrypted
            // DB, and the next start - seeing that file exist - would treat migration
            // as done and delete the intact plaintext, silently losing the queue.
            // Throwing leaves both files as the migrator left them so the next process
            // start re-runs the transcription from the surviving plaintext source.
            if (outcome == DatabaseEncryptionMigrator.Outcome.FAILED_WILL_RETRY) {
                error("queue encryption migration failed; refusing to open an empty encrypted queue (will retry next start)")
            }

            return builder(appContext, ENCRYPTED_DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }

        private fun builder(appContext: Context, dbName: String): RoomDatabase.Builder<ArchiverDatabase> =
            Room.databaseBuilder(appContext, ArchiverDatabase::class.java, dbName)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /**
         * Adds the meta key/value table. A real migration (not destructive) so a
         * pre-release dev install keeps its durable un-uploaded event queue and
         * snapshot rather than dropping them - never lose captured-but-unsent data.
         */
        private val MIGRATION_1_2 = object : Migration(1, VERSION_META_TABLE) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS archiver_meta " +
                        "(key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)",
                )
            }
        }

        /**
         * Adds the durable pending-attachment store and the `ready_for_upload` gate
         * on captured_events. A non-destructive migration so an existing install
         * keeps its un-uploaded event queue and snapshot. Existing rows default
         * `ready_for_upload = 1`: they were captured before attachment hashing
         * existed and must still drain (their attachments, if any, simply lack a
         * content_hash - no worse than before this change).
         */
        // Version endpoints are written as LITERALS, never as SCHEMA_VERSION
        // arithmetic: a migration is a fixed edge between two specific schema
        // versions, so deriving it from the current version silently re-points the
        // edge (2->3 became 3->4) the next time SCHEMA_VERSION is bumped, and Room
        // would then find no path for the real 2->3 upgrade.
        private val MIGRATION_2_3 =
            object : Migration(VERSION_META_TABLE, VERSION_PENDING_ATTACHMENTS) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE captured_events ADD COLUMN ready_for_upload INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_captured_events_ready_for_upload " +
                        "ON captured_events (ready_for_upload)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_attachments " +
                        "(id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "content_hash TEXT NOT NULL, " +
                        "part_uri TEXT NOT NULL, " +
                        "mime TEXT NOT NULL, " +
                        "size_bytes INTEGER NOT NULL, " +
                        "uploaded INTEGER NOT NULL DEFAULT 0, " +
                        "created_at INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_attachments_content_hash_part_uri " +
                        "ON pending_attachments (content_hash, part_uri)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_attachments_uploaded " +
                        "ON pending_attachments (uploaded)",
                )
            }
        }

        /**
         * Adds the attachment fingerprint to the snapshot, giving the classifier a
         * change key that can see MMS/RCS media filled in after the row was first
         * captured. Non-destructive: the durable queue and the existing snapshot
         * rows survive.
         *
         * The column is NULLABLE with no default ON PURPOSE. Backfilling it with the
         * empty-set hash would make every already-snapshotted row that HAS media
         * compare unequal on the very next pass and emit an edit for the device's
         * whole message history. NULL instead means "unknown, predates this column",
         * which the classifier backfills silently without emitting.
         */
        private val MIGRATION_3_4 =
            object : Migration(VERSION_PENDING_ATTACHMENTS, VERSION_ATTACHMENT_FINGERPRINT) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE telephony_snapshot ADD COLUMN attachments_hash TEXT")
            }
        }
    }
}
