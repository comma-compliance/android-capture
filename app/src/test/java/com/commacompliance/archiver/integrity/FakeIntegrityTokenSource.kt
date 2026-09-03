package com.commacompliance.archiver.integrity

/**
 * Records [fetch] calls and replays a scripted token (or null) so the
 * registration/enroll wiring is exercised without Play services. By default it
 * returns [token]; set it to null to simulate Play services being absent (the
 * degrade-to-tokenless path).
 */
class FakeIntegrityTokenSource(
    var token: String? = "fake-integrity-token",
) : IntegrityTokenSource {
    data class Call(val cloudProjectNumber: Long, val requestHash: String)

    val calls = mutableListOf<Call>()

    override fun fetch(cloudProjectNumber: Long, requestHash: String): String? {
        calls.add(Call(cloudProjectNumber, requestHash))
        return token
    }
}
