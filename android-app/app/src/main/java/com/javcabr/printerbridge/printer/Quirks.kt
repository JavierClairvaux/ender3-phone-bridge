package com.javcabr.printerbridge.printer

import org.json.JSONArray
import org.json.JSONObject

/** One known board + firmware build from `assets/quirks.json`. */
class Quirk(val key: String, val name: String, val knownMissing: Set<String>, val quirks: JSONArray, private val match: List<String>) {
    fun matches(firmwareName: String?) = firmwareName != null && match.isNotEmpty() && match.all { it in firmwareName }
}

/** The quirks registry: a small JSON asset keyed by board and firmware build. Batch 8 fills in the texts. */
class QuirkRegistry(private val boards: List<Quirk>) {
    fun find(firmwareName: String?): Quirk? = boards.firstOrNull { it.matches(firmwareName) }

    companion object {
        val EMPTY = QuirkRegistry(emptyList())

        fun parse(text: String): QuirkRegistry {
            val a = JSONObject(text).getJSONArray("boards")
            return QuirkRegistry((0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val m = o.optJSONArray("match") ?: JSONArray()
                val k = o.optJSONArray("known_missing_commands") ?: JSONArray()
                Quirk(o.getString("key"), o.optString("name", o.getString("key")),
                    (0 until k.length()).map { k.getString(it).uppercase() }.toSet(),
                    o.optJSONArray("quirks") ?: JSONArray(), (0 until m.length()).map { m.getString(it) })
            })
        }
    }
}
