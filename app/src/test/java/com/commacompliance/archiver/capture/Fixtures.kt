package com.commacompliance.archiver.capture

import org.json.JSONObject

/** Loads a golden fixture from the contract/ test resources by base name. */
object Fixtures {
    fun load(name: String): JSONObject {
        val stream = Fixtures::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name.json")
            ?: error("fixture not found: $name")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    fun payload(name: String): JSONObject = load(name).getJSONObject("payload")
}
