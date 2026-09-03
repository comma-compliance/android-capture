package com.commacompliance.archiver.data

import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The at-rest-encryption seam: production leaves the open-helper factory null and
 * gets SQLCipher; an injected factory is used verbatim instead. Robolectric cannot
 * load the SQLCipher native, so this test injects the framework default factory and
 * proves [ArchiverDatabase.get] opens a working database through it - the same seam
 * production uses, just with the bytes left unencrypted off-device.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiverDatabaseSeamTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun resetSingleton() = clearInstance()

    @After
    fun tearDown() = clearInstance()

    @Test
    fun get_opens_a_working_database_through_an_injected_framework_factory() {
        val db = ArchiverDatabase.get(context, FrameworkSQLiteOpenHelperFactory())
        assertNotNull(db)

        // A round-trip through a real DAO proves the factory actually opened a DB.
        // Production forbids main-thread queries (no allowMainThreadQueries on the
        // real builder), so run the round-trip off the main thread - the same
        // constraint the workers honor.
        val dao = db.capturedEventDao()
        val count = onBackgroundThread {
            dao.insert(
                CapturedEvent(
                    providerId = "sms:1",
                    eventType = "received",
                    eventTs = 1L,
                    bodyHash = "h",
                    threadId = "1",
                    sender = null,
                    rawJson = "{}",
                ),
            )
            dao.count()
        }
        assertEquals(1, count)
    }

    private fun <T> onBackgroundThread(block: () -> T): T {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            return executor.submit(block).get()
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun get_returns_the_same_singleton_instance() {
        val first = ArchiverDatabase.get(context, FrameworkSQLiteOpenHelperFactory())
        val second = ArchiverDatabase.get(context, FrameworkSQLiteOpenHelperFactory())
        assertEquals(first, second)
    }

    // The singleton is process-global; reset it between tests so each opens fresh and
    // this test does not leak an open handle (or stored rows) into sibling tests.
    private fun clearInstance() {
        val field = ArchiverDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        (field.get(null) as? ArchiverDatabase)?.close()
        field.set(null, null)
        // Drop the file-backed DB so each method starts from an empty store.
        context.getDatabasePath("archiver-enc.db").parentFile?.listFiles()
            ?.filter { it.name.startsWith("archiver-enc.db") }
            ?.forEach { it.delete() }
    }
}
