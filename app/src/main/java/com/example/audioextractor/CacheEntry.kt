package com.example.audioextractor

data class CacheEntry(
    val videoId: String,
    val videoName: String,
    val timestamp: Long, // Quando è stato messo in cache dal nostro server
    val youtubeExpiresAtMillis: Long, // Nuovo: tempo di scadenza di YouTube (assoluto)
    val accessCount: Int = 0 // 👈 nuovo campo
) {
    fun getRemainingTime(): Long {
        val remaining = youtubeExpiresAtMillis - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    fun isExpired(): Boolean {
        return System.currentTimeMillis() >= youtubeExpiresAtMillis
    }
}

