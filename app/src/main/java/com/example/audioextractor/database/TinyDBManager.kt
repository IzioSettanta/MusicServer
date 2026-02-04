package com.example.audioextractor.database

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * TinyDB-compatible database manager per URL YouTube
 * Compatibile con il formato TinyDB di Kodular/MIT App Inventor
 */
class TinyDBManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "TinyDB_URLDatabase"
        private const val TAG = "TinyDBManager"
        private const val KEY_URL_DATABASE = "url_database"
        private const val KEY_STATS = "database_stats"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val urlCache = ConcurrentHashMap<String, URLEntry>()

    init {
        loadDatabase()
    }

    /**
     * Entry per ogni URL nel database
     */
    data class URLEntry(
        val videoId: String,
        val videoTitle: String,
        var url: String,               // <- CAMBIA val IN var
        var expiresAt: Long,           // <- CAMBIA val IN var
        val type: String,
        var extractedAt: Long,
        var extractionCount: Int,
        var lastAccessedAt: Long = System.currentTimeMillis()
    ) {
        fun isValid(): Boolean {
            return System.currentTimeMillis() < expiresAt
        }

        fun toJSON(): JSONObject {
            return JSONObject().apply {
                put("video_id", videoId)
                put("video_title", videoTitle)
                put("url", url)
                put("expires_at", expiresAt)
                put("type", type)
                put("extracted_at", extractedAt)
                put("extraction_count", extractionCount)
                put("last_accessed_at", lastAccessedAt)
                put("is_valid", isValid())
                put("expires_in_seconds", (expiresAt - System.currentTimeMillis()) / 1000)
            }
        }

        companion object {
            fun fromJSON(json: JSONObject): URLEntry {
                return URLEntry(
                    videoId = json.getString("video_id"),
                    videoTitle = json.optString("video_title", "Unknown"),
                    url = json.getString("url"),
                    extractedAt = json.getLong("extracted_at"),
                    expiresAt = json.getLong("expires_at"),
                    extractionCount = json.getInt("extraction_count"),
                    type = json.optString("type", "audio"),
                    lastAccessedAt = json.optLong("last_accessed_at", json.getLong("extracted_at"))
                )
            }
        }
    }

    /**
     * Carica il database da SharedPreferences
     */
    private fun loadDatabase() {
        try {
            val jsonString = prefs.getString(KEY_URL_DATABASE, null) ?: return
            val jsonArray = JSONArray(jsonString)

            urlCache.clear()
            for (i in 0 until jsonArray.length()) {
                val entry = URLEntry.fromJSON(jsonArray.getJSONObject(i))
                val key = generateKey(entry.videoId, entry.type)
                urlCache[key] = entry
            }

            Log.d(TAG, "✅ Database caricato: ${urlCache.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore caricamento database", e)
            urlCache.clear()
        }
    }

    /**
     * Salva il database su SharedPreferences
     */
    @Synchronized
    private fun saveDatabase() {
        try {
            val jsonArray = JSONArray()
            urlCache.values.forEach { entry ->
                jsonArray.put(entry.toJSON())
            }

            prefs.edit()
                .putString(KEY_URL_DATABASE, jsonArray.toString())
                .putLong("${KEY_STATS}_last_save", System.currentTimeMillis())
                .apply()

            Log.d(TAG, "💾 Database salvato: ${urlCache.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore salvataggio database", e)
        }
    }

    /**
     * Genera chiave univoca per video + type
     */
    private fun generateKey(videoId: String, type: String): String = "${videoId}_${type}"

    /**
     * Ottiene un URL dal database se valido
     * @return URLEntry se trovato e valido, null altrimenti
     */
    fun getURL(videoId: String, type: String = "audio"): URLEntry? {
        val key = generateKey(videoId, type)
        val entry = urlCache[key] ?: return null

        return if (entry.isValid()) {
            // Aggiorna ultimo accesso
            entry.lastAccessedAt = System.currentTimeMillis()
            saveDatabase()
            Log.d(TAG, "🎯 Cache HIT: $videoId ($type) - count: ${entry.extractionCount}")
            entry
        } else {
            Log.d(TAG, "⏰ URL scaduto: $videoId ($type)")
            null
        }
    }

    /**
     * Salva o aggiorna un URL nel database
     * @param forceUpdate se true, aggiorna anche se l'URL è ancora valido
     */
    @Synchronized
    fun saveURL(
        videoId: String,
        videoTitle: String,
        url: String,
        expiresInSeconds: Long,
        type: String = "audio",
        forceUpdate: Boolean = false
    ): URLEntry {
        val key = generateKey(videoId, type)
        val currentTime = System.currentTimeMillis()
        val expiresAt = currentTime + (expiresInSeconds * 1000)

        val entry = urlCache[key]

        return if (entry != null && !forceUpdate) {
            entry.url = url
            entry.extractedAt = currentTime
            entry.expiresAt = expiresAt
            entry.extractionCount++
            entry.lastAccessedAt = currentTime
            Log.d(TAG, "🔄 URL aggiornata: $videoId ($type) - count: ${entry.extractionCount}")
            entry
        } else {
            URLEntry(
                videoId = videoId,
                videoTitle = videoTitle,
                url = url,
                extractedAt = currentTime,
                expiresAt = expiresAt,
                extractionCount = 1,
                type = type,
                lastAccessedAt = currentTime
            ).also {
                urlCache[key] = it
                Log.d(TAG, "➕ Nuovo URL salvato: $videoId ($type)")
            }
        }.also {
            saveDatabase()
        }
    }


    /**
     * Incrementa il contatore di estrazione
     */
    fun incrementExtractionCount(videoId: String, type: String = "audio") {
        val key = generateKey(videoId, type)
        urlCache[key]?.let { entry ->
            entry.extractionCount++
            entry.lastAccessedAt = System.currentTimeMillis()
            saveDatabase()
            Log.d(TAG, "📈 Contatore incrementato: $videoId - count: ${entry.extractionCount}")
        }
    }

    /**
     * Ottiene le ultime N URL estratte
     */
    fun getRecentURLs(limit: Int = 5): List<URLEntry> {
        return urlCache.values
            .sortedByDescending { it.lastAccessedAt }
            .take(limit)
    }

    /**
     * Ottiene le URL più utilizzate
     */
    fun getMostUsedURLs(limit: Int = 10): List<URLEntry> {
        return urlCache.values
            .sortedByDescending { it.extractionCount }
            .take(limit)
    }

    /**
     * Ottiene statistiche database
     */
    fun getStatistics(): DatabaseStats {
        val validURLs = urlCache.values.count { it.isValid() }
        val expiredURLs = urlCache.size - validURLs
        val totalExtractions = urlCache.values.sumOf { it.extractionCount }

        return DatabaseStats(
            totalURLs = urlCache.size,
            validURLs = validURLs,
            expiredURLs = expiredURLs,
            totalExtractions = totalExtractions,
            lastSaveTime = prefs.getLong("${KEY_STATS}_last_save", 0L)
        )
    }

    data class DatabaseStats(
        val totalURLs: Int,
        val validURLs: Int,
        val expiredURLs: Int,
        val totalExtractions: Int,
        val lastSaveTime: Long
    )

    /**
     * Esporta database in formato TinyDB compatibile con Kodular
     * Formato: JSON con struttura chiave-valore come TinyDB
     */
    fun exportToTinyDBFormat(): String {
        val tinyDBObject = JSONObject()

        // Metadati
        val metadata = JSONObject().apply {
            put("export_date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("total_entries", urlCache.size)
            put("format_version", "1.0")
            put("compatible_with", "Kodular TinyDB / MIT App Inventor")
        }
        tinyDBObject.put("metadata", metadata)

        // Database entries in formato TinyDB
        val entriesObject = JSONObject()
        urlCache.values.forEachIndexed { index, entry ->
            val entryKey = "url_${entry.videoId}_${entry.type}"
            entriesObject.put(entryKey, entry.toJSON())
        }
        tinyDBObject.put("entries", entriesObject)

        // Lista video IDs per facile accesso
        val videoIdsList = JSONArray()
        urlCache.values.map { it.videoId }.distinct().forEach { videoIdsList.put(it) }
        tinyDBObject.put("video_ids", videoIdsList)

        // Statistiche
        val stats = getStatistics()
        tinyDBObject.put("statistics", JSONObject().apply {
            put("total_urls", stats.totalURLs)
            put("valid_urls", stats.validURLs)
            put("expired_urls", stats.expiredURLs)
            put("total_extractions", stats.totalExtractions)
        })

        return tinyDBObject.toString(2) // Indentato per leggibilità
    }

    /**
     * Esporta e salva su file
     */
    fun exportToFile(outputFile: File): Boolean {
        return try {
            val tinyDBContent = exportToTinyDBFormat()
            outputFile.writeText(tinyDBContent)
            Log.d(TAG, "📤 Database esportato: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore esportazione", e)
            false
        }
    }

    /**
     * Pulisce URL scaduti
     */
    fun cleanExpiredURLs(): Int {
        val beforeSize = urlCache.size
        val currentTime = System.currentTimeMillis()

        val expiredKeys = urlCache.filterValues { !it.isValid() }.keys
        expiredKeys.forEach { urlCache.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            saveDatabase()
            Log.d(TAG, "🧹 Puliti ${expiredKeys.size} URL scaduti")
        }

        return expiredKeys.size
    }

    /**
     * Cancella tutto il database
     */
    fun clearDatabase() {
        urlCache.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "🗑️ Database cancellato")
    }

    /**
     * Ottiene dimensione database in bytes
     */
    fun getDatabaseSize(): Long {
        val jsonString = prefs.getString(KEY_URL_DATABASE, "[]") ?: "[]"
        return jsonString.toByteArray().size.toLong()
    }
}