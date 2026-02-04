package com.example.audioextractor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.widget.RemoteViews
import java.io.IOException
import java.io.File
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
//import androidx.media3.common.MediaItem
//import androidx.media3.common.MediaMetadata
//import androidx.media3.common.PlaybackException
//import androidx.media3.common.Player
//import androidx.media3.common.util.UnstableApi
//import androidx.media3.exoplayer.ExoPlayer
//import androidx.media3.session.MediaSession
//import android.support.v4.media.session.MediaSessionCompat
//import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.audioextractor.downloader.OkHttpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.Locale
import java.util.Date

import com.example.audioextractor.database.TinyDBManager

// ═══════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════
data class CachedUrl(
    val url: String,
    val timestamp: Long,
    val videoId: String = "",
    val videoTitle: String = "",
    val type: String = "audio",
    val youtubeExpiresAtMillis: Long = 0L,
    var accessCount: Int = 0
)

data class ExtractedStreamInfo(
    val url: String,
    val title: String,
    val expiresInSeconds: Long,
    val type: String,
    val videoId: String
)

data class CachedDashManifest(
    val manifest: String,
    val timestamp: Long,
    val expiresAt: Long
)

data class UserPreferences(
    val topArtists: List<String> = emptyList(),
    val avgDuration: Long = 0,
    val lastWatched: List<String> = emptyList()
)

// ═══════════════════════════════════════════════════════════════════
// BITMAP MANAGER - Gestione ottimizzata memoria immagini
// ═══════════════════════════════════════════════════════════════════
class BitmapManager {
    private var currentArtwork: Bitmap? = null
    private val lock = Any()

    fun updateArtwork(newBitmap: Bitmap) {
        synchronized(lock) {
            currentArtwork?.recycle()
            currentArtwork = newBitmap
        }
    }

    fun getCurrentArtwork(): Bitmap? = synchronized(lock) { currentArtwork }

    fun release() {
        synchronized(lock) {
            currentArtwork?.recycle()
            currentArtwork = null
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// CACHE MANAGER - Gestione ottimizzata cache con batch writes
// ═══════════════════════════════════════════════════════════════════
class CacheManager(
    private val cacheFile: File,
    private val expirationMillis: Long = 30 * 60 * 1000L
) {
    private val liveCache = ConcurrentHashMap<String, CachedUrl>()
    private val fullHistory = ConcurrentHashMap<String, CachedUrl>()

    private var pendingSave = false
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    private val cleanupTimer = Timer().apply {
        scheduleAtFixedRate(object : TimerTask() {
            override fun run() { cleanExpiredEntries() }
        }, 60000, 60000)
    }

    var cacheHits = 0
        private set

    fun get(key: String): CachedUrl? {
        val cached = liveCache[key]
        return if (cached != null && !isExpired(cached)) cached else null
    }

    fun put(key: String, url: CachedUrl) {
        liveCache[key] = url
        fullHistory[key] = url
        scheduleSave()
    }

    fun incrementAccessCount(key: String) {
        liveCache[key]?.let {
            it.accessCount++
            fullHistory[key] = it
            cacheHits++
        }
        scheduleSave()
    }

    private fun isExpired(cached: CachedUrl): Boolean {
        val currentTime = System.currentTimeMillis()
        val serverExpired = (currentTime - cached.timestamp) > expirationMillis
        val youtubeExpired = cached.youtubeExpiresAtMillis < currentTime
        return serverExpired || youtubeExpired
    }

    private fun scheduleSave() {
        if (!pendingSave) {
            pendingSave = true
            saveScope.launch {
                delay(5000) // Batch writes ogni 5 secondi
                saveToDisk()
                pendingSave = false
            }
        }
    }

    private fun cleanExpiredEntries() {
        val keysToRemove = liveCache.filterValues { cached ->
            isExpired(cached)
        }.keys

        keysToRemove.forEach { liveCache.remove(it) }

        if (keysToRemove.isNotEmpty()) {
            android.util.Log.d("CacheManager", "Cleaned ${keysToRemove.size} expired entries")
        }
    }

    private suspend fun saveToDisk() {
        withContext(Dispatchers.IO) {
            try {
                val jsonArray = JSONArray()
                fullHistory.forEach { (key, cached) ->
                    jsonArray.put(JSONObject().apply {
                        put("cache_key", key)
                        put("url", cached.url)
                        put("timestamp", cached.timestamp)
                        put("video_id", cached.videoId)
                        put("video_title", cached.videoTitle)
                        put("type", cached.type)
                        put("youtube_expires_at_millis", cached.youtubeExpiresAtMillis)
                        put("access_count", cached.accessCount)
                    })
                }
                cacheFile.writeText(jsonArray.toString())
                android.util.Log.d("CacheManager", "💾 Saved ${fullHistory.size} items")
            } catch (e: Exception) {
                android.util.Log.e("CacheManager", "Save error", e)
            }
        }
    }

    suspend fun loadFromDisk() {
        withContext(Dispatchers.IO) {
            try {
                if (!cacheFile.exists()) return@withContext

                val jsonArray = JSONArray(cacheFile.readText())
                fullHistory.clear()
                liveCache.clear()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val key = obj.getString("cache_key")
                    val cached = CachedUrl(
                        url = obj.getString("url"),
                        timestamp = obj.getLong("timestamp"),
                        videoId = obj.optString("video_id"),
                        videoTitle = obj.optString("video_title"),
                        type = obj.optString("type"),
                        youtubeExpiresAtMillis = obj.optLong("youtube_expires_at_millis"),
                        accessCount = obj.optInt("access_count", 0)
                    )

                    fullHistory[key] = cached

                    if (!isExpired(cached)) {
                        liveCache[key] = cached
                    }
                }

                android.util.Log.d("CacheManager", "✅ Loaded ${fullHistory.size} items (${liveCache.size} valid)")
            } catch (e: Exception) {
                android.util.Log.e("CacheManager", "Load error", e)
                fullHistory.clear()
                liveCache.clear()
            }
        }
    }

    fun clear() {
        liveCache.clear()
    }

    fun size() = liveCache.size
    fun historySize() = fullHistory.size

    fun getLiveCache() = liveCache
    fun getFullHistory() = fullHistory

    fun shutdown() {
        cleanupTimer.cancel()
        runBlocking { saveToDisk() }
        saveScope.cancel()
    }
}

// ═══════════════════════════════════════════════════════════════════
// NETWORK CLIENT - Client HTTP ottimizzato con cache
// ═══════════════════════════════════════════════════════════════════
class NetworkClient(private val context: Context) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    private val imageLoader = ImageLoader.Builder(context)
        .crossfade(true)
        .okHttpClient(okHttpClient)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizePercent(0.15)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .maxSizeBytes(50 * 1024 * 1024)
                .build()
        }
        .build()

    suspend fun fetchBitmap(url: String, timeout: Long = 3000): Bitmap? {
        return withTimeoutOrNull(timeout) {
            try {
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(512)
                    .build()

                val result = imageLoader.execute(req)
                if (result is SuccessResult) {
                    result.drawable.toBitmap()
                } else null
            } catch (e: Exception) {
                android.util.Log.e("NetworkClient", "Fetch error: $url", e)
                null
            }
        }
    }

    fun shutdown() {
        imageLoader.shutdown()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }
}

// ═══════════════════════════════════════════════════════════════════
// RECOMMENDATION ENGINE - Sistema raccomandazioni personalizzato
// ═══════════════════════════════════════════════════════════════════
class RecommendationEngine(
    private val cacheManager: CacheManager,
    private val service: HttpServerService // Pass the service to access its internal methods
) {

    suspend fun generateRecommendations(maxResults: Int = 20): String {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = analyzeUserPreferences()
                val recommendations = mutableListOf<JSONObject>()

                // 1. Video correlati ai più ascoltati
                prefs.lastWatched.take(3).forEach { videoId ->
                    try {
                        val relatedJson = service.getRelatedVideosInternal(videoId, 5)
                        val related = JSONObject(relatedJson)
                        val items = related.optJSONArray("suggestions")
                        items?.let {
                            for (i in 0 until it.length()) {
                                recommendations.add(it.getJSONObject(i))
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("RecommendationEngine", "Error fetching related for $videoId", e)
                    }
                }

                // 2. Search basata su artisti preferiti
                prefs.topArtists.take(2).forEach { artist ->
                    try {
                        val searchJson = service.performSearchInternal(artist, "video", "", 5)
                        val search = JSONObject(searchJson)
                        val items = search.optJSONArray("results")
                        items?.let {
                            for (i in 0 until it.length()) {
                                recommendations.add(it.getJSONObject(i))
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("RecommendationEngine", "Error searching for $artist", e)
                    }
                }

                // Rimuovi duplicati e limita
                val unique = recommendations
                    .distinctBy { it.optString("videoId", it.optString("id", "")) }
                    .take(maxResults)

                JSONObject().apply {
                    put("status", "success")
                    put("recommendation_type", "personalized")
                    put("based_on", JSONObject().apply {
                        put("top_artists", JSONArray(prefs.topArtists))
                        put("watched_count", prefs.lastWatched.size)
                        put("avg_duration_sec", prefs.avgDuration)
                    })
                    put("result_count", unique.size)
                    put("results", JSONArray(unique))
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("RecommendationEngine", "Error", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", e.message)
                }.toString()
            }
        }
    }

    private fun analyzeUserPreferences(): UserPreferences {
        val history = cacheManager.getFullHistory()

        val mostPlayed = history.values
            .sortedByDescending { it.accessCount }
            .take(50)

        val artists = mostPlayed
            .map { extractArtist(it.videoTitle) }
            .filter { it != "Unknown" }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }

        val lastWatched = mostPlayed
            .sortedByDescending { it.timestamp }
            .take(10)
            .map { it.videoId }

        return UserPreferences(
            topArtists = artists,
            lastWatched = lastWatched
        )
    }

    private fun extractArtist(title: String): String {
        val patterns = listOf(" - ", ": ", " | ")
        for (pattern in patterns) {
            if (title.contains(pattern)) {
                return title.substringBefore(pattern).trim()
            }
        }
        return "Unknown"
    }
}

// ═══════════════════════════════════════════════════════════════════
// HTTP SERVER SERVICE - Servizio principale ottimizzato
// ═══════════════════════════════════════════════════════════════════
class HttpServerService : Service() {

    companion object {
        private var isNewPipeInitialized = false
        const val SERVER_PORT = 8080
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_TOGGLE = "TOGGLE"
        const val ACTION_TERMINATE = "TERMINATE"
        const val ACTION_SHOW_INFO = "SHOW_INFO"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "HttpServerChannel"
    }

    // Server components
    private var serverSocket: ServerSocket? = null
    @Volatile private var isServerRunning = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Managers
    private lateinit var cacheManager: CacheManager
    private lateinit var networkClient: NetworkClient
    private lateinit var bitmapManager: BitmapManager
    private lateinit var recommendationEngine: RecommendationEngine

    private val dashManifestCache = ConcurrentHashMap<String, CachedDashManifest>()

    @Volatile private var serverStartTime: Long = 0L
    private var totalRequests = 0

    // Stats timer
    private var statsUpdateTimer: Timer? = null

    private lateinit var tinyDBManager: TinyDBManager

    // Connection pool
    private val clientExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() * 2,
        ThreadFactory { runnable ->
            Thread(runnable, "HttpClient-${System.nanoTime()}").apply {
                isDaemon = true
            }
        }
    )

    private val cacheFile by lazy { File(filesDir, "cache_store.json") }

    private val COMMAND_ACTIONS = listOf(
        "com.example.audioextractor.action.EXTRACT_ONLY",
        "com.example.audioextractor.action.SERVER_COMMAND",
        "com.example.audioextractor.action.PLAYER_STATUS_REQUEST"
    )

    override fun onCreate() {
        super.onCreate()

        if (!isNewPipeInitialized) {
            NewPipe.init(OkHttpDownloader.createDefault())
            isNewPipeInitialized = true
        }

        // Inizializza managers
        cacheManager = CacheManager(cacheFile)
        networkClient = NetworkClient(this)
        bitmapManager = BitmapManager()
        tinyDBManager = TinyDBManager(this)
        recommendationEngine = RecommendationEngine(cacheManager, this) // Pass 'this' as HttpServerService

        createNotificationChannel()

        serviceScope.launch { cacheManager.loadFromDisk() }

        val filter = IntentFilter()
        COMMAND_ACTIONS.forEach { filter.addAction(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        startStatsUpdateTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent?.getStringExtra("COMMAND") ?: ACTION_START
        when (command) {
            ACTION_START -> {
                if (!isServerRunning) {
                    startForegroundService()
                    startHttpServer()
                    writeStatus("Servizio avviato sulla porta $SERVER_PORT", true)
                }
            }
            ACTION_STOP -> {
                stopHttpServer()
                updateNotification("Servizio arrestato")
                writeStatus("Servizio arrestato", false)
            }
            ACTION_TOGGLE -> {
                if (isServerRunning) {
                    stopHttpServer()
                    updateNotification("Servizio arrestato")
                } else {
                    startHttpServer()
                    updateNotification("Servizio riavviato - Cache: ${cacheManager.size()}")
                }
            }
            ACTION_TERMINATE -> {
                stopHttpServer()
                writeStatus("Servizio terminato", false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SHOW_INFO -> showServerInfo()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(commandReceiver) } catch (e: Exception) { }

        statsUpdateTimer?.cancel()
        stopHttpServer()

        bitmapManager.release()
        networkClient.shutdown()
        cacheManager.shutdown() // Ensure cache is saved and resources released

        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        cacheManager.shutdown()
        super.onTaskRemoved(rootIntent)
    }

    private fun startHttpServer() {
        if (isServerRunning) return

        serviceScope.launch {
            try {
                serverSocket = ServerSocket(SERVER_PORT).apply {
                    reuseAddress = true
                    soTimeout = 0
                }
                isServerRunning = true
                if (serverStartTime == 0L) serverStartTime = System.currentTimeMillis()

                updateNotification("✅ Servizio avviato - Cache: ${cacheManager.size()}")
                android.util.Log.d("HttpServerService", "Servizio avviato sulla porta $SERVER_PORT")

                while (isServerRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let { socket ->
                            clientExecutor.execute {
                                runBlocking { handleClient(socket) }
                            }
                        }
                    } catch (e: IOException) {
                        if (isServerRunning) {
                            android.util.Log.e("HttpServerService", "Accept error", e)
                            delay(100)
                        }
                    }
                }
            } catch (e: IOException) {
                android.util.Log.e("HttpServerService", "Start error", e)
                isServerRunning = false
                updateNotification("❌ Errore servizio")
                writeStatus("Errore avvio servizio: ${e.message}", false)
            }
        }
    }

    private fun stopHttpServer() {
        isServerRunning = false
        clientExecutor.shutdown()
        try {
            if (!clientExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                clientExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            clientExecutor.shutdownNow()
        }

        try {
            serverSocket?.close()
            serverSocket = null
            cacheManager.clear() // Clear live cache, full history is managed by CacheManager.shutdown()
            dashManifestCache.clear()
            serverStartTime = 0L
            android.util.Log.d("HttpServerService", "Servizio arrestato")
        } catch (e: IOException) {
            android.util.Log.e("HttpServerService", "Stop error", e)
        }
    }

    // ═══════════════════ CLIENT HANDLER ═══════════════════

    @SuppressLint("LongLogTag")
    private suspend fun handleClient(clientSocket: Socket) {
        try {
            clientSocket.use { socket ->
                socket.soTimeout = 30000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                var requestLine: String? = null
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (requestLine == null && line?.isNotEmpty() == true) {
                        requestLine = line
                    }
                    if (line?.isEmpty() == true) break
                }

                if (requestLine == null) {
                    sendHttpResponse(writer, 400, """{"status":"error","message":"Empty request"}""")
                    return
                }

                // ═══════════════════ ROUTING ═══════════════════

                when {

                    requestLine.startsWith("GET /video_formats") -> handleVideoFormats(requestLine, writer)
                    requestLine.startsWith("GET /search") -> handleSearch(requestLine, writer)
                    requestLine.startsWith("GET /trending") -> handleTrending(requestLine, writer)
                    requestLine.startsWith("GET /playlist") -> handlePlaylist(requestLine, writer)
                    requestLine.startsWith("GET /metadata") -> handleMetadata(requestLine, writer)
                    requestLine.startsWith("GET /kiosk/list") -> handleKioskList(writer)
                    requestLine.startsWith("GET /kiosk") -> handleKiosk(requestLine, writer)
                    requestLine.startsWith("GET /channel/videos") -> handleChannelVideos(requestLine, writer)
                    requestLine.startsWith("GET /channel") -> handleChannel(requestLine, writer)
                    requestLine.startsWith("GET /suggestions") -> handleSuggestions(requestLine, writer)
                    requestLine.startsWith("GET /recommendations") -> handleRecommendations(requestLine, writer) // NEW ENDPOINT

                    requestLine.startsWith("GET /get_manifest") -> handleGetManifest(requestLine, writer)
                    requestLine.startsWith("GET /dash_manifest/") -> handleDashManifest(requestLine, writer)

                    requestLine.startsWith("GET /cache/list") -> handleCacheList(writer)
                    requestLine.startsWith("GET /cache/clear") -> handleCacheClear(writer)
                    requestLine.startsWith("GET /cache/download") -> handleCacheDownload(writer)

                    requestLine.startsWith("GET /extract_merged") -> handleExtractMerged(requestLine, writer)
                    requestLine.startsWith("GET /extract") -> handleExtract(requestLine, writer)

                    requestLine.startsWith("GET /database/stats") -> handleDatabaseStats(writer)
                    requestLine.startsWith("GET /database/export") -> handleDatabaseExport(writer)
                    requestLine.startsWith("GET /database/clean") -> handleDatabaseClean(writer)

                    requestLine.startsWith("GET /status") -> {
                        totalRequests++
                        val statusResponse = JSONObject().apply {
                            put("status", "running")
                            put("port", SERVER_PORT)
                            put("cache_size", cacheManager.size())
                            put("total_requests", totalRequests)
                            put("cache_hits", cacheManager.cacheHits)
                            put("uptime_seconds", if (serverStartTime == 0L) 0L else (System.currentTimeMillis() - serverStartTime) / 1000L)
                            put("player_state", "NOT_AVAILABLE")
                            put("player_is_playing", false)
                        }.toString()
                        sendHttpResponse(writer, 200, statusResponse)
                    }

                    requestLine.startsWith("GET /") -> handleHelp(writer)
                    requestLine.startsWith("GET /extract") -> handleExtract(requestLine, writer)

                    else -> sendHttpResponse(writer, 404, """{"status":"error","message":"Endpoint not found"}""")
                }

            }
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Client handler error", e)
        }
    }


    // ═══════════════════ CONTENT HANDLERS (1/2) ═══════════════════

    private suspend fun handleVideoFormats(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val videoId = params["videoId"] ?: params["id"]

        if (videoId != null && videoId.isNotEmpty()) {
            try {
                val formatsResult = getVideoFormats(videoId)
                sendHttpResponse(writer, 200, formatsResult)
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Video formats error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Error fetching formats","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"videoId missing"}""")
        }
    }

    private suspend fun handleSearch(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val query = params["q"] ?: params["query"]
        val filter = params["filter"] ?: "all"
        val sortFilter = params["sort"] ?: ""
        val maxResults = params["max"]?.toIntOrNull() ?: 20

        if (query != null && query.isNotEmpty()) {
            try {
                val searchResult = performSearchInternal(query, filter, sortFilter, maxResults)
                sendHttpResponse(writer, 200, searchResult)
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Search error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Search error","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"query missing"}""")
        }
    }

    private suspend fun handleTrending(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val countryCode = params["country"] ?: "IT"
        val maxResults = params["max"]?.toIntOrNull() ?: 20

        try {
            val trendingResult = getDailyTopVideos(countryCode, maxResults)
            sendHttpResponse(writer, 200, trendingResult)
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Trending error", e)
            sendHttpResponse(writer, 500, """{"status":"error","message":"Trending error","details":"${e.message}"}""")
        }
    }

    private suspend fun handlePlaylist(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val playlistId = params["id"] ?: params["playlistId"]
        val maxVideos = params["max"]?.toIntOrNull() ?: 50

        if (playlistId != null && playlistId.isNotEmpty()) {
            try {
                val playlistResult = getPlaylistInfo(playlistId, maxVideos)
                sendHttpResponse(writer, 200, playlistResult)
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Playlist error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Playlist error","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"playlistId missing"}""")
        }
    }

    // ═══════════════════ CONTENT HANDLERS (2/2) ═══════════════════

    private suspend fun handleMetadata(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val videoId = params["videoId"] ?: params["id"]

        if (videoId != null && videoId.isNotEmpty()) {
            try {
                val metadataResult = getVideoMetadata(videoId)
                sendHttpResponse(writer, 200, metadataResult)
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Metadata error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Metadata error","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"videoId missing"}""")
        }
    }

    private suspend fun handleKioskList(writer: PrintWriter) {
        totalRequests++
        try {
            val kioskListResult = getAvailableKiosks()
            sendHttpResponse(writer, 200, kioskListResult)
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Kiosk list error", e)
            sendHttpResponse(writer, 500, """{"status":"error","message":"Kiosk list error","details":"${e.message}"}""")
        }
    }

    private suspend fun handleKiosk(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val kioskId = params["id"] ?: "Trending"
        val maxResults = params["max"]?.toIntOrNull() ?: 20

        try {
            val kioskResult = getKiosk(kioskId, maxResults)
            sendHttpResponse(writer, 200, kioskResult)
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Kiosk error", e)
            sendHttpResponse(writer, 500, """{"status":"error","message":"Kiosk error","details":"${e.message}"}""")
        }
    }

    private suspend fun handleChannelVideos(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val channelName = params["name"] ?: params["channelName"]
        val maxVideos = params["max"]?.toIntOrNull() ?: 20

        if (channelName != null && channelName.isNotEmpty()) {
            try {
                val channelVideosResult = searchChannelVideos(channelName, maxVideos)
                sendHttpResponse(writer, 200, channelVideosResult)
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Channel videos error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Channel videos error","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"channelName missing"}""")
        }
    }

    private suspend fun handleChannel(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val channelId = params["id"] ?: params["channelId"]

        if (channelId != null && channelId.isNotEmpty()) {
            try {
                val channelResult = getChannelInfo(channelId, 0)
                sendHttpResponse(writer, 200, channelResult)
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Channel error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Channel error","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"channelId missing"}""")
        }
    }

    private suspend fun handleSuggestions(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val videoId = params["videoId"] ?: params["id"]
        val maxResults = params["max"]?.toIntOrNull() ?: 20

        if (videoId != null && videoId.isNotEmpty()) {
            try {
                val suggestionsResult = getRelatedVideosInternal(videoId, maxResults)
                sendHttpResponse(writer, 200, suggestionsResult)
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Suggestions error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Suggestions error","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"videoId missing"}""")
        }
    }

    private suspend fun handleRecommendations(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val maxResults = params["max"]?.toIntOrNull() ?: 20

        try {
            val result = recommendationEngine.generateRecommendations(maxResults)
            sendHttpResponse(writer, 200, result)
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Recommendations error", e)
            sendHttpResponse(writer, 500, """{"status":"error","message":"Recommendations error","details":"${e.message}"}""")
        }
    }

    private suspend fun handleGetManifest(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val videoId = params["videoId"] ?: params["id"]

        if (videoId != null && videoId.isNotEmpty()) {
            try {
                val manifestResult = getManifestUrl(videoId)
                sendHttpResponse(writer, 200, manifestResult)
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Manifest error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Manifest error","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"videoId missing"}""")
        }
    }

    private fun handleDashManifest(requestLine: String, writer: PrintWriter) {
        val videoIdMatch = requestLine.substringAfter("/dash_manifest/").substringBefore(" ").substringBefore(".mpd")
        val cachedManifest = dashManifestCache[videoIdMatch]

        if (cachedManifest != null && System.currentTimeMillis() < cachedManifest.expiresAt) {
            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: application/dash+xml; charset=UTF-8")
            writer.println("Access-Control-Allow-Origin: *")
            writer.println("Connection: close")
            writer.println("Content-Length: ${cachedManifest.manifest.toByteArray(Charsets.UTF_8).size}")
            writer.println()
            writer.print(cachedManifest.manifest)
            writer.flush()
        } else {
            sendHttpResponse(writer, 404, """{"status":"error","message":"Manifest not found or expired"}""")
        }
    }

    // ═══════════════════ CACHE HANDLERS ═══════════════════

    private fun handleCacheList(writer: PrintWriter) {
        try {
            val cacheList = JSONArray()
            val currentTime = System.currentTimeMillis()

            cacheManager.getLiveCache().forEach { (key, cachedUrl) ->
                val youtubeRemaining = cachedUrl.youtubeExpiresAtMillis - currentTime

                if (youtubeRemaining > 0) {
                    cacheList.put(JSONObject().apply {
                        put("cache_key", key)
                        put("video_id", cachedUrl.videoId)
                        put("video_title", cachedUrl.videoTitle)
                        put("type", cachedUrl.type)
                        put("url", cachedUrl.url)
                        put("timestamp", cachedUrl.timestamp)
                        put("youtube_expires_at_millis", cachedUrl.youtubeExpiresAtMillis)
                        put("youtube_expires_in_seconds", youtubeRemaining / 1000)
                        put("access_count", cachedUrl.accessCount)
                    })
                }
            }

            val response = JSONObject().apply {
                put("status", "success")
                put("cache_count", cacheList.length())
                put("total_cache_hits", cacheManager.cacheHits)
                put("cache_items", cacheList)
            }.toString()

            sendHttpResponse(writer, 200, response)
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Cache list error", e)
            sendHttpResponse(writer, 500, """{"status":"error","message":"Cache list error"}""")
        }
    }

    private fun handleCacheClear(writer: PrintWriter) {
        totalRequests++
        try {
            val sizeBefore = cacheManager.size()
            cacheManager.clear()

            sendHttpResponse(writer, 200, """{"status":"success","message":"Cache cleared","items_removed":$sizeBefore}""")
            android.util.Log.d("HttpServerService", "Cache cleared: $sizeBefore items")
        } catch (e: Exception) {
            sendHttpResponse(writer, 500, """{"status":"error","message":"Cache clear error"}""")
        }
    }

    private fun handleCacheDownload(writer: PrintWriter) {
        try {
            if (!cacheFile.exists()) {
                sendHttpResponse(writer, 404, """{"status":"error","message":"No cache file"}""")
                return
            }

            val rawJsonString = cacheFile.readText()
            val rawJsonArray = JSONArray(rawJsonString)
            val filteredJsonArray = JSONArray()

            for (i in 0 until rawJsonArray.length()) {
                val originalObj = rawJsonArray.getJSONObject(i)
                val filteredObj = JSONObject().apply {
                    put("video_id", originalObj.optString("video_id", ""))
                    put("video_title", originalObj.optString("video_title", ""))
                    put("type", originalObj.optString("type", ""))
                    put("timestamp", originalObj.optLong("timestamp", 0L))
                    put("access_count", originalObj.optInt("access_count", 0))
                }
                filteredJsonArray.put(filteredObj)
            }

            val finalJsonOutput = filteredJsonArray.toString(2)

            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: application/json; charset=UTF-8")
            writer.println("Content-Disposition: attachment; filename=\"cache_history.json\"")
            writer.println("Access-Control-Allow-Origin: *")
            writer.println("Connection: close")
            writer.println("Content-Length: ${finalJsonOutput.toByteArray(Charsets.UTF_8).size}")
            writer.println()
            writer.print(finalJsonOutput)
            writer.flush()
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Cache download error", e)
            sendHttpResponse(writer, 500, """{"status":"error","message":"Cache download error"}""")
        }
    }


    // ═══════════════════ EXTRACTION HANDLERS ═══════════════════
    private suspend fun handleExtractMerged(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val videoId = params["videoId"]
        val quality = params["quality"]?.toIntOrNull() ?: 1080
        val format = params["format"]?.lowercase() ?: "json"

        if (videoId != null && videoId.isNotEmpty()) {
            try {
                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"
                val info = StreamInfo.getInfo(youtubeUrl)

                val videoOnlyStreams = info.videoOnlyStreams?.filterNotNull() ?: emptyList()
                val audioStreams = info.audioStreams?.filterNotNull() ?: emptyList()

                if (videoOnlyStreams.isEmpty() || audioStreams.isEmpty()) {
                    sendHttpResponse(writer, 404, """{"status":"error","message":"No streams available"}""")
                    return
                }

                val bestVideo = videoOnlyStreams
                    .filter { (it.resolution?.replace("p", "")?.toIntOrNull() ?: 0) <= quality }
                    .maxByOrNull { it.bitrate }

                val bestAudio = audioStreams.maxByOrNull { it.bitrate }

                if (bestVideo == null || bestAudio == null) {
                    sendHttpResponse(writer, 404, """{"status":"error","message":"No suitable streams"}""")
                    return
                }

                val estimatedExpiresInSeconds = (info.duration.toLong() * 2).coerceAtLeast(3600L).coerceAtMost(6 * 3600L)

                if (format == "url") {
                    val urlResponse = "${bestVideo.url}|||${bestAudio.url}"
                    sendPlainTextResponse(writer, 200, urlResponse)
                } else {
                    val response = JSONObject().apply {
                        put("status", "success")
                        put("video_id", videoId)
                        put("video_title", info.name)
                        put("type", "merged_sources")
                        put("video", JSONObject().apply {
                            put("url", bestVideo.url)
                            put("resolution", bestVideo.resolution)
                            put("bitrate", bestVideo.bitrate)
                        })
                        put("audio", JSONObject().apply {
                            put("url", bestAudio.url)
                            put("bitrate", bestAudio.bitrate)
                        })
                        put("expires_in_seconds", estimatedExpiresInSeconds)
                    }.toString()

                    sendHttpResponse(writer, 200, response)
                }

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Extract merged error", e)
                sendHttpResponse(writer, 500, """{"status":"error","message":"Extract merged error","details":"${e.message}"}""")
            }
        } else {
            sendHttpResponse(writer, 400, """{"status":"error","message":"videoId missing"}""")
        }
    }

    private suspend fun handleExtract(requestLine: String, writer: PrintWriter) {
        totalRequests++
        val params = parseUrlParameters(requestLine)
        val videoIdParam = params["videoId"]
        val extractType = params["type"] ?: "audio"
        val quality = params["quality"]?.toIntOrNull() ?: 720
        val onlyUrl = params["OnlyURL"]?.lowercase() != "false"

        if (videoIdParam != null && videoIdParam.isNotEmpty()) {
            // ⭐ STEP 1: Controlla nel TinyDB
            val cachedEntry = tinyDBManager.getURL(videoIdParam, extractType)

            if (cachedEntry != null && cachedEntry.isValid()) {
                // ⭐ URL trovato nel database e ancora valido
                tinyDBManager.incrementExtractionCount(videoIdParam, extractType)

                val result = if (onlyUrl) {
                    cachedEntry.url
                } else {
                    JSONObject().apply {
                        put("status", "success")
                        put("url", cachedEntry.url)
                        put("cached", true)
                        put("source", "tinydb")
                        put("video_id", cachedEntry.videoId)
                        put("video_title", cachedEntry.videoTitle)
                        put("type", cachedEntry.type)
                        put("extraction_count", cachedEntry.extractionCount)
                        put("expires_in_seconds", (cachedEntry.expiresAt - System.currentTimeMillis()) / 1000)
                        put("extracted_at", cachedEntry.extractedAt)
                    }.toString()
                }

                android.util.Log.d("Extract", "🎯 TinyDB HIT: $videoIdParam - count: ${cachedEntry.extractionCount}")
                if (onlyUrl) sendPlainTextResponse(writer, 200, result)
                else sendHttpResponse(writer, 200, result)
                return
            }

            // ⭐ STEP 2: URL non trovato o scaduto - estrai nuovo
            android.util.Log.d("Extract", "🔍 TinyDB MISS: $videoIdParam - estrazione in corso...")

            try {
                val streamInfo = extractStream(videoIdParam, extractType, quality)

                if (streamInfo == null) {
                    val errorMsg = if (onlyUrl) "ERROR: Extraction failed" else """{"status":"error","message":"Extraction failed"}"""
                    sendPlainTextResponse(writer, 500, errorMsg)
                    return
                }

                if (streamInfo.url.isNotEmpty()) {
                    // ⭐ STEP 3: Salva nel TinyDB
                    val savedEntry = tinyDBManager.saveURL(
                        videoId = streamInfo.videoId,
                        videoTitle = streamInfo.title,
                        url = streamInfo.url,
                        expiresInSeconds = streamInfo.expiresInSeconds,
                        type = streamInfo.type,
                        forceUpdate = cachedEntry != null // Forza update se stava scadendo
                    )

                    android.util.Log.d("Extract", "💾 TinyDB SAVED: $videoIdParam - count: ${savedEntry.extractionCount}")

                    // ⭐ Mantieni anche la cache legacy per compatibilità
                    val cacheKey = if (extractType == "audio") "${videoIdParam}_audio" else "${videoIdParam}_video_${quality}"
                    val newEntry = CachedUrl(
                        url = streamInfo.url,
                        timestamp = System.currentTimeMillis(),
                        videoId = streamInfo.videoId,
                        videoTitle = streamInfo.title,
                        type = streamInfo.type,
                        youtubeExpiresAtMillis = System.currentTimeMillis() + (streamInfo.expiresInSeconds * 1000),
                        accessCount = savedEntry.extractionCount
                    )
                    cacheManager.put(cacheKey, newEntry)
                }

                val resultResponse = if (onlyUrl) {
                    streamInfo.url
                } else {
                    JSONObject().apply {
                        put("status", "success")
                        put("url", streamInfo.url)
                        put("cached", false)
                        put("source", "fresh_extraction")
                        put("video_id", streamInfo.videoId)
                        put("video_title", streamInfo.title)
                        put("type", streamInfo.type)
                        put("youtube_expires_in_seconds", streamInfo.expiresInSeconds)
                        put("extraction_count", 1)
                    }.toString()
                }

                sendPlainTextResponse(writer, 200, resultResponse)

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Extract error", e)
                val errorMsg = if (onlyUrl) "ERROR: ${e.message}" else """{"status":"error","message":"${e.message}"}"""
                sendPlainTextResponse(writer, 500, errorMsg)
            }
        } else {
            val errorResponse = if (onlyUrl) "ERROR: videoId missing" else """{"status":"error","message":"videoId missing"}"""
            sendPlainTextResponse(writer, 400, errorResponse)
        }
    }

    private fun handleDatabaseStats(writer: PrintWriter) {
        totalRequests++
        try {
            val stats = tinyDBManager.getStatistics()
            val recentURLs = tinyDBManager.getRecentURLs(5)
            val mostUsed = tinyDBManager.getMostUsedURLs(5)

            val response = JSONObject().apply {
                put("status", "success")
                put("statistics", JSONObject().apply {
                    put("total_urls", stats.totalURLs)
                    put("valid_urls", stats.validURLs)
                    put("expired_urls", stats.expiredURLs)
                    put("total_extractions", stats.totalExtractions)
                    put("database_size_bytes", tinyDBManager.getDatabaseSize())
                    put("last_save_time", stats.lastSaveTime)
                })

                put("recent_urls", JSONArray().apply {
                    recentURLs.forEach { entry ->
                        put(entry.toJSON())
                    }
                })

                put("most_used", JSONArray().apply {
                    mostUsed.forEach { entry ->
                        put(entry.toJSON())
                    }
                })
            }.toString()

            sendHttpResponse(writer, 200, response)
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Database stats error", e)
            sendHttpResponse(writer, 500, """{"status":"error","message":"${e.message}"}""")
        }
    }

    private fun handleDatabaseExport(writer: PrintWriter) {
        totalRequests++
        try {
            val exportContent = tinyDBManager.exportToTinyDBFormat()

            writer.println("HTTP/1.1 200 OK")
            writer.println("Content-Type: application/json; charset=UTF-8")
            writer.println("Content-Disposition: attachment; filename=\"tinydb_export_${System.currentTimeMillis()}.json\"")
            writer.println("Access-Control-Allow-Origin: *")
            writer.println("Connection: close")
            writer.println("Content-Length: ${exportContent.toByteArray(Charsets.UTF_8).size}")
            writer.println()
            writer.print(exportContent)
            writer.flush()

            android.util.Log.d("HttpServerService", "📤 Database esportato")
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Export error", e)
            sendHttpResponse(writer, 500, """{"status":"error","message":"Export failed"}""")
        }
    }

    private fun handleDatabaseClean(writer: PrintWriter) {
        totalRequests++
        try {
            val removedCount = tinyDBManager.cleanExpiredURLs()
            val response = JSONObject().apply {
                put("status", "success")
                put("message", "Expired URLs cleaned")
                put("removed_count", removedCount)
            }.toString()

            sendHttpResponse(writer, 200, response)
            android.util.Log.d("HttpServerService", "🧹 Cleaned $removedCount expired URLs")
        } catch (e: Exception) {
            sendHttpResponse(writer, 500, """{"status":"error","message":"Clean failed"}""")
        }
    }

    // ═══════════════════ STATUS & HELP HANDLERS ═══════════════════

    private fun handleHelp(writer: PrintWriter) {
        val helpResponse = JSONObject().apply {
            put("status", "info")
            put("message", "Music Server API - Available Endpoints")
            put("endpoints", JSONObject().apply {
                put("extract", "GET /extract?videoId=ID&type=audio|video&OnlyURL=true")
                put("extract_merged", "GET /extract_merged?videoId=ID&quality=1080&format=url|json")
                put("get_manifest", "GET /get_manifest?videoId=ID")
                put("extplay", "GET /extplay?videoId=ID&type=audio|video")
                put("search", "GET /search?q=query&filter=all|video|channel|playlist&max=20")
                put("trending", "GET /trending?country=IT&max=20")
                put("playlist", "GET /playlist?id=PLxxxxxx&max=50")
                put("metadata", "GET /metadata?videoId=ID")
                put("kiosk", "GET /kiosk?id=Trending&max=20")
                put("kiosk_list", "GET /kiosk/list")
                put("channel", "GET /channel?id=UCxxxxxx")
                put("channel_videos", "GET /channel/videos?name=channel+name&max=20")
                put("suggestions", "GET /suggestions?videoId=ID&max=20")
                put("recommendations", "GET /recommendations?max=20") // NEW ENDPOINT
                put("video_formats", "GET /video_formats?videoId=ID")
                put("cache_list", "GET /cache/list")
                put("cache_clear", "GET /cache/clear")
                put("cache_download", "GET /cache/download")
                put("player_play", "GET /player/play?url=URL")
                put("player_pause", "GET /player/pause")
                put("player_resume", "GET /player/resume")
                put("player_stop", "GET /player/stop")
                put("player_seek", "GET /player/seek?position_ms=1000")
                put("player_status", "GET /player/status")
                put("status", "GET /status")
                put("database_stats", "GET /database/stats - Get TinyDB statistics and recent URLs")
                put("database_export", "GET /database/export - Export database in TinyDB format")
                put("database_clean", "GET /database/clean - Remove expired URLs")
            })
            put("stats", "Requests: $totalRequests, Cache hits: ${cacheManager.cacheHits}, Cache size: ${cacheManager.size()}")
        }.toString()

        sendHttpResponse(writer, 200, helpResponse)
    }

    // ═══════════════════ HELPER FUNCTIONS ═══════════════════

    private fun parseUrlParameters(requestLine: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val parts = requestLine.split(" ")
        if (parts.size < 2) return params
        val urlPart = parts[1]
        if (urlPart.contains("?")) {
            val queryString = urlPart.split("?", limit = 2)[1]
            queryString.split("&").forEach { param ->
                val keyValue = param.split("=", limit = 2)
                if (keyValue.size == 2) {
                    val key = URLDecoder.decode(keyValue[0], "UTF-8")
                    val value = URLDecoder.decode(keyValue[1], "UTF-8")
                    params[key] = value
                } else if (keyValue.size == 1) {
                    val key = URLDecoder.decode(keyValue[0], "UTF-8")
                    params[key] = ""
                }
            }
        }
        return params
    }

    private fun sendHttpResponse(writer: PrintWriter, statusCode: Int, body: String) {
        try {
            writer.println("HTTP/1.1 $statusCode OK")
            writer.println("Content-Type: application/json; charset=UTF-8")
            writer.println("Access-Control-Allow-Origin: *")
            writer.println("Connection: close")
            writer.println("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}")
            writer.println()
            writer.print(body)
            writer.flush()
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Response error", e)
        }
    }

    private fun sendPlainTextResponse(writer: PrintWriter, statusCode: Int, body: String) {
        try {
            writer.println("HTTP/1.1 $statusCode OK")
            writer.println("Content-Type: text/plain; charset=UTF-8")
            writer.println("Access-Control-Allow-Origin: *")
            writer.println("Connection: close")
            writer.println("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}")
            writer.println()
            writer.print(body)
            writer.flush()
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Response error", e)
        }
    }

    private fun writeStatus(message: String, success: Boolean) {
        try {
            val status = JSONObject().apply {
                put("message", message)
                put("success", success)
                put("server_running", isServerRunning)
                put("timestamp", System.currentTimeMillis())
                put("cache_size", cacheManager.size())
                put("total_requests", totalRequests)
                put("cache_hits", cacheManager.cacheHits)
                put("uptime_seconds", if (serverStartTime == 0L) 0L else (System.currentTimeMillis() - serverStartTime) / 1000L)
            }
            val file = File(filesDir, "server_status.txt")
            file.writeText(status.toString())
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Write status error", e)
        }
    }

    private fun buildYouTubeThumbnailUrls(videoId: String): List<String> {
        return listOf(
            "https://img.youtube.com/vi/$videoId/maxresdefault.jpg",
            "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            "https://img.youtube.com/vi/$videoId/mqdefault.jpg",
            "https://img.youtube.com/vi/$videoId/default.jpg"
        )
    }

    private fun extractYouTubeVideoId(url: String?): String? {
        if (url == null) return null
        try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            if (host.contains("youtu.be")) {
                return uri.lastPathSegment
            }
            if (host.contains("youtube.com")) {
                uri.getQueryParameter("v")?.let { if (it.isNotEmpty()) return it }
                val segments = uri.pathSegments
                val embedIndex = segments.indexOf("embed")
                if (embedIndex >= 0 && segments.size > embedIndex + 1) return segments[embedIndex + 1]
            }
        } catch (_: Exception) { }
        return null
    }

    private fun startStatsUpdateTimer() {
        statsUpdateTimer?.cancel()
        statsUpdateTimer = Timer()
        statsUpdateTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                writeStatus("Service active", true)
            }
        }, 0, 10000)
    }

    private fun showServerInfo() {
        val uptime = if (serverStartTime == 0L) 0L else (System.currentTimeMillis() - serverStartTime) / 1000L
        val uptimeText = formatUptime(uptime)
        val infoText = buildString {
            append("📊 SERVER STATS\n\n")
            append("Status: ${if (isServerRunning) "RUNNING" else "STOPPED"}\n")
            append("Uptime: $uptimeText\n")
            append("Requests: $totalRequests\n")
            append("Cache hits: ${cacheManager.cacheHits}\n")
            append("Cache size: ${cacheManager.size()}\n")
        }
        updateNotification(infoText)
        serviceScope.launch {
            delay(8000)
            updateNotification("Service ${if (isServerRunning) "active" else "stopped"} - Cache: ${cacheManager.size()}")
        }
    }

    private fun formatUptime(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> {
                val minutes = seconds / 60
                val secs = seconds % 60
                "${minutes}m ${secs}s"
            }
            else -> {
                val hours = seconds / 3600
                val minutes = (seconds % 3600) / 60
                val secs = seconds % 60
                "${hours}h ${minutes}m ${secs}s"
            }
        }
    }

    // ---------------------- Notification / Foreground ----------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Server",
                NotificationManager.IMPORTANCE_MIN // ⬅️ CAMBIATO: Priorità minima (era DEFAULT)
            ).apply {
                description = "Servizio per estrazione URL YouTube"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET // ⬅️ CAMBIATO: Nasconde dalla lockscreen
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createNotification("Attivo")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotification(contentText: String, artwork: Bitmap? = null): Notification {
        // Intent per terminare il servizio
        val terminateIntent = Intent(this, HttpServerService::class.java).apply {
            putExtra("COMMAND", ACTION_TERMINATE)
        }
        val terminatePendingIntent = PendingIntent.getService(
            this, 3, terminateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent per aprire MainActivity (tap sulla notifica)
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Costruzione notifica MINIMALE
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Server") // ⬅️ Titolo breve
            .setContentText("Attivo") // ⬅️ Testo minimale (ignorato contentText passato)
            .setPriority(NotificationCompat.PRIORITY_MIN) // ⬅️ Priorità minima
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // ⬅️ Nascosta dalla lockscreen
            .setShowWhen(false) // ⬅️ Nasconde timestamp
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(mainPendingIntent)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // ⬅️ SOLO UN PULSANTE: Termina
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Termina",
                terminatePendingIntent
            )

        return builder.build()
    }


    private fun updateNotification(contentText: String) {
        val notification = createNotification("Attivo") // ⬅️ Testo fisso minimale
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)

        try {
            if (isServerRunning) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        } catch (_: Exception) { /* ignore if service is already stopped */ }
    }

    private fun createCircularBitmap(source: Bitmap, size: Int): Bitmap {
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        // Disegna cerchio
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        // Usa XOR per ritagliare
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)

        val srcRect = android.graphics.Rect(0, 0, source.width, source.height)
        val destRect = android.graphics.Rect(0, 0, size, size)
        canvas.drawBitmap(source, srcRect, destRect, paint)

        return output
    }

    // ---------------------- Broadcast Integration ----------------------
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val action = intent.action ?: return

            when (action) {
                "com.example.audioextractor.action.EXTRACT_ONLY" -> {
                    val videoId = intent.getStringExtra("videoId")
                    val type = intent.getStringExtra("type") ?: "audio"
                    val quality = intent.getIntExtra("quality", 720)
                    val replyTo = intent.getStringExtra("replyToPackage")
                    val requestId = intent.getStringExtra("requestId")
                    if (!videoId.isNullOrEmpty()) {
                        serviceScope.launch {
                            val extracted = extractStream(videoId, type, quality)
                            if (replyTo != null) {
                                val resp = Intent("com.example.audioextractor.action.EXTRACT_ONLY_REPLY")
                                resp.setPackage(replyTo)
                                requestId?.let { resp.putExtra("requestId", it) }
                                if (extracted != null) {
                                    resp.putExtra("status", "success")
                                    resp.putExtra("url", extracted.url)
                                    resp.putExtra("video_title", extracted.title)
                                    resp.putExtra("youtube_expires_in_seconds", extracted.expiresInSeconds)
                                } else {
                                    resp.putExtra("status", "error")
                                    resp.putExtra("error", "Errore estrazione stream")
                                }
                                sendBroadcast(resp)
                            }
                        }
                    } else {
                        sendErrorReply(replyTo, requestId, "MISSING_PARAM", "videoId missing")
                    }
                }

                "com.example.audioextractor.action.SERVER_COMMAND" -> {
                    val command = intent.getStringExtra("command")
                    val replyTo = intent.getStringExtra("replyToPackage")
                    val requestId = intent.getStringExtra("requestId")
                    when (command) {
                        ACTION_START -> {
                            if (!isServerRunning) {
                                startForegroundService()
                                startHttpServer()
                                writeStatus("Servizio avviato sulla porta $SERVER_PORT", true)
                            }
                            sendServerStatusUpdate(replyTo, requestId)
                        }
                        ACTION_STOP -> {
                            stopHttpServer()
                            updateNotification("Servizio arrestato - Premi 'Avvia' per riattivare")
                            writeStatus("Servizio arrestato", false)
                            sendServerStatusUpdate(replyTo, requestId)
                        }
                        ACTION_TOGGLE -> {
                            if (isServerRunning) {
                                stopHttpServer()
                                updateNotification("Servizio arrestato - Premi 'Avvia' per riattivare")
                            } else {
                                startHttpServer()
                                updateNotification("Servizio riavviato - Cache: ${cacheManager.size()} elementi")
                            }
                            sendServerStatusUpdate(replyTo, requestId)
                        }
                        ACTION_TERMINATE -> {
                            stopHttpServer()
                            writeStatus("Servizio terminato", false)
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        ACTION_SHOW_INFO -> {
                            showServerInfo()
                            sendServerStatusUpdate(replyTo, requestId)
                        }
                        else -> {
                            sendErrorReply(replyTo, requestId, "UNKNOWN_COMMAND", "Command not recognized")
                        }
                    }
                }

                "com.example.audioextractor.action.PLAYER_STATUS_REQUEST" -> {
                    val replyTo = intent.getStringExtra("replyToPackage")
                    val requestId = intent.getStringExtra("requestId")
                }
            }
        }
    }

    private fun sendServerStatusUpdate(replyToPackage: String?, requestId: String?) {
        val intent = Intent("com.example.audioextractor.action.SERVER_STATUS_UPDATE")
        intent.putExtra("server_running", isServerRunning)
        intent.putExtra("total_requests", totalRequests)
        intent.putExtra("cache_size", cacheManager.size())
        intent.putExtra("cache_hits", cacheManager.cacheHits)
        intent.putExtra("uptime_seconds", if (serverStartTime == 0L) 0L else (System.currentTimeMillis()-serverStartTime)/1000L)
        requestId?.let { intent.putExtra("requestId", it) }
        replyToPackage?.let { intent.setPackage(it) }
        sendBroadcast(intent)
    }

    private fun sendErrorReply(replyToPackage: String?, requestId: String?, code: String, message: String) {
        if (replyToPackage == null) return
        val intent = Intent("com.example.audioextractor.action.ERROR_REPLY")
        intent.setPackage(replyToPackage)
        requestId?.let { intent.putExtra("requestId", it) }
        intent.putExtra("errorCode", code)
        intent.putExtra("errorMessage", message)
        sendBroadcast(intent)
    }

    // ---------------------- YouTube utilities and notification update ----------------------
    private fun createBlurredBackgroundBitmap(originalBitmap: Bitmap): Bitmap {
        try {
            val width = 512
            val height = 512

            // Crea bitmap di output
            val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(outputBitmap)

            // Scala e sfoca l'immagine originale per lo sfondo
            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true)

            // Applica blur manuale (effetto semplificato)
            val blurredBitmap = applySimpleBlur(scaledBitmap, 25)

            // Disegna lo sfondo sfocato
            canvas.drawBitmap(blurredBitmap, 0f, 0f, null)

            // Applica gradiente da sinistra a destra (da opaco a trasparente)
            val gradientPaint = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    intArrayOf(
                        0xDD000000.toInt(),
                        0x88000000.toInt(),
                        0x00000000
                    ),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)

            // Disegna l'immagine originale in piccolo a sinistra
            val thumbSize = width / 3f
            val thumbLeft = 20f
            val thumbTop = (height - thumbSize) / 2f
            val thumbRect = android.graphics.RectF(thumbLeft, thumbTop, thumbLeft + thumbSize, thumbTop + thumbSize)

            val thumbPaint = Paint().apply {
                isAntiAlias = true
            }

            // Disegna ombra
            val shadowPaint = Paint().apply {
                isAntiAlias = true
                color = 0x88000000.toInt()
                maskFilter = android.graphics.BlurMaskFilter(15f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawRoundRect(
                thumbRect.left + 5f, thumbRect.top + 5f,
                thumbRect.right + 5f, thumbRect.bottom + 5f,
                12f, 12f, shadowPaint
            )

            // Disegna thumbnail con angoli arrotondati
            val path = android.graphics.Path().apply {
                addRoundRect(thumbRect, 12f, 12f, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(path)
            val srcRect = android.graphics.Rect(0, 0, originalBitmap.width, originalBitmap.height)
            canvas.drawBitmap(originalBitmap, srcRect, thumbRect, thumbPaint)

            blurredBitmap.recycle()
            if (scaledBitmap != originalBitmap) scaledBitmap.recycle()

            return outputBitmap
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Errore creazione background sfocato", e)
            return originalBitmap
        }
    }

    private fun applySimpleBlur(bitmap: Bitmap, radius: Int): Bitmap {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // RenderEffect disponibile da Android 12
                val blurred = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                return blurred
            }

            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val blurredPixels = IntArray(w * h)
            val kernelSize = radius.coerceIn(1, 25)

            for (y in 0 until h) {
                for (x in 0 until w) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var a = 0
                    var count = 0

                    for (ky in -kernelSize..kernelSize step 3) {
                        for (kx in -kernelSize..kernelSize step 3) {
                            val px = (x + kx).coerceIn(0, w - 1)
                            val py = (y + ky).coerceIn(0, h - 1)
                            val pixel = pixels[py * w + px]

                            a += (pixel shr 24) and 0xFF
                            r += (pixel shr 16) and 0xFF
                            g += (pixel shr 8) and 0xFF
                            b += pixel and 0xFF
                            count++
                        }
                    }

                    blurredPixels[y * w + x] = (
                            ((a / count) shl 24) or
                                    ((r / count) shl 16) or
                                    ((g / count) shl 8) or
                                    (b / count)
                            )
                }
            }

            val blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            blurred.setPixels(blurredPixels, 0, w, 0, 0, w, h)
            return blurred
        } catch (e: Exception) {
            android.util.Log.e("HttpServerService", "Errore blur", e)
            return bitmap
        }
    }

    // ---------------------- Helper functions for new endpoints ----------------------

    private fun getThumbnailUrl(item: InfoItem): String {
        return try {
            item.thumbnails?.firstOrNull()?.url ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    internal suspend fun performSearchInternal(query: String, filter: String, sortFilter: String, maxResults: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val service = NewPipe.getService(0)
                val searchExtractor = service.getSearchExtractor(query)
                searchExtractor.fetchPage()

                val allItems = searchExtractor.initialPage.items

                // Filtra manualmente i risultati
                val filteredItems = when (filter.lowercase()) {
                    "video" -> allItems.filterIsInstance<StreamInfoItem>()
                    "channel" -> allItems.filterIsInstance<ChannelInfoItem>()
                    "playlist" -> allItems.filterIsInstance<PlaylistInfoItem>()
                    else -> allItems
                }.take(maxResults)

                val results = org.json.JSONArray()
                filteredItems.forEach { item ->
                    val itemJson = JSONObject().apply {
                        put("type", when (item) {
                            is StreamInfoItem -> "video"
                            is ChannelInfoItem -> "channel"
                            is PlaylistInfoItem -> "playlist"
                            else -> "unknown"
                        })
                        put("name", item.name)
                        put("url", item.url)

                        when (item) {
                            is StreamInfoItem -> {
                                put("videoId", item.url.substringAfter("v=").substringBefore("&"))
                                put("uploader", item.uploaderName ?: "")
                                put("duration", item.duration)
                                put("view_count", item.viewCount)
                                put("thumbnail", getThumbnailUrl(item))
                                put("upload_date", try {
                                    val date = item.uploadDate
                                    if (date != null) {
                                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                        sdf.format(date)
                                    } else {
                                        ""
                                    }
                                } catch (e: Exception) {
                                    ""
                                })
                                put("short_description", item.shortDescription ?: "")
                            }
                            is ChannelInfoItem -> {
                                put("channelId", item.url.substringAfter("channel/").substringBefore("?"))
                                put("subscriber_count", item.subscriberCount)
                                put("description", item.description ?: "")
                                put("thumbnail", getThumbnailUrl(item))
                                put("stream_count", item.streamCount)
                            }
                            is PlaylistInfoItem -> {
                                put("playlistId", item.url.substringAfter("list=").substringBefore("&"))
                                put("uploader", item.uploaderName ?: "")
                                put("stream_count", item.streamCount)
                                put("thumbnail", getThumbnailUrl(item))
                            }
                        }
                    }
                    results.put(itemJson)
                }

                JSONObject().apply {
                    put("status", "success")
                    put("query", query)
                    put("filter", filter)
                    put("sort", sortFilter)
                    put("result_count", results.length())
                    put("results", results)
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Search error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore durante la ricerca")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }

    private suspend fun getTrending(category: String, maxResults: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val service = NewPipe.getService(0)
                val kioskList = service.kioskList

                val kioskId = when (category) {
                    "10" -> "trending_music"
                    "20" -> "trending_gaming"
                    "30" -> "trending_movies_and_shows"
                    "" -> "Trending"
                    else -> "Trending"
                }

                android.util.Log.d("HttpServerService", "getTrending: category=$category, kioskId=$kioskId")

                val trendingExtractor = kioskList.getExtractorById(kioskId, null)
                trendingExtractor.fetchPage()

                val items = trendingExtractor.initialPage.items.take(maxResults)
                val results = org.json.JSONArray()

                items.forEach { item ->
                    if (item is StreamInfoItem) {
                        try {
                            val itemJson = JSONObject().apply {
                                put("type", "video")
                                val videoId = item.url?.substringAfter("v=")?.substringBefore("&") ?: ""
                                put("videoId", videoId)

                                // ✅ USA "name" invece di "title"
                                put("name", item.name ?: "")

                                put("uploader", item.uploaderName ?: "")
                                put("uploaderName", item.uploaderName ?: "")
                                put("duration", item.duration ?: 0)

                                // ✅ GESTISCI view_count -1
                                val viewCount = item.viewCount ?: -1
                                if (viewCount >= 0) {
                                    put("view_count", viewCount)
                                } else {
                                    put("view_count", 0) // Usa 0 invece di -1
                                }

                                put("thumbnail", getThumbnailUrl(item))

                                // ✅ DATA ORIGINALE (senza modifiche)
                                val uploadDateString = try {
                                    val date = item.uploadDate
                                    if (date != null) {
                                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                        sdf.format(date)
                                    } else {
                                        ""
                                    }
                                } catch (e: Exception) {
                                    ""
                                }
                                put("upload_date", uploadDateString)
                                put("url", item.url ?: "")
                            }
                            results.put(itemJson)
                        } catch (e: Exception) {
                            android.util.Log.e("HttpServerService", "Error creating JSON for trending item: ${e.message}", e)
                        }
                    }
                }

                JSONObject().apply {
                    put("status", "success")
                    put("category", category)
                    put("kiosk_id", kioskId)
                    put("result_count", results.length())
                    put("results", results)
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Trending error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore recupero trending")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }



    private suspend fun getPlaylistInfo(playlistId: String, maxVideos: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val playlistUrl = if (playlistId.startsWith("http")) {
                    playlistId
                } else {
                    "https://www.youtube.com/playlist?list=$playlistId"
                }

                val playlistInfo = PlaylistInfo.getInfo(playlistUrl)

                val videos = org.json.JSONArray()
                var removedCount = 0 // Contatore per i video filtrati

                val items = playlistInfo.relatedItems // Questi sono StreamInfoItem
                if (items != null) {
                    items.forEach { item ->
                        if (item is StreamInfoItem) {
                            // FILTRO LATO SERVER: Usa la funzione isValidServerSideVideo (euristiche veloci)
                            if (isValidServerSideVideo(item)) { // <--- CHIAMATA AL FILTRO EURISTICO
                                // Aggiungi solo se non abbiamo superato il limite richiesto
                                if (videos.length() < maxVideos) {
                                    val videoJson = JSONObject().apply {
                                        put("videoId", item.url.substringAfter("v=").substringBefore("&"))
                                        put("title", item.name)
                                        put("uploader", item.uploaderName ?: "")
                                        put("duration", item.duration)
                                        put("duration_formatted", formatDuration(item.duration))
                                        put("view_count", item.viewCount)
                                        put("view_count_formatted", formatViewCount(item.viewCount))
                                        put("thumbnail", getThumbnailUrl(item))
                                        put("url", item.url)
                                        put("is_available", true) // Flag per Kodular
                                    }
                                    videos.put(videoJson)
                                } else {
                                    removedCount++ // Video valido ma oltre il limite maxVideos
                                }
                            } else {
                                // Logga i video non disponibili che vengono filtrati dal server
                                android.util.Log.d("PlaylistFilter",
                                    "Filtered unavailable video (server-side): ${item.name} (ID: ${item.url.substringAfter("v=").substringBefore("&")})")
                                removedCount++
                            }
                        }
                    }
                }

                // Log del riepilogo del filtro
                if (removedCount > 0) {
                    android.util.Log.d("HttpServerService", "GetPlaylistInfo: Filtered out $removedCount unavailable or over-limit videos.")
                }

                val playlistThumbnail = try {
                    playlistInfo.thumbnails?.firstOrNull()?.url ?: ""
                } catch (e: Exception) {
                    ""
                }

                JSONObject().apply {
                    put("status", "success")
                    put("playlist_id", playlistId)
                    put("name", playlistInfo.name)
                    put("uploader", playlistInfo.uploaderName ?: "")
                    put("thumbnail", playlistThumbnail)
                    put("stream_count", playlistInfo.streamCount)
                    put("description", playlistInfo.description ?: "")
                    put("video_count", videos.length()) // Conteggio dei video effettivamente restituiti dopo il filtro
                    put("videos", videos)
                    put("original_stream_count", playlistInfo.streamCount)
                    put("filtered_out_count", playlistInfo.streamCount - videos.length())

                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Playlist error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore recupero playlist")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }

    /**
     * Verifica la validità di un video lato server basandosi sui metadati disponibili di StreamInfoItem.
     * Questo filtro usa durata (-1), conteggio visualizzazioni (-1) e keyword nel titolo per identificare video non disponibili.
     */
    private fun isValidServerSideVideo(item: StreamInfoItem): Boolean {
        // ID del video
        val videoId = item.url.substringAfter("v=").substringBefore("&")

        // Titolo (usa name di StreamInfoItem)
        val title = item.name ?: ""

        // Durata (Long, può essere -1 per non disponibile)
        val duration = item.duration

        // Conteggio visualizzazioni (Long, può essere -1 per non disponibile)
        val viewCount = item.viewCount

        // Thumbnail URL (utile per debug o filtri futuri)
        val thumbnailUrl = getThumbnailUrl(item)

        // 1. Controlli basilari (videoId e titolo non vuoti, lunghezza videoId)
        if (videoId.isEmpty() || title.isEmpty() || videoId.length != 11) {
            android.util.Log.d("ServerVideoFilter", "Filtered (basic check failed) - ID: $videoId, Title: '$title'") // Debug
            return false
        }

        // 2. FILTRO CRUCIALE: Durata -1 (come indicato dal Javadoc) o <= 0
        if (duration <= 0) { // Cattura sia 0 che -1
            android.util.Log.d("ServerVideoFilter", "Filtered (duration <= 0 or -1) - ID: $videoId, Title: '$title', Duration: $duration") // Debug
            return false
        }

        // 3. FILTRO CRUCIALE: Conteggio visualizzazioni -1 (come indicato dal Javadoc) o <= 0
        if (viewCount <= 0) { // Cattura sia 0 che -1
            android.util.Log.d("ServerVideoFilter", "Filtered (viewCount <= 0 or -1) - ID: $videoId, Title: '$title', ViewCount: $viewCount") // Debug
            return false
        }

        // 4. Keyword nel titolo (per catturare messaggi espliciti di indisponibilità)
        val titleLower = title.toLowerCase(Locale.ROOT) // Usa Locale.ROOT per coerenza
        val unavailableKeywords = listOf(
            "[deleted video]", "[private video]", "video unavailable",
            "private video", "deleted video", "content blocked",
            "non disponibile", "bloccato", "removed by uploader",
            "removed by youtube", "account terminated", "blocked in your country",
            "video is restricted", "video non disponibile", "not available",
            "il video non è disponibile", "questo video non è disponibile",
            "youtube video player", "watch this video on youtube",
            "this video is no longer available",
            "this video has been removed", "this video contains content from"
        )

        if (unavailableKeywords.any { titleLower.contains(it) }) {
            android.util.Log.d("ServerVideoFilter", "Filtered (title keyword) - ID: $videoId, Title: '$title'") // Debug
            return false
        }

        // 5. Filtro per durate estremamente brevi (potenziali placeholder o errori di estrazione)
        // Se la durata è tra 1 e 5 secondi, potrebbe essere un problema
        // (Già coperto in parte da duration <= 0, ma utile per durate positive ma troppo brevi)
        if (duration > 0 && duration < 5) {
            android.util.Log.d("ServerVideoFilter", "Filtered (very short duration) - ID: $videoId, Title: '$title', Duration: $duration") // Debug
            return false
        }

        // 6. Controlli sulla thumbnail URL (opzionale, se noti pattern specifici)
        val thumbnailLower = thumbnailUrl.toLowerCase(Locale.ROOT)
        val unavailableThumbnailKeywords = listOf(
            "no_thumbnail", "default_live", "hqdefault_live",
            "unavailable.jpg", "error.jpg", "deleted_video_thumbnail",
            "404.jpg", "default_404.jpg"
        )
        if (unavailableThumbnailKeywords.any { thumbnailLower.contains(it) }) {
            android.util.Log.d("ServerVideoFilter", "Filtered (thumbnail keyword) - ID: $videoId, Thumbnail: '$thumbnailUrl'") // Debug
            return false
        }

        android.util.Log.d("ServerVideoFilter", "Video passed all checks - ID: $videoId, Title: '$title'") // Debug
        return true
    }


    /**
     * Converte i secondi in formato MM:SS o HH:MM:SS
     */
    private fun formatDuration(seconds: Long): String {
        if (seconds < 0) return "0:00"

        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, secs)
        }
    }

    /**
     * Formatta il numero di visualizzazioni in formato leggibile (es. 1.5M)
     */
    private fun formatViewCount(views: Long): String {
        if (views < 1000) {
            return views.toString()
        } else if (views < 1_000_000) {
            val k = views / 1000.0
            return String.format(Locale.US, "%.1fK", k)
        } else if (views < 1_000_000_000) {
            val m = views / 1_000_000.0
            return String.format(Locale.US, "%.1fM", m)
        } else {
            val b = views / 1_000_000_000.0
            return String.format(Locale.US, "%.1fB", b)
        }
    }
    private suspend fun getVideoMetadata(videoId: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val youtubeUrl = if (videoId.startsWith("http")) {
                    videoId
                } else {
                    "https://www.youtube.com/watch?v=$videoId"
                }

                val streamInfo = StreamInfo.getInfo(youtubeUrl)

                // Stimiamo il tempo di scadenza per i metadati.
                // Basato sulla durata del video, minimo 1 ora, massimo 6 ore.
                val estimatedExpiresInSeconds = (streamInfo.duration.toLong() * 2)
                    .coerceAtLeast(3600L) // Minimo 1 ora
                    .coerceAtMost(6 * 3600L) // Massimo 6 ore

                val audioStreams = org.json.JSONArray()
                val audioList = streamInfo.audioStreams
                if (audioList != null) {
                    audioList.forEach { audio ->
                        audioStreams.put(JSONObject().apply {
                            put("format", audio.format?.toString() ?: "")
                            put("bitrate", audio.bitrate)
                            put("url", audio.url)
                            put("format_id", audio.formatId)
                            put("expires_in_seconds", estimatedExpiresInSeconds) // Usa il valore stimato
                        })
                    }
                }

                val videoStreams = org.json.JSONArray()
                val videoList = streamInfo.videoStreams
                if (videoList != null) {
                    videoList.forEach { video ->
                        videoStreams.put(JSONObject().apply {
                            put("format", video.format?.toString() ?: "")
                            put("resolution", video.resolution ?: "")
                            put("bitrate", video.bitrate)
                            put("url", video.url)
                            put("format_id", video.formatId)
                            put("expires_in_seconds", estimatedExpiresInSeconds) // Usa il valore stimato
                        })
                    }
                }

                val relatedVideos = org.json.JSONArray()
                try {
                    val relatedList = streamInfo.relatedStreams
                    if (relatedList != null) {
                        relatedList.take(10).forEach { item ->
                            if (item is StreamInfoItem) {
                                relatedVideos.put(JSONObject().apply {
                                    put("videoId", item.url.substringAfter("v=").substringBefore("&"))
                                    put("title", item.name)
                                    put("uploader", item.uploaderName ?: "")
                                    put("duration", item.duration)
                                    put("thumbnail", getThumbnailUrl(item))
                                })
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("HttpServerService", "No related streams: ${e.message}")
                }

                val videoThumbnail = try {
                    streamInfo.thumbnails?.firstOrNull()?.url ?: ""
                } catch (e: Exception) {
                    ""
                }

                val tagsArray = org.json.JSONArray()
                val tagsList = streamInfo.tags
                if (tagsList != null) {
                    tagsList.forEach { tag ->
                        tagsArray.put(tag)
                    }
                }

                JSONObject().apply {
                    put("status", "success")
                    put("videoId", videoId)
                    put("title", streamInfo.name)
                    put("uploader", streamInfo.uploaderName ?: "")
                    put("uploader_url", streamInfo.uploaderUrl ?: "")
                    val uploadDateString = if (streamInfo.uploadDate != null) {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        sdf.format(streamInfo.uploadDate)
                    } else {
                        ""
                    }
                    put("upload_date", uploadDateString)
                    put("view_count", streamInfo.viewCount)
                    put("like_count", streamInfo.likeCount)
                    put("dislike_count", streamInfo.dislikeCount)
                    put("duration", streamInfo.duration)
                    put("description", streamInfo.description?.content ?: "")
                    put("thumbnail", videoThumbnail)
                    put("category", streamInfo.category ?: "")
                    put("age_limit", streamInfo.ageLimit)
                    put("tags", tagsArray)
                    put("audio_streams_count", audioStreams.length())
                    put("video_streams_count", videoStreams.length())
                    put("audio_streams", audioStreams)
                    put("video_streams", videoStreams)
                    put("related_videos", relatedVideos)
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Metadata error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore recupero metadata")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }


    private suspend fun getKiosk(kioskId: String, maxResults: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val service = NewPipe.getService(0)
                val kioskList = service.kioskList
                val kioskExtractor = kioskList.getExtractorById("Trending", null)
                kioskExtractor.fetchPage()

                val items = kioskExtractor.initialPage.items.take(maxResults)
                val results = org.json.JSONArray()

                items.forEach { item ->
                    if (item is StreamInfoItem) {
                        val itemJson = JSONObject().apply {
                            put("type", "video")
                            put("videoId", item.url.substringAfter("v=").substringBefore("&"))
                            put("title", item.name)
                            put("uploader", item.uploaderName ?: "")
                            put("duration", item.duration)
                            put("view_count", item.viewCount)
                            put("thumbnail", getThumbnailUrl(item))
                            put("upload_date", try {
                                val date = item.uploadDate
                                if (date != null) {
                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                    sdf.format(date)
                                } else {
                                    ""
                                }
                            } catch (e: Exception) {
                                ""
                            })
                            put("url", item.url)
                        }
                        results.put(itemJson)
                    }
                }

                JSONObject().apply {
                    put("status", "success")
                    put("kiosk_id", kioskId)
                    put("result_count", results.length())
                    put("results", results)
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Kiosk error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore recupero kiosk '$kioskId'")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }

    private suspend fun getAvailableKiosks(): String {
        return withContext(Dispatchers.IO) {
            try {
                val service = NewPipe.getService(0)
                val kioskList = service.kioskList
                val availableKiosks = kioskList.availableKiosks

                val kiosks = org.json.JSONArray()
                availableKiosks.forEach { kioskId ->
                    kiosks.put(JSONObject().apply {
                        put("id", kioskId)
                        put("url", "/kiosk?id=$kioskId&max=20")
                    })
                }

                JSONObject().apply {
                    put("status", "success")
                    put("kiosk_count", kiosks.length())
                    put("kiosks", kiosks)
                    put("note", "Usa /kiosk?id=KIOSK_ID per ottenere i contenuti")
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Kiosk list error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore recupero lista kiosk")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }

    private suspend fun getChannelInfo(channelId: String, maxVideos: Int): String { // maxVideos non sarà usato per i video qui
        return withContext(Dispatchers.IO) {
            try {
                val channelUrl = if (channelId.startsWith("http")) {
                    channelId
                } else if (channelId.startsWith("UC") || channelId.startsWith("HC")) {
                    "https://www.youtube.com/channel/$channelId"
                } else {
                    "https://www.youtube.com/$channelId"
                }

                val channelInfo = ChannelInfo.getInfo(channelUrl)

                val videos = org.json.JSONArray() // Lasciamo vuoto per questo endpoint

                val avatar = try {
                    channelInfo.avatars?.firstOrNull()?.url ?: ""
                } catch (e: Exception) {
                    ""
                }

                val banner = try {
                    channelInfo.banners?.firstOrNull()?.url ?: ""
                } catch (e: Exception) {
                    ""
                }

                JSONObject().apply {
                    put("status", "success")
                    put("channel_id", channelId)
                    put("name", channelInfo.name)
                    put("description", channelInfo.description ?: "")
                    put("avatar", avatar)
                    put("banner", banner)
                    put("subscriber_count", channelInfo.subscriberCount)
                    put("feed_url", channelInfo.feedUrl ?: "")
                    put("url", channelInfo.url)
                    put("video_count", 0) // Non recuperiamo video direttamente qui
                    put("videos", videos) // Array vuoto
                    put("note", "Per i video del canale, usa l'endpoint /channel/videos")
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Channel error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore recupero canale")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }

    private suspend fun searchChannelVideos(query: String, maxResults: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val service = NewPipe.getService(0)
                val searchExtractor = service.getSearchExtractor(query)
                searchExtractor.fetchPage()

                val allItems = searchExtractor.initialPage.items
                val videoItems = allItems.filterIsInstance<StreamInfoItem>().take(maxResults)

                val videos = org.json.JSONArray()
                videoItems.forEach { item ->
                    val videoJson = JSONObject().apply {
                        put("videoId", item.url.substringAfter("v=").substringBefore("&"))
                        put("title", item.name)
                        put("uploader", item.uploaderName ?: "")
                        put("duration", item.duration)
                        put("view_count", item.viewCount)
                        put("thumbnail", getThumbnailUrl(item))
                        put("upload_date", try {
                            val date = item.uploadDate
                            if (date != null) {
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                sdf.format(date)
                            } else {
                                ""
                            }
                        } catch (e: Exception) {
                            ""
                        })
                        put("url", item.url)
                    }
                    videos.put(videoJson)
                }

                JSONObject().apply {
                    put("status", "success")
                    put("query", query)
                    put("result_count", videos.length())
                    put("videos", videos)
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Channel videos search error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore ricerca video canale")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }

    // ---------------------- Helper function for YouTube Charts API (Uniformed Output with maxResults) ----------------------

    private suspend fun getDailyTopVideos(countryCode: String = "IT", maxResults: Int = 100): String {
        return withContext(Dispatchers.IO) {
            val requestBodyJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("capabilities", JSONObject())
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_MUSIC_ANALYTICS")
                        put("clientVersion", "2.0")
                        put("hl", "it")
                        put("gl", countryCode)
                        put("experimentIds", JSONArray())
                        put("experimentsToken", "")
                        put("theme", "MUSIC")
                    })
                    put("request", JSONObject().apply {
                        put("internalExperimentFlags", JSONArray())
                    })
                })
                put("browseId", "FEmusic_analytics_charts_home")
                put("query", "perspective=CHART_DETAILS&chart_params_country_code=${countryCode.lowercase()}&chart_params_chart_type=VIDEOS&chart_params_period_type=DAILY")
            }.toString()

            val body = requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("https://charts.youtube.com/youtubei/v1/browse?alt=json")
                .post(body)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/533.36")
                .build()

            try {
                val client = OkHttpClient()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Unexpected code ${response.code}")

                    val responseBody = response.body?.string() ?: throw IOException("Empty response body")
                    // android.util.Log.d("YoutubeChartsDebug", "Full JSON Response: $responseBody") // Puoi commentare questa riga di debug ora

                    val jsonResponse = JSONObject(responseBody)

                    val contentsObj = jsonResponse.optJSONObject("contents")
                    if (contentsObj == null) {
                        throw IOException("JSON path 'contents' not found or not an object. Full response: $responseBody")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "Contents: $contentsObj")

                    val sectionListRendererObj = contentsObj.optJSONObject("sectionListRenderer")
                    if (sectionListRendererObj == null) {
                        throw IOException("JSON path 'contents.sectionListRenderer' not found or not an object. Current obj: $contentsObj")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "SectionListRenderer: $sectionListRendererObj")

                    val contentsArray = sectionListRendererObj.optJSONArray("contents")
                    if (contentsArray == null || contentsArray.length() == 0) {
                        throw IOException("JSON path 'contents.sectionListRenderer.contents' not found or empty array. Current obj: $sectionListRendererObj")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "Contents Array: $contentsArray")

                    val firstContentObj = contentsArray.optJSONObject(0)
                    if (firstContentObj == null) {
                        throw IOException("JSON path 'contents.sectionListRenderer.contents[0]' not found or not an object. Current array: $contentsArray")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "First Content Obj: $firstContentObj")

                    val musicAnalyticsSectionRendererObj = firstContentObj.optJSONObject("musicAnalyticsSectionRenderer")
                    if (musicAnalyticsSectionRendererObj == null) {
                        throw IOException("JSON path '...musicAnalyticsSectionRenderer' not found or not an object. Current obj: $firstContentObj")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "MusicAnalyticsSectionRenderer: $musicAnalyticsSectionRendererObj")

                    val contentObj = musicAnalyticsSectionRendererObj.optJSONObject("content")
                    if (contentObj == null) {
                        throw IOException("JSON path '...content' not found or not an object. Current obj: $musicAnalyticsSectionRendererObj")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "Content Obj: $contentObj")

                    val perspectiveMetadataObj = contentObj.optJSONObject("perspectiveMetadata")
                    if (perspectiveMetadataObj == null) {
                        throw IOException("JSON path '...perspectiveMetadata' not found or not an object. Current obj: $contentObj")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "PerspectiveMetadata Obj: $perspectiveMetadataObj")

                    val videosRootArray = contentObj.optJSONArray("videos")
                    if (videosRootArray == null || videosRootArray.length() == 0) {
                        throw IOException("JSON path '...content.videos' not found or empty array. Current obj: $contentObj")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "Videos Root Array (direct child of contentObj): $videosRootArray")

                    val firstVideoItemContainer = videosRootArray.optJSONObject(0)
                    if (firstVideoItemContainer == null) {
                        throw IOException("JSON path '...videos[0]' not found or not an object. Current array: $videosRootArray")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "First Video Item (contains videoViews): $firstVideoItemContainer")

                    val videoViewsArray = firstVideoItemContainer.optJSONArray("videoViews")
                    if (videoViewsArray == null || videoViewsArray.length() == 0) {
                        throw IOException("JSON path '...videoViews' not found or empty array. Current obj: $firstVideoItemContainer")
                    }
                    // android.util.Log.d("YoutubeChartsDebug", "VideoViews Array (actual video list): $videoViewsArray")

                    val results = JSONArray()
                    for (i in 0 until videoViewsArray.length()) {
                        // Limita il numero di risultati se maxResults è specificato e non è 0
                        if (maxResults > 0 && results.length() >= maxResults) {
                            break
                        }

                        val videoJsonOriginal = videoViewsArray.getJSONObject(i)

                        val videoJsonOutput = JSONObject().apply {
                            // --- Campi per uniformare con /search ---
                            put("type", "video")
                            put("videoId", videoJsonOriginal.getString("id"))

                            // LOGICA AGGIORNATA PER IL NOME (Titolo - Autore)
                            val videoTitle = videoJsonOriginal.getString("title")
                            val artistsArrayFromOriginal = videoJsonOriginal.optJSONArray("artists")
                            val artistName = if (artistsArrayFromOriginal != null && artistsArrayFromOriginal.length() > 0) {
                                artistsArrayFromOriginal.getJSONObject(0).optString("name", "")
                            } else {
                                videoJsonOriginal.optString("channelName", "")
                            }
                            val formattedName = if (artistName.isNotEmpty() && artistName != videoTitle) {
                                "$artistName - $videoTitle"
                            } else {
                                videoTitle
                            }
                            put("name", formattedName) // Mappa il titolo formattato a "name"

                            put("uploader", videoJsonOriginal.optString("channelName", ""))
                            put("duration", videoJsonOriginal.optInt("videoDuration", 0))

                            val viewCountStr = videoJsonOriginal.optString("viewCount", "0")
                            val viewCountLong = try { viewCountStr.toLong() } catch (e: NumberFormatException) { 0L }
                            put("view_count", viewCountLong)

                            // LOGICA AGGIORNATA PER LA THUMBNAIL A MEDIA RISOLUZIONE
                            val thumbnailsArray = videoJsonOriginal.getJSONObject("thumbnail").getJSONArray("thumbnails")
                            val mediumResThumbnailUrl = if (thumbnailsArray.length() >= 3) { // Indice 2 per hqdefault.jpg o simile
                                thumbnailsArray.getJSONObject(2).getString("url")
                            } else if (thumbnailsArray.length() >= 1) {
                                thumbnailsArray.getJSONObject(0).getString("url")
                            } else {
                                ""
                            }
                            put("thumbnail", mediumResThumbnailUrl)

                            put("url", "https://www.youtube.com/watch?v=${videoJsonOriginal.getString("id")}")

                            // --- MANTIENI TUTTE LE ALTRE INFORMAZIONI ORIGINALI ---
                            videoJsonOriginal.keys().forEach { key ->
                                when (key) {
                                    "id" -> if (!this.has("videoId")) put("id", videoJsonOriginal.getString("id"))
                                    "title" -> if (!this.has("name")) put("title", videoJsonOriginal.getString("title"))
                                    "viewCount" -> if (!this.has("view_count")) put("viewCount", videoJsonOriginal.getString("viewCount"))
                                    "channelName" -> if (!this.has("uploader")) put("channelName", videoJsonOriginal.optString("channelName", ""))
                                    "videoDuration" -> if (!this.has("duration")) put("videoDuration", videoJsonOriginal.optInt("videoDuration", 0))
                                    "thumbnail" -> {
                                        if (!this.has("thumbnail_full_object")) put("thumbnail_full_object", videoJsonOriginal.getJSONObject("thumbnail"))
                                    }
                                    "artists" -> { /* Gestito separatamente per 'artists_list' e 'artists_original' */ }
                                    else -> put(key, videoJsonOriginal.get(key))
                                }
                            }
                            // Creiamo l'array di nomi degli artisti semplificato
                            val artistNames = JSONArray()
                            artistsArrayFromOriginal?.let {
                                for(j in 0 until it.length()) {
                                    artistNames.put(it.getJSONObject(j).getString("name"))
                                }
                            }
                            put("artists_list", artistNames)
                            if (artistsArrayFromOriginal != null) {
                                put("artists_original", artistsArrayFromOriginal)
                            } else {
                                put("artists_original", JSONObject.NULL)
                            }
                        }
                        results.put(videoJsonOutput)
                    }

                    val chartPeriodType = perspectiveMetadataObj
                        .optJSONObject("requestParams")
                        ?.optJSONObject("chartParams")
                        ?.optString("chartPeriodType", "Unknown") ?: "Unknown"

                    val endDate = perspectiveMetadataObj
                        .optJSONObject("requestParams")
                        ?.optJSONObject("chartParams")
                        ?.optString("latestEndDate", "Unknown") ?: "Unknown"

                    return@withContext JSONObject().apply {
                        put("status", "success")
                        put("query", "daily top videos")
                        put("filter", "video")
                        put("sort", "view_count")
                        put("result_count", results.length())
                        put("results", results)
                        put("source_api", "YouTubeChartsAPI")
                        put("charts_period_type", chartPeriodType)
                        put("charts_end_date", endDate)
                    }.toString()
                }
            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Error fetching daily top videos: ${e.message}", e)
                return@withContext JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore durante il recupero delle classifiche")
                    put("details", e.message ?: "Unknown error")
                }.toString()
            }
        }
    }



    internal suspend fun getRelatedVideosInternal(videoId: String, maxResults: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val youtubeUrl = if (videoId.startsWith("http")) {
                    videoId
                } else {
                    "https://www.youtube.com/watch?v=$videoId"
                }

                val streamInfo = StreamInfo.getInfo(youtubeUrl)

                val suggestions = org.json.JSONArray()

                try {
                    val relatedList = streamInfo.relatedStreams
                    if (relatedList != null) {
                        relatedList.take(maxResults).forEach { item ->
                            if (item is StreamInfoItem) {
                                val videoJson = JSONObject().apply {
                                    put("videoId", item.url.substringAfter("v=").substringBefore("&"))
                                    put("title", item.name)
                                    put("uploader", item.uploaderName ?: "")
                                    put("duration", item.duration)
                                    put("view_count", item.viewCount)
                                    put("thumbnail", getThumbnailUrl(item))
                                    put("upload_date", try {
                                        val date = item.uploadDate
                                        if (date != null) {
                                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                            sdf.format(date)
                                        } else {
                                            ""
                                        }
                                    } catch (e: Exception) {
                                        ""
                                    })
                                    put("url", item.url)
                                }
                                suggestions.put(videoJson)
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("HttpServerService", "No related streams: ${e.message}")
                }

                JSONObject().apply {
                    put("status", "success")
                    put("videoId", videoId)
                    put("video_title", streamInfo.name)
                    put("result_count", suggestions.length())
                    put("suggestions", suggestions)
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Suggestions error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore recupero video correlati")
                    put("details", e.message ?: "Unknown")
                }.toString()
            }
        }
    }

    /**
     * Estrae l'URL del Manifest DASH (MPD) o fornisce alternative per l'adaptive streaming di ExoPlayer.
     * Se DASH non è disponibile, restituisce gli stream disponibili per costruire un playlist manuale.
     */
    private suspend fun getManifestUrl(videoId: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val youtubeUrl = "https://www.youtube.com/watch?v=$videoId"

                // Usa StreamInfo.getInfo() per ottenere le informazioni dello stream
                val streamInfo: StreamInfo = StreamInfo.getInfo(youtubeUrl)

                // Estrazione dell'URL del Manifest DASH
                val manifestUrl: String? = streamInfo.dashMpdUrl

                // Se il manifest DASH è disponibile, restituiscilo
                if (!manifestUrl.isNullOrEmpty()) {
                    return@withContext JSONObject().apply {
                        put("status", "success")
                        put("videoId", videoId)
                        put("video_title", streamInfo.name)
                        put("manifest_url", manifestUrl)
                        put("manifest_type", "DASH_MPD")
                        put("format", "DASH MPD")
                        put("note", "Questo URL può essere passato direttamente a ExoPlayer per l'adaptive streaming.")
                    }.toString()
                }

                // FALLBACK: Se DASH non è disponibile, restituisci gli stream disponibili
                android.util.Log.d("HttpServerService", "DASH MPD non disponibile, fornisco stream alternativi")

                // Calcola expires_in_seconds basato sulla durata del video
                val estimatedExpiresInSeconds = (streamInfo.duration.toLong() * 2)
                    .coerceAtLeast(3600L) // Minimo 1 ora
                    .coerceAtMost(6 * 3600L) // Massimo 6 ore

                // Raccogli video streams con info su muxed
                val videoStreams = org.json.JSONArray()
                val videoOnlyStreams = org.json.JSONArray()
                val muxedStreams = org.json.JSONArray()

                streamInfo.videoStreams?.forEach { video ->
                    // itag 18 = 360p muxed, itag 22 = 720p muxed (contengono già audio)
                    val isMuxed = video.formatId == 18 || video.formatId == 22 ||
                            video.format?.toString()?.contains("MPEG_4") == true

                    val videoJson = JSONObject().apply {
                        put("format", video.format?.toString() ?: "")
                        put("resolution", video.resolution ?: "")
                        put("bitrate", video.bitrate)
                        put("url", video.url)
                        put("format_id", video.formatId)
                        put("fps", video.fps)
                        put("is_muxed", isMuxed)
                        put("contains_audio", isMuxed)
                        put("expires_in_seconds", estimatedExpiresInSeconds)
                    }

                    videoStreams.put(videoJson)

                    if (isMuxed) {
                        muxedStreams.put(videoJson)
                    } else {
                        videoOnlyStreams.put(videoJson)
                    }
                }

                // Raccogli audio streams
                val audioStreams = org.json.JSONArray()
                streamInfo.audioStreams?.forEach { audio ->
                    audioStreams.put(JSONObject().apply {
                        put("format", audio.format?.toString() ?: "")
                        put("bitrate", audio.bitrate)
                        put("url", audio.url)
                        put("format_id", audio.formatId)
                        put("average_bitrate", audio.averageBitrate)
                        put("is_audio_only", true)
                        put("expires_in_seconds", estimatedExpiresInSeconds)
                    })
                }

                // Trova il miglior video stream muxed (priorità) o video-only
                val bestMuxedStream = streamInfo.videoStreams
                    ?.filter { it.formatId == 18 || it.formatId == 22 }
                    ?.maxByOrNull { stream ->
                        stream.resolution?.replace("p", "")?.toIntOrNull() ?: 0
                    }

                val bestVideoStream = bestMuxedStream ?: streamInfo.videoStreams
                    ?.maxByOrNull { stream ->
                        stream.resolution?.replace("p", "")?.toIntOrNull() ?: 0
                    }

                // Trova il miglior audio stream (più alto bitrate)
                val bestAudioStream = streamInfo.audioStreams
                    ?.maxByOrNull { it.bitrate }

                // Restituisci una risposta con stream separati e consigli
                JSONObject().apply {
                    put("status", "success")
                    put("videoId", videoId)
                    put("video_title", streamInfo.name)
                    put("manifest_type", "SEPARATE_STREAMS")
                    put("dash_available", false)
                    put("expires_in_seconds", estimatedExpiresInSeconds)

                    // Stream consigliati
                    put("recommended", JSONObject().apply {
                        if (bestVideoStream != null) {
                            val isBestMuxed = bestVideoStream.formatId == 18 || bestVideoStream.formatId == 22
                            put("video_url", bestVideoStream.url)
                            put("video_resolution", bestVideoStream.resolution)
                            put("video_format", bestVideoStream.format?.toString() ?: "")
                            put("video_bitrate", bestVideoStream.bitrate)
                            put("is_muxed", isBestMuxed)
                            put("contains_audio", isBestMuxed)
                            put("playback_ready", isBestMuxed) // Se muxed, può essere riprodotto direttamente
                        }
                        if (bestAudioStream != null) {
                            put("audio_url", bestAudioStream.url)
                            put("audio_bitrate", bestAudioStream.bitrate)
                            put("audio_format", bestAudioStream.format?.toString() ?: "")
                        }
                    })

                    // Tutti gli stream disponibili
                    put("video_streams", videoStreams)
                    put("audio_streams", audioStreams)
                    put("muxed_streams", muxedStreams) // Stream video+audio già combinati
                    put("video_only_streams", videoOnlyStreams) // Stream solo video (richiedono audio separato)
                    put("video_streams_count", videoStreams.length())
                    put("audio_streams_count", audioStreams.length())
                    put("muxed_streams_count", muxedStreams.length())

                    // Note esplicative
                    val noteText = if (muxedStreams.length() > 0) {
                        "DASH non disponibile. Usa 'recommended.video_url' (muxed stream) per riproduzione immediata, oppure combina video_only + audio per qualità superiore."
                    } else {
                        "DASH non disponibile. Combina 'recommended.video_url' + 'recommended.audio_url' per la riproduzione."
                    }
                    put("note", noteText)
                    put("exoplayer_hint", "Stream muxed: usa MediaItem.fromUri() direttamente. Stream separati: usa MergingMediaSource.")
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("HttpServerService", "Manifest error for $videoId: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore nel recupero del manifest/stream")
                    put("details", e.message ?: "Unknown error")
                }.toString()
            }
        }
    }
    /**
     * Genera un manifest DASH personalizzato dagli stream estratti
     */
    private fun generateCustomDashManifest(
        videoId: String,
        allVideoStreams: List<org.schabi.newpipe.extractor.stream.VideoStream>,
        audioStreams: List<org.schabi.newpipe.extractor.stream.AudioStream>,
        duration: Long
    ): String {
        val durationStr = "PT${duration}S"

        android.util.Log.d("DASH_GEN", "📹 Generazione DASH per $videoId")
        android.util.Log.d("DASH_GEN", "📹 Video streams totali: ${allVideoStreams.size}")
        android.util.Log.d("DASH_GEN", "🔊 Audio streams: ${audioStreams.size}")

        // ✅ FILTRA solo video-only (escludi muxed per evitare problemi con DASH)
        val videoOnlyStreams = allVideoStreams.filter { stream ->
            val isMuxed = stream.formatId == 18 || stream.formatId == 22 ||
                    stream.format?.toString()?.contains("MPEG_4") == true
            !isMuxed // Tieni solo NON muxed
        }

        android.util.Log.d("DASH_GEN", "📹 Video-only streams dopo filtro: ${videoOnlyStreams.size}")

        // Se non ci sono abbastanza video-only, usa tutti (fallback)
        val streamsToUse = if (videoOnlyStreams.size >= 2) {
            videoOnlyStreams
        } else {
            android.util.Log.w("DASH_GEN", "⚠️ Pochi video-only (${videoOnlyStreams.size}), uso tutti gli stream")
            allVideoStreams
        }

        // =================================== GENERA ADAPTATION SET VIDEO ===================================
        val videoAdaptations = streamsToUse
            .sortedByDescending { it.resolution?.replace("p", "")?.toIntOrNull() ?: 0 }
            .joinToString("\n") { stream ->
                val resInt = stream.resolution?.replace("p", "")?.toIntOrNull() ?: 360
                val width = when (resInt) {
                    144 -> 256
                    240 -> 426
                    360 -> 640
                    480 -> 854
                    720 -> 1280
                    1080 -> 1920
                    1440 -> 2560
                    2160 -> 3840
                    else -> 640
                }
                val height = resInt

                // ✅ BANDWIDTH CORRETTO
                val bandwidth = if (stream.bitrate > 0) {
                    stream.bitrate * 1000  // kbps → bps
                } else {
                    when (resInt) {
                        144 -> 100000
                        240 -> 250000
                        360 -> 500000
                        480 -> 1000000
                        720 -> 2500000
                        1080 -> 4000000
                        1440 -> 8000000
                        2160 -> 16000000
                        else -> 500000
                    }
                }

                // Determina codec e mimeType
                val codec = stream.codec ?: when {
                    stream.format?.toString()?.contains("WEBM") == true -> "vp9"
                    else -> "avc1.4d401f"
                }

                val mimeType = when {
                    stream.format?.toString()?.contains("WEBM") == true -> "video/webm"
                    else -> "video/mp4"
                }

                android.util.Log.d("DASH_GEN", "  ├─ ${resInt}p @ ${bandwidth/1000} kbps ($codec)")

                """
          <Representation id="video_${resInt}p_${stream.formatId}" bandwidth="$bandwidth" width="$width" height="$height" codecs="$codec" mimeType="$mimeType">
            <BaseURL>${stream.url}</BaseURL>
            <SegmentBase indexRange="0-0" />
          </Representation>"""
            }

        // =================================== GENERA ADAPTATION SET AUDIO ===================================
        val audioAdaptations = audioStreams
            .sortedByDescending { it.bitrate }
            .take(3) // Limita a 3 stream audio (basso, medio, alto)
            .joinToString("\n") { stream ->
                val bandwidth = stream.bitrate * 1000
                val codec = when {
                    stream.format?.toString()?.contains("OPUS") == true -> "opus"
                    stream.format?.toString()?.contains("M4A") == true -> "mp4a.40.2"
                    else -> "mp4a.40.2"
                }
                val mimeType = when {
                    stream.format?.toString()?.contains("WEBM") == true -> "audio/webm"
                    else -> "audio/mp4"
                }

                android.util.Log.d("DASH_GEN", "  ├─ Audio @ ${stream.bitrate} kbps ($codec)")

                """
          <Representation id="audio_${stream.bitrate}kbps" bandwidth="$bandwidth" codecs="$codec" mimeType="$mimeType">
            <BaseURL>${stream.url}</BaseURL>
            <SegmentBase indexRange="0-0" />
          </Representation>"""
            }

        android.util.Log.d("DASH_GEN", "✅ DASH manifest generato con ${streamsToUse.size} video + ${audioStreams.take(3).size} audio")

        return """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns="urn:mpeg:dash:schema:mpd:2011" xsi:schemaLocation="urn:mpeg:dash:schema:mpd:2011 DASH-MPD.xsd" type="static" mediaPresentationDuration="$durationStr" minBufferTime="PT2S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
  <Period duration="$durationStr">
    <AdaptationSet mimeType="video/mp4" contentType="video" subsegmentAlignment="true" bitstreamSwitching="true">$videoAdaptations
    </AdaptationSet>
    <AdaptationSet mimeType="audio/mp4" contentType="audio" subsegmentAlignment="true">$audioAdaptations
    </AdaptationSet>
  </Period>
</MPD>"""
    }
    /**
     * Restituisce tutti i formati video disponibili per un video YouTube
     */
    private suspend fun getVideoFormats(videoId: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val youtubeUrl = if (videoId.startsWith("http")) {
                    videoId
                } else {
                    "https://www.youtube.com/watch?v=$videoId"
                }

                android.util.Log.d("VideoFormats", "📹 Extraction for: $videoId")
                val streamInfo = StreamInfo.getInfo(youtubeUrl)

                // Calcola scadenza stimata
                val estimatedExpiresInSeconds = (streamInfo.duration.toLong() * 2)
                    .coerceAtLeast(3600L)
                    .coerceAtMost(6 * 3600L)

                // =================================== VERIFICA DASH MPD ===================================
                val dashMpdUrl = streamInfo.dashMpdUrl
                android.util.Log.d("VideoFormats", "🎬 DASH MPD URL: ${dashMpdUrl ?: "NULL"}")

                // =================================== ESTRAI TUTTI I VIDEO STREAMS (INCLUSI VIDEO-ONLY) ===================================
                val videoFormats = org.json.JSONArray()
                val videoStreams = streamInfo.videoStreams
                val videoOnlyStreams = streamInfo.videoOnlyStreams // <-- IMPORTANTE!

                android.util.Log.d("VideoFormats", "📊 Video streams: ${videoStreams?.size ?: 0}")
                android.util.Log.d("VideoFormats", "📊 Video-only streams: ${videoOnlyStreams?.size ?: 0}")

                // Mappa per tracciare risoluzioni già aggiunte (evita duplicati)
                val addedResolutions = mutableSetOf<String>()

                // =================================== PROCESSA VIDEO STREAMS MUXED ===================================
                videoStreams?.forEach { video ->
                    try {
                        val resolution = video.resolution ?: "unknown"
                        val formatId = video.formatId
                        val isMuxed = formatId == 18 || formatId == 22 ||
                                video.format?.toString()?.contains("MPEG_4") == true

                        android.util.Log.d("VideoFormats", "  Video: $resolution (ID: $formatId) Muxed: $isMuxed")

                        val videoJson = JSONObject().apply {
                            put("format_id", formatId)
                            put("itag", formatId) // itag = formatId in NewPipe
                            put("format", video.format?.toString() ?: "")
                            put("resolution", resolution)
                            put("width", extractWidth(resolution))
                            put("height", extractHeight(resolution))
                            put("fps", video.fps ?: 30)
                            put("bitrate", video.bitrate)
                            put("codec", video.codec ?: "")
                            put("is_muxed", isMuxed)
                            put("contains_audio", isMuxed)
                            put("is_video_only", false) // Questi sono muxed
                            put("url", video.url ?: "")
                            put("expires_in_seconds", estimatedExpiresInSeconds)
                        }
                        videoFormats.put(videoJson)
                        addedResolutions.add(resolution)
                    } catch (e: Exception) {
                        android.util.Log.e("VideoFormats", "Error processing video stream: ${e.message}")
                    }
                }

                // =================================== PROCESSA VIDEO-ONLY STREAMS (QUI CI SONO LE ALTE RISOLUZIONI!) ===================================
                videoOnlyStreams?.forEach { video ->
                    try {
                        val resolution = video.resolution ?: "unknown"
                        val formatId = video.formatId

                        android.util.Log.d("VideoFormats", "  Video-Only: $resolution (ID: $formatId)")

                        val videoJson = JSONObject().apply {
                            put("format_id", formatId)
                            put("itag", formatId)
                            put("format", video.format?.toString() ?: "")
                            put("resolution", resolution)
                            put("width", extractWidth(resolution))
                            put("height", extractHeight(resolution))
                            put("fps", video.fps ?: 30)
                            put("bitrate", video.bitrate)
                            put("codec", video.codec ?: "")
                            put("is_muxed", false)
                            put("contains_audio", false)
                            put("is_video_only", true) // ⚠️ Richiede audio separato
                            put("url", video.url ?: "")
                            put("expires_in_seconds", estimatedExpiresInSeconds)
                            put("note", "Video-only stream - requires separate audio track")
                        }
                        videoFormats.put(videoJson)
                        addedResolutions.add(resolution)
                    } catch (e: Exception) {
                        android.util.Log.e("VideoFormats", "Error processing video-only stream: ${e.message}")
                    }
                }

                // =================================== PROCESSA AUDIO STREAMS ===================================
                val audioFormats = org.json.JSONArray()
                val audioStreams = streamInfo.audioStreams

                android.util.Log.d("VideoFormats", "🔊 Audio streams: ${audioStreams?.size ?: 0}")

                audioStreams?.forEach { audio ->
                    try {
                        val formatId = audio.formatId
                        audioFormats.put(JSONObject().apply {
                            put("format_id", formatId)
                            put("itag", formatId)
                            put("format", audio.format?.toString() ?: "")
                            put("bitrate", audio.bitrate)
                            put("average_bitrate", audio.averageBitrate)
                            put("codec", audio.codec ?: "")
                            put("url", audio.url ?: "")
                            put("expires_in_seconds", estimatedExpiresInSeconds)
                        })
                    } catch (e: Exception) {
                        android.util.Log.e("VideoFormats", "Error processing audio stream: ${e.message}")
                    }
                }

                // =================================== TROVA I MIGLIORI FORMATI ===================================
                val allVideoStreams = (videoStreams ?: emptyList()) + (videoOnlyStreams ?: emptyList())

                val bestMuxed = videoStreams
                    ?.filter { it.formatId == 18 || it.formatId == 22 }
                    ?.maxByOrNull { it.resolution?.replace("p", "")?.toIntOrNull() ?: 0 }

                val bestVideoOnly = videoOnlyStreams
                    ?.maxByOrNull { it.resolution?.replace("p", "")?.toIntOrNull() ?: 0 }

                val bestAudio = audioStreams?.maxByOrNull { it.bitrate }

                // =================================== CONTEGGIO RISOLUZIONI ===================================
                val resolutionsJson = JSONObject()
                addedResolutions.forEach { res ->
                    val count = allVideoStreams.count { it.resolution == res }
                    resolutionsJson.put(res, count)
                }

                // =================================== RISPOSTA FINALE ===================================
                JSONObject().apply {
                    put("status", "success")
                    put("videoId", videoId)
                    put("video_title", streamInfo.name)
                    put("duration", streamInfo.duration)

                    // Statistiche
                    put("total_video_formats", videoFormats.length())
                    put("total_audio_formats", audioFormats.length())
                    put("muxed_formats_count", videoStreams?.size ?: 0)
                    put("video_only_formats_count", videoOnlyStreams?.size ?: 0)
                    put("resolutions_available", resolutionsJson)

                    // DASH info
                    put("dash_available", !dashMpdUrl.isNullOrEmpty())
                    if (!dashMpdUrl.isNullOrEmpty()) {
                        put("dash_manifest_url", dashMpdUrl)
                        put("dash_note", "Use this URL directly with ExoPlayer for adaptive streaming")
                    }

                    // Formati consigliati
                    put("recommended", JSONObject().apply {
                        if (bestMuxed != null) {
                            put("best_muxed", JSONObject().apply {
                                put("format_id", bestMuxed.formatId)
                                put("resolution", bestMuxed.resolution)
                                put("fps", bestMuxed.fps)
                                put("bitrate", bestMuxed.bitrate)
                                put("url", bestMuxed.url)
                                put("note", "Ready to play - Video+Audio combined")
                            })
                        }

                        if (bestVideoOnly != null) {
                            put("best_video_only", JSONObject().apply {
                                put("format_id", bestVideoOnly.formatId)
                                put("resolution", bestVideoOnly.resolution)
                                put("fps", bestVideoOnly.fps)
                                put("bitrate", bestVideoOnly.bitrate)
                                put("url", bestVideoOnly.url)
                                put("note", "Highest quality - requires separate audio")
                            })
                        }

                        if (bestAudio != null) {
                            put("best_audio", JSONObject().apply {
                                put("format_id", bestAudio.formatId)
                                put("bitrate", bestAudio.bitrate)
                                put("codec", bestAudio.codec ?: "")
                                put("url", bestAudio.url)
                            })
                        }
                    })

                    // Liste complete
                    put("video_formats", videoFormats)
                    put("audio_formats", audioFormats)

                    put("expires_in_seconds", estimatedExpiresInSeconds)

                    // Note d'uso
                    put("usage_notes", JSONObject().apply {
                        put("muxed_streams", "Use directly - contain both video and audio")
                        put("video_only_streams", "Combine with audio stream for highest quality")
                        put("dash_streaming", "Use dash_manifest_url for adaptive bitrate streaming")
                    })
                }.toString()

            } catch (e: Exception) {
                android.util.Log.e("VideoFormats", "getVideoFormats error: ${e.message}", e)
                JSONObject().apply {
                    put("status", "error")
                    put("message", "Errore nel recupero dei formati video")
                    put("details", e.message ?: "Unknown error")
                }.toString()
            }
        }
    }

    /**
     * Estrae la larghezza dalla stringa risoluzione (es. "1920x1080" o "1080p")
     */
    private fun extractWidth(resolution: String?): Int {
        if (resolution == null) return 0

        // Formato "1920x1080"
        if (resolution.contains("x")) {
            return resolution.split("x")[0].toIntOrNull() ?: 0
        }

        // Formato "1080p"
        val height = resolution.replace("p", "").toIntOrNull() ?: return 0
        return when (height) {
            144 -> 256
            240 -> 426
            360 -> 640
            480 -> 854
            720 -> 1280
            1080 -> 1920
            1440 -> 2560
            2160 -> 3840
            4320 -> 7680
            else -> height * 16 / 9 // Aspect ratio 16:9
        }
    }

    /**
     * Estrae l'altezza dalla stringa risoluzione
     */
    private fun extractHeight(resolution: String?): Int {
        if (resolution == null) return 0

        // Formato "1920x1080"
        if (resolution.contains("x")) {
            return resolution.split("x")[1].toIntOrNull() ?: 0
        }

        // Formato "1080p"
        return resolution.replace("p", "").toIntOrNull() ?: 0
    }


    // Nuova funzione extractStream che restituisce ExtractedStreamInfo
    private suspend fun extractStream(videoId: String, extractType: String, quality: Int): ExtractedStreamInfo? {
        return withContext(Dispatchers.IO) {
            var lastException: Exception? = null
            repeat(3) { attempt ->
                try {
                    val timeoutMillis = 30000L + (attempt * 15000L)
                    val youtubeUrl = if (videoId.startsWith("http")) videoId else "https://www.youtube.com/watch?v=$videoId"

                    val result = withTimeout(timeoutMillis) {
                        if (!isNewPipeInitialized) {
                            NewPipe.init(OkHttpDownloader.createDefault())
                            isNewPipeInitialized = true
                        }

                        if (!youtubeUrl.contains("youtube.com/watch?v=") && !youtubeUrl.contains("youtu.be/")) {
                            throw IllegalArgumentException("URL YouTube non valido: $youtubeUrl")
                        }

                        val info = StreamInfo.getInfo(youtubeUrl)
                        val title = info.name ?: videoId
                        val estimatedExpiresInSeconds = (info.duration.toLong() * 2)
                            .coerceAtLeast(3600L)
                            .coerceAtMost(6 * 3600L)

                        when (extractType.lowercase()) {
                            "audio" -> {
                                val audioStream = info.audioStreams?.maxByOrNull { it.bitrate }
                                val url = audioStream?.url ?: ""
                                ExtractedStreamInfo(url, title, estimatedExpiresInSeconds, "audio", videoId)
                            }

                            "video" -> {
                                android.util.Log.d("EXTRACT_DEBUG", "╔═══════════════════════════════════════╗")
                                android.util.Log.d("EXTRACT_DEBUG", "║   EXTRACT STREAM DEBUG - VIDEO        ║")
                                android.util.Log.d("EXTRACT_DEBUG", "╚═══════════════════════════════════════╝")

                                // =================================== PRIORITÀ 0: DASH NATIVO YOUTUBE ===================================
                                val dashUrl = info.dashMpdUrl
                                android.util.Log.d("EXTRACT_DEBUG", "📡 DASH nativo: ${dashUrl ?: "NULL"}")

                                if (!dashUrl.isNullOrEmpty()) {
                                    android.util.Log.d("EXTRACT_DEBUG", "✅ RITORNO DASH NATIVO")
                                    return@withTimeout ExtractedStreamInfo(
                                        dashUrl,
                                        title,
                                        estimatedExpiresInSeconds,
                                        "video_dash_native",
                                        videoId
                                    )
                                }

                                // =================================== CONTA GLI STREAM DISPONIBILI ===================================
                                val allVideoStreams = info.videoStreams?.filterNotNull() ?: emptyList()
                                val allVideoOnlyStreams = info.videoOnlyStreams?.filterNotNull() ?: emptyList()
                                val allAudioStreams = info.audioStreams?.filterNotNull() ?: emptyList()

                                android.util.Log.d("EXTRACT_DEBUG", "📊 CONTEGGIO STREAM:")
                                android.util.Log.d("EXTRACT_DEBUG", "  ├─ videoStreams (muxed): ${allVideoStreams.size}")
                                android.util.Log.d("EXTRACT_DEBUG", "  ├─ videoOnlyStreams: ${allVideoOnlyStreams.size}")
                                android.util.Log.d("EXTRACT_DEBUG", "  └─ audioStreams: ${allAudioStreams.size}")

                                // =================================== DEBUG: STAMPA STREAM ===================================
                                android.util.Log.d("EXTRACT_DEBUG", "📹 VIDEO STREAMS (muxed):")
                                allVideoStreams.forEachIndexed { idx, stream ->
                                    android.util.Log.d("EXTRACT_DEBUG", "  [$idx] ${stream.resolution} (ID:${stream.formatId}) ${stream.bitrate}kbps")
                                }

                                android.util.Log.d("EXTRACT_DEBUG", "📹 VIDEO-ONLY STREAMS:")
                                allVideoOnlyStreams.forEachIndexed { idx, stream ->
                                    android.util.Log.d("EXTRACT_DEBUG", "  [$idx] ${stream.resolution} (ID:${stream.formatId}) ${stream.bitrate}kbps")
                                }

                                android.util.Log.d("EXTRACT_DEBUG", "🔊 AUDIO STREAMS:")
                                allAudioStreams.forEachIndexed { idx, stream ->
                                    android.util.Log.d("EXTRACT_DEBUG", "  [$idx] ${stream.bitrate}kbps (${stream.format})")
                                }

                                // =================================== PRIORITÀ 1: CERCA STREAM MUXED ===================================
                                android.util.Log.d("EXTRACT_DEBUG", "🔍 Cerco stream muxed (video+audio)...")

                                val foundMuxedStreams = allVideoStreams.filter { stream ->
                                    stream.formatId == 18 || stream.formatId == 22 ||
                                            stream.format?.toString()?.contains("MPEG_4") == true
                                }

                                android.util.Log.d("EXTRACT_DEBUG", "📹 Muxed trovati: ${foundMuxedStreams.size}")

                                val chosenMuxedStream = if (foundMuxedStreams.isNotEmpty()) {
                                    foundMuxedStreams.minByOrNull { stream ->
                                        val resInt = stream.resolution?.replace("p", "")?.toIntOrNull() ?: 0
                                        kotlin.math.abs(resInt - quality)
                                    }
                                } else null

                                if (chosenMuxedStream != null) {
                                    android.util.Log.d("EXTRACT_DEBUG", "✅ RITORNO MUXED: ${chosenMuxedStream.resolution}")
                                    android.util.Log.d("EXTRACT_DEBUG", "╚═══════════════════════════════════════╝")

                                    val url = chosenMuxedStream.url ?: ""
                                    return@withTimeout ExtractedStreamInfo(
                                        url,
                                        title,
                                        estimatedExpiresInSeconds,
                                        "video_muxed_fixed",
                                        videoId
                                    )
                                }

                                // =================================== PRIORITÀ 2: GENERA DASH CUSTOM (solo se NO muxed) ===================================
                                android.util.Log.d("EXTRACT_DEBUG", "⚠️ Nessun muxed, tento DASH custom...")

                                val combinedVideoStreams = allVideoStreams + allVideoOnlyStreams
                                val canGenerateDash = combinedVideoStreams.size >= 2 && allAudioStreams.isNotEmpty()

                                android.util.Log.d("EXTRACT_DEBUG", "🔧 Can generate DASH? $canGenerateDash")
                                android.util.Log.d("EXTRACT_DEBUG", "  ├─ combinedVideoStreams >= 2? ${combinedVideoStreams.size >= 2} (${combinedVideoStreams.size})")
                                android.util.Log.d("EXTRACT_DEBUG", "  └─ audioStreams not empty? ${allAudioStreams.isNotEmpty()} (${allAudioStreams.size})")

                                if (canGenerateDash) {
                                    android.util.Log.d("EXTRACT_DEBUG", "✅ GENERO DASH CUSTOM")

                                    val customDashManifest = generateCustomDashManifest(
                                        videoId,
                                        combinedVideoStreams,
                                        allAudioStreams,
                                        info.duration
                                    )

                                    val manifestUrl = "http://localhost:$SERVER_PORT/dash_manifest/$videoId.mpd"

                                    synchronized(dashManifestCache) {
                                        dashManifestCache[videoId] = CachedDashManifest(
                                            manifest = customDashManifest,
                                            timestamp = System.currentTimeMillis(),
                                            expiresAt = System.currentTimeMillis() + (estimatedExpiresInSeconds * 1000)
                                        )
                                    }

                                    android.util.Log.d("EXTRACT_DEBUG", "💾 DASH salvato: $manifestUrl")
                                    android.util.Log.d("EXTRACT_DEBUG", "╚═══════════════════════════════════════╝")

                                    return@withTimeout ExtractedStreamInfo(
                                        manifestUrl,
                                        title,
                                        estimatedExpiresInSeconds,
                                        "video_dash_custom",
                                        videoId
                                    )
                                }

                                // =================================== FALLBACK FINALE: VIDEO-ONLY ===================================
                                android.util.Log.d("EXTRACT_DEBUG", "⚠️ FALLBACK: video-only")

                                val finalVideoStream = combinedVideoStreams
                                    .filter { stream ->
                                        val resInt = stream.resolution?.replace("p", "")?.toIntOrNull() ?: 0
                                        resInt <= quality
                                    }
                                    .maxByOrNull { it.bitrate }

                                val url = finalVideoStream?.url ?: ""
                                android.util.Log.d("EXTRACT_DEBUG", "✅ RITORNO VIDEO-ONLY: ${finalVideoStream?.resolution}")
                                android.util.Log.d("EXTRACT_DEBUG", "╚═══════════════════════════════════════╝")

                                ExtractedStreamInfo(url, title, estimatedExpiresInSeconds, "video_only_fixed", videoId)
                            }

                            else -> {
                                throw IllegalArgumentException("Tipo di estrazione non valido: $extractType")
                            }
                        }
                    }
                    return@withContext result

                } catch (e: TimeoutCancellationException) {
                    lastException = e
                    if (attempt < 2) delay(2000)
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < 2) delay(1000)
                }
            }

            android.util.Log.e("ExtractStream", "❌ ERRORE FATALE: ${lastException?.message}", lastException)
            null
        }
    }
}
