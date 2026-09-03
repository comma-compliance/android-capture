package com.commacompliance.archiver.integrity

/**
 * Produces a Play Integrity token to attest the device + app at registration and
 * enroll. The token binds an opaque server-issued [requestHash] (see the two
 * provisioning paths) so the server can match the verdict to this exact request.
 *
 * This is a seam, not just an abstraction: the real implementation talks to Google
 * Play services (absent on de-Googled / emulator / Play-less devices), so every
 * caller must treat a null return as "no attestation available" and proceed
 * WITHOUT hard-blocking sign-in or enroll. The server's integrity policy
 * (off/monitor/enforce) decides what a missing token means - the client never
 * decides for it. A fake implementation drives the wiring tests.
 *
 * [fetch] is blocking by design (it mirrors the blocking HttpClient idiom used by
 * RegistrationClient/EnrollmentClient); callers invoke it inside the same
 * `withContext(Dispatchers.IO)` block as the rest of the handshake.
 */
interface IntegrityTokenSource {
    /**
     * Return a Play Integrity token bound to [requestHash] for the Cloud project
     * [cloudProjectNumber], or null when one cannot be produced (Play services
     * unavailable, API error, timeout). MUST NOT throw and MUST NOT block sign-in:
     * any failure surfaces as null.
     *
     * @param cloudProjectNumber Google Cloud project number from discovery.
     * @param requestHash the server-bound request hash (lowercase hex SHA-256).
     */
    fun fetch(cloudProjectNumber: Long, requestHash: String): String?
}
