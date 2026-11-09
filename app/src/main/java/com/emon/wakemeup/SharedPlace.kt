package com.emon.wakemeup

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Understands what Google Maps sends when you tap Share on a place:
 * a place name plus a short link like https://maps.app.goo.gl/abc.
 * We open that link once (to Google only) to find the position it points to.
 */
object SharedPlace {

    /** What we managed to work out from the shared text. */
    class Result(
        val name: String,          // the place name from the shared text (may be empty)
        val lat: Double?,          // exact position, if the link contained one
        val lng: Double?,
        val searchText: String?,   // otherwise: text to look up with the address search
    )

    private val urlRegex = Regex("""https?://\S+""")
    private const val NUM = """(-?\d{1,3}\.\d+)"""

    /** Places in a Google Maps link where an exact position can be written, best first. */
    private val coordPatterns = listOf(
        Regex("""!3d$NUM!4d$NUM"""),                                              // the pin itself
        Regex("""[?&](?:q|ll|query|destination|daddr|center)=$NUM,\s*\+?$NUM"""), // ?q=53.1,-6.2
        Regex("""/(?:search|place|dir)/$NUM,\s*\+?$NUM"""),                       // /search/53.1,-6.2
        Regex("""@$NUM,$NUM"""),                                                  // centre of the map view
    )
    private val plainCoords = Regex("""(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})""")
    private val placePath = Regex("""/maps/place/([^/@?]+)""")
    private val queryText = Regex("""[?&]q=([^&]+)""")

    /** Does network work, so call it from a background thread. */
    fun resolve(sharedText: String): Result {
        val link = urlRegex.find(sharedText)?.value
        val name = sharedText.replace(urlRegex, "").lines()
            .map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

        // 1) Sometimes the text itself has the position (for example a dropped pin).
        findCoords(sharedText, plainOnly = true)?.let { return Result(name, it.first, it.second, null) }

        // 2) Follow the link, one hop at a time, and look at each address it leads to.
        var searchText: String? = null
        var current = link
        var hops = 0
        while (current != null && hops < 5) {
            val decoded = decode(current)
            findCoords(decoded, plainOnly = false)?.let { return Result(name, it.first, it.second, null) }
            if (searchText == null) searchText = placeTextFrom(current)
            current = if (isGoogle(current)) nextHop(current) else null
            hops++
        }

        // 3) No position in the link: fall back to searching for the place by name.
        return Result(name, null, null, searchText ?: name.ifBlank { null })
    }

    private fun findCoords(text: String, plainOnly: Boolean): Pair<Double, Double>? {
        val patterns = if (plainOnly) listOf(plainCoords) else coordPatterns
        for (p in patterns) {
            val m = p.find(text) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0 && !(lat == 0.0 && lng == 0.0)) return lat to lng
        }
        return null
    }

    /** "…/maps/place/Heuston+Station,+Dublin/…" -> "Heuston Station, Dublin". */
    private fun placeTextFrom(url: String): String? {
        val raw = placePath.find(url)?.groupValues?.get(1) ?: queryText.find(url)?.groupValues?.get(1) ?: return null
        val text = decode(raw).trim()
        return text.ifBlank { null }
    }

    private fun decode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    /** We only ever open Google's own links, never any other website. */
    private fun isGoogle(url: String): Boolean {
        val host = try { URL(url).host.lowercase() } catch (_: Exception) { return false }
        return host == "goo.gl" || host.endsWith(".goo.gl") ||
            host == "google.com" || host.endsWith(".google.com") ||
            Regex("""(^|\.)google\.[a-z.]{2,6}$""").containsMatchIn(host)
    }

    /** Asks the link where it points next, without loading the page itself. */
    private fun nextHop(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            val code = connection.responseCode
            if (code in 300..399) connection.getHeaderField("Location") else null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
