package dev.kosend

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    const val RELEASES_URL = "https://github.com/FedericoDiTosto/KoSend/releases/latest"
    private const val API_URL = "https://api.github.com/repos/FedericoDiTosto/KoSend/releases/latest"

    class Release(val version: String, val url: String)

    fun latest(currentVersion: String): Release {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "KoSend/$currentVersion")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub replied ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return Release(
                version = json.getString("tag_name").removePrefix("v"),
                url = json.optString("html_url").ifEmpty { RELEASES_URL },
            )
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
