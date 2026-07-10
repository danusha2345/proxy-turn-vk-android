package com.wdtt.client

import org.json.JSONObject

object TunnelEventParser {
    private const val PREFIX = "__WDTT_EVENT__|"

    sealed class Event {
        data class Ready(val message: String = "") : Event()
        data class Config(val config: String) : Event()
        data class Stats(val active: Int, val bytesUp: Long, val bytesDown: Long) : Event()
    }

    fun parse(line: String): Event? {
        if (!line.startsWith(PREFIX)) return null
        val rest = line.substring(PREFIX.length)
        val separator = rest.indexOf('|')
        if (separator < 0) return null
        val type = rest.substring(0, separator)
        val payload = try {
            JSONObject(rest.substring(separator + 1))
        } catch (_: Exception) {
            JSONObject()
        }
        return when (type) {
            "READY" -> Event.Ready(payload.optString("message", ""))
            "CONFIG" -> Event.Config(payload.optString("config", ""))
            "STATS" -> Event.Stats(
                payload.optInt("active", 0),
                payload.optLong("bytes_up", 0L),
                payload.optLong("bytes_down", 0L)
            )
            else -> null
        }
    }
}
