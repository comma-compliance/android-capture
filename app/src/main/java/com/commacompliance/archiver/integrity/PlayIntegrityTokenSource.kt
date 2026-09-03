package com.commacompliance.archiver.integrity

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityToken
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import com.google.android.play.core.integrity.model.StandardIntegrityErrorCode
import java.util.concurrent.TimeUnit

/**
 * The production [IntegrityTokenSource], backed by Play's StandardIntegrityManager.
 *
 * Standard requests are a two-step API:
 *   1. prepareIntegrityToken(cloudProjectNumber) -> a token PROVIDER. This is the
 *      expensive warm-up; it is done lazily ONCE and the provider cached, keyed by
 *      cloud project number (the number comes from server discovery and is stable
 *      for a deployment, but we re-prepare if it ever changes).
 *   2. provider.request(requestHash) -> the integrity token for this specific
 *      request, cheap and called per registration/enroll.
 *
 * Every failure path - Play services absent, network down, API error, a hung call
 * past the timeout - collapses to a null return with a class-name-only log (never
 * the token, the request hash, or any URL). Callers proceed token-less; the server
 * policy decides what that means. The one modeled retry is
 * INTEGRITY_TOKEN_PROVIDER_INVALID, which means the cached provider expired: we
 * drop it and re-prepare exactly once.
 *
 * All Play Tasks are awaited with a bounded timeout on the calling thread (callers
 * already run this on Dispatchers.IO), so a wedged Play service can never stall the
 * onboarding/enroll coroutine indefinitely.
 */
class PlayIntegrityTokenSource(
    context: Context,
    private val manager: StandardIntegrityManager =
        com.google.android.play.core.integrity.IntegrityManagerFactory.createStandard(context.applicationContext),
) : IntegrityTokenSource {

    // Cached prepared provider + the project number it was prepared for, so a
    // changed project number forces a fresh prepare. Guarded by the JVM monitor
    // below so two concurrent fetches cannot both prepare.
    private data class Prepared(val cloudProjectNumber: Long, val provider: StandardIntegrityTokenProvider)

    // A plain JVM monitor (not a coroutine Mutex) by design. This seam is BLOCKING:
    // IntegrityTokenSource.fetch is a non-suspend function called from synchronous,
    // non-coroutine call sites - WorkManager Worker.doWork() (heartbeat/upload/enroll)
    // and the onboarding registration path, which run on plain executor/IO threads,
    // not in a coroutine. There is no suspend context here to host Mutex.withLock, so
    // a coroutine Mutex does not fit without converting the whole blocking Worker +
    // HttpClient stack to suspend.
    //
    // Holding the monitor across the bounded Tasks.await below is acceptable because:
    //   - prepare is lazy and ONE-shot: it runs on the first fetch (and again only if
    //     the cloud project number changes or the provider is reported invalid), then
    //     every later fetch returns the cached provider without entering the await;
    //   - the await is hard-bounded at PREPARE_TIMEOUT_SECONDS, so a wedged Play
    //     service can stall a contender for at most that window, never indefinitely;
    //   - a second concurrent fetch that blocks here is the intended single-flight: it
    //     wakes to a freshly-cached provider and skips its own redundant prepare;
    //   - this runs on Dispatchers.IO / a Worker thread (off the main thread), so the
    //     worst case is one extra background thread parked for the bounded window, not
    //     a frozen UI or a starved dispatcher.
    private val lock = Any()
    private var prepared: Prepared? = null

    // ReturnCount: distinct null-returns per failure boundary read clearer than
    // nesting. TooGenericExceptionCaught: the whole point of this seam is that ANY
    // failure - StandardIntegrityException, Tasks.await's Execution/Interrupted/
    // Timeout, a Play services NPE - degrades to a null token; catching narrowly
    // would let an unanticipated Play failure crash sign-in, which must never happen.
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun fetch(cloudProjectNumber: Long, requestHash: String): String? {
        val provider = prepareProvider(cloudProjectNumber, forceRefresh = false) ?: return null
        return try {
            requestToken(provider, requestHash)
        } catch (e: StandardIntegrityException) {
            // A stale/expired provider reports INTEGRITY_TOKEN_PROVIDER_INVALID;
            // re-prepare once and retry the request a single time.
            if (e.errorCode == StandardIntegrityErrorCode.INTEGRITY_TOKEN_PROVIDER_INVALID) {
                val refreshed = prepareProvider(cloudProjectNumber, forceRefresh = true) ?: return null
                return runCatching { requestToken(refreshed, requestHash) }
                    .onFailure { logFailure("integrity request retry", it) }
                    .getOrNull()
            }
            logFailure("integrity request", e)
            null
        } catch (e: Exception) {
            logFailure("integrity request", e)
            null
        }
    }

    // TooGenericExceptionCaught: prepare can fail with Play API errors, Tasks.await
    // checked exceptions, or a timeout - all of which mean "no provider, proceed
    // token-less"; a narrow catch would risk crashing onboarding on an unmodeled one.
    @Suppress("TooGenericExceptionCaught")
    private fun prepareProvider(cloudProjectNumber: Long, forceRefresh: Boolean): StandardIntegrityTokenProvider? {
        synchronized(lock) {
            val cached = prepared
            if (!forceRefresh && cached != null && cached.cloudProjectNumber == cloudProjectNumber) {
                return cached.provider
            }
            return try {
                val request = PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(cloudProjectNumber)
                    .build()
                val provider = Tasks.await(
                    manager.prepareIntegrityToken(request),
                    PREPARE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
                prepared = Prepared(cloudProjectNumber, provider)
                provider
            } catch (e: Exception) {
                logFailure("integrity prepare", e)
                null
            }
        }
    }

    private fun requestToken(provider: StandardIntegrityTokenProvider, requestHash: String): String {
        val request = StandardIntegrityTokenRequest.builder()
            .setRequestHash(requestHash)
            .build()
        val token: StandardIntegrityToken =
            Tasks.await(provider.request(request), REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return token.token()
    }

    // Class-name-only: a Play exception message can echo request context; never log
    // the token, the request hash, or any URL.
    private fun logFailure(stage: String, t: Throwable) {
        Log.w(TAG, "$stage failed: ${t.javaClass.simpleName}")
    }

    companion object {
        private const val TAG = "PlayIntegrityTokenSource"
        private const val PREPARE_TIMEOUT_SECONDS = 20L
        private const val REQUEST_TIMEOUT_SECONDS = 20L
    }
}
