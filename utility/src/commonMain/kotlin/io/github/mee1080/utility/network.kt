package io.github.mee1080.utility

import java.net.URL

// A pure JVM implementation to fetch the remote JSON data
suspend fun fetchFromUrl(url: String): String {
    return try {
        URL(url).readText()
    } catch (e: Exception) {
        System.err.println("Failed to fetch data from $url: ${e.message}")
        ""
    }
}