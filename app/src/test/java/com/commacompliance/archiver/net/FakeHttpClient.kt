package com.commacompliance.archiver.net

/**
 * Records POSTs and replays scripted responses so the enrollment + upload logic is
 * exercised without a network. A response can be an [IOException] to simulate a
 * transport failure (WorkManager-retry path).
 */
class FakeHttpClient : HttpClient {
    data class Call(val method: String, val url: String, val body: String, val headers: Map<String, String>)

    val calls = mutableListOf<Call>()
    private val responses = ArrayDeque<() -> HttpClient.Response>()

    fun enqueue(code: Int, body: String = "") {
        responses.addLast { HttpClient.Response(code, body) }
    }

    fun enqueueTransportFailure() {
        responses.addLast { throw java.io.IOException("simulated transport failure") }
    }

    override fun postJson(url: String, body: String, headers: Map<String, String>): HttpClient.Response {
        calls.add(Call("POST", url, body, headers))
        val next = responses.removeFirstOrNull() ?: error("no scripted response for $url")
        return next()
    }

    override fun getJson(url: String, headers: Map<String, String>): HttpClient.Response {
        calls.add(Call("GET", url, "", headers))
        val next = responses.removeFirstOrNull() ?: error("no scripted response for $url")
        return next()
    }
}
