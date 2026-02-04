package com.example.audioextractor

import android.content.Context
import android.content.Intent
import com.example.audioextractor.BuildConfig
import android.content.SharedPreferences
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import androidx.core.widget.NestedScrollView
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import android.os.Environment
import android.view.LayoutInflater
import android.view.ViewGroup

class MainActivity : AppCompatActivity() {

    // UI Components - Stats
    private lateinit var serverStatusText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var totalRequestsText: TextView
    private lateinit var cacheHitsText: TextView
    private lateinit var cacheElementsText: TextView
    private lateinit var refreshStatsButton: Button
    private lateinit var startStopServerButton: Button

    // UI Components - Cache History
    private lateinit var cacheHistoryRecyclerView: RecyclerView
    private lateinit var emptyCacheText: TextView
    private lateinit var cacheAdapter: CacheHistoryAdapter

    // UI Components - Settings
    private lateinit var serverAddressInput: EditText
    private lateinit var serverPortInput: EditText
    private lateinit var deviceIpText: TextView
    private lateinit var cacheExpirationInput: EditText
    private lateinit var networkTimeoutInput: EditText
    private lateinit var autoClearCacheSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var lowPowerModeSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var saveSettingsButton: Button

    // UI Components - Actions
    private lateinit var testVideoButton: Button
    private lateinit var clearCacheButton: Button
    private lateinit var updateAppButton: Button // Nuovo pulsante per l'aggiornamento

    // UI Components - Header (Nuovi)
    private lateinit var serverBuildInfoText: TextView
    private lateinit var librariesInfoButton: Button

    private var statsUpdateTimer: Timer? = null
    private lateinit var prefs: SharedPreferences

    // Coroutine Scope per operazioni in background
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Variabile per gestire il risultato della richiesta di installazione da sorgenti sconosciute
    private lateinit var requestInstallPermissionLauncher: ActivityResultLauncher<Intent>

    //AGGIORNAMENTO SERVER
    private val GITHUB_PAT = "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" // ⚠️ MASSIMA CAUTELA: NON pushare il codice sorgente con un PAT!
    private val REPO_OWNER = "IzioSettanta" // Il proprietario del repository
    private val REPO_NAME = "SealUTube" // Il nome del repository
    private val APK_FILE_NAME = "MusicServer.apk" // Aggiornato per riflettere il nome del file su GitHub// Il percorso esatto del file APK all'interno del repository
    private val GITHUB_LATEST_RELEASE_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var downloadProgressText: TextView

    private lateinit var databaseStatsText: TextView
    private lateinit var recentURLsRecyclerView: RecyclerView
    private lateinit var exportDatabaseButton: Button
    private lateinit var cleanDatabaseButton: Button
    private lateinit var recentURLsAdapter: RecentURLsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ServerSettings", Context.MODE_PRIVATE)

        initializeViews()
        setupCacheRecyclerView()
        loadSettings()
        updateDeviceIp()
        setServerBuildInfo()
        setupListeners()
        startStatsUpdateTimer()
        refreshServerStatsQuiet()

        // Inizializza il launcher per la richiesta di permesso
        requestInstallPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (packageManager.canRequestPackageInstalls()) {
                    // Permesso concesso, procedi con l'installazione
                    installApk(File(getExternalFilesDir(null), APK_FILE_NAME))
                } else {
                    Toast.makeText(this, "Permesso di installazione non concesso. Impossibile aggiornare.", Toast.LENGTH_LONG).show()
                    updateAppButton.isEnabled = true
                }
            }
        }
    }

    private fun initializeViews() {
        // Stats

        databaseStatsText = findViewById(R.id.databaseStatsText)
        recentURLsRecyclerView = findViewById(R.id.recentURLsRecyclerView)
        exportDatabaseButton = findViewById(R.id.exportDatabaseButton)
        cleanDatabaseButton = findViewById(R.id.cleanDatabaseButton)
        serverStatusText = findViewById(R.id.serverStatusText)
        uptimeText = findViewById(R.id.uptimeText)
        totalRequestsText = findViewById(R.id.totalRequestsText)
        cacheHitsText = findViewById(R.id.cacheHitsText)
        cacheElementsText = findViewById(R.id.cacheElementsText)
        refreshStatsButton = findViewById(R.id.refreshStatsButton)
        startStopServerButton = findViewById(R.id.startStopServerButton)

        // Cache History
        cacheHistoryRecyclerView = findViewById(R.id.cacheHistoryRecyclerView)
        emptyCacheText = findViewById(R.id.emptyCacheText)

        // Settings
        serverAddressInput = findViewById(R.id.serverAddressInput)
        serverPortInput = findViewById(R.id.serverPortInput)
        deviceIpText = findViewById(R.id.deviceIpText)
        cacheExpirationInput = findViewById(R.id.cacheExpirationInput)
        networkTimeoutInput = findViewById(R.id.networkTimeoutInput)
        autoClearCacheSwitch = findViewById(R.id.autoClearCacheSwitch)
        lowPowerModeSwitch = findViewById(R.id.lowPowerModeSwitch)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)

        // Actions
        testVideoButton = findViewById(R.id.testVideoButton)
        clearCacheButton = findViewById(R.id.clearCacheButton)
        updateAppButton = findViewById(R.id.updateAppButton) // Inizializza il nuovo pulsante

       // downloadProgressBar = findViewById(R.id.downloadProgressBar)
       // downloadProgressText = findViewById(R.id.downloadProgressText)

        // Header (Nuovi)
        serverBuildInfoText = findViewById(R.id.serverBuildInfoText)
        librariesInfoButton = findViewById(R.id.librariesInfoButton)

    }

    private fun setupCacheRecyclerView() {
        cacheAdapter = CacheHistoryAdapter(emptyList())
        cacheHistoryRecyclerView.layoutManager = LinearLayoutManager(this)
        cacheHistoryRecyclerView.adapter = cacheAdapter

        // ⭐ Setup RecyclerView per Recent URLs
        recentURLsAdapter = RecentURLsAdapter(emptyList())
        recentURLsRecyclerView.layoutManager = LinearLayoutManager(this)
        recentURLsRecyclerView.adapter = recentURLsAdapter
    }

    private fun loadSettings() {
        serverAddressInput.setText(prefs.getString("server_address", "localhost"))
        serverPortInput.setText(prefs.getInt("server_port", 8080).toString())
        cacheExpirationInput.setText(prefs.getInt("cache_expiration", 30).toString())
        networkTimeoutInput.setText(prefs.getInt("network_timeout", 30).toString())
        autoClearCacheSwitch.isChecked = prefs.getBoolean("auto_clear_cache", true)
        lowPowerModeSwitch.isChecked = prefs.getBoolean("low_power_mode", false)
    }

    private fun updateDeviceIp() {
        try {
            var ipAddress: String? = null

            // 1️⃣ Tentativo via Wi-Fi
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                if (wifiInfo != null && wifiInfo.ipAddress != 0) {
                    val wifiIp = Formatter.formatIpAddress(wifiInfo.ipAddress)
                    if (wifiIp != "0.0.0.0") ipAddress = wifiIp
                    android.util.Log.d("DeviceIP", "WiFi IP: $wifiIp")
                }
            } catch (ignored: Exception) {}

            // 2️⃣ Tentativo via interfacce di rete (tutti i tipi)
            if (ipAddress == null || ipAddress == "0.0.0.0") {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                for (intf in interfaces) {
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val currentIp = addr.hostAddress
                            if (!currentIp.startsWith("127.") && currentIp != "0.0.0.0") {
                                ipAddress = currentIp
                                android.util.Log.d("DeviceIP", "Interface ${intf.displayName} → $currentIp")
                                break
                            }
                        }
                    }
                    if (ipAddress != null) break
                }
            }

            // 3️⃣ Ultimo fallback: IP locale per connessione di loopback (se proprio nulla)
            if (ipAddress == null) {
                val localhost = java.net.InetAddress.getLocalHost()
                if (localhost != null && localhost.hostAddress != "127.0.0.1") {
                    ipAddress = localhost.hostAddress
                }
            }

            // 4️⃣ Mostra o fallback finale
            deviceIpText.text = ipAddress ?: "N/A"
            android.util.Log.d("DeviceIP", "IP finale: ${deviceIpText.text}")

        } catch (e: Exception) {
            deviceIpText.text = "N/A"
            android.util.Log.e("DeviceIP", "Errore durante rilevamento IP", e)
        }
    }

    private fun setServerBuildInfo() {
        // ✅ Utilizza BuildConfig.VERSION_NAME per la versione dell'app
        val appVersion = BuildConfig.VERSION_NAME
        // La data di build è già gestita da BuildConfig.TIMESTAMP se configurata in Gradle
        // Se non hai BuildConfig.TIMESTAMP configurato, potresti usare Date(System.currentTimeMillis())
        val buildDate = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(BuildConfig.TIMESTAMP))

        serverBuildInfoText.text = "Server: $appVersion ($buildDate)"
    }
    private fun setupListeners() {
        refreshStatsButton.setOnClickListener {
            refreshServerStats()
        }

        startStopServerButton.setOnClickListener {
            toggleServer()
        }

        saveSettingsButton.setOnClickListener {
            saveSettings()
        }

        testVideoButton.setOnClickListener {
            startActivity(Intent(this, TestVideoActivity::class.java))
        }

        clearCacheButton.setOnClickListener {
            clearCache()
        }

        librariesInfoButton.setOnClickListener {
            showLibrariesInfoPopup()
        }

        updateAppButton.setOnClickListener {
            checkForUpdate() // Listener per il nuovo pulsante
        }
        exportDatabaseButton.setOnClickListener {
            exportDatabase()
        }

        cleanDatabaseButton.setOnClickListener {
            cleanExpiredURLs()
        }
    }

    private fun refreshDatabaseStats() {
        Thread {
            try {
                val serverAddress = prefs.getString("server_address", "localhost") ?: "localhost"
                val serverPort = prefs.getInt("server_port", 8080)
                val url = URL("http://$serverAddress:$serverPort/database/stats")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonObject = JSONObject(response)

                    runOnUiThread {
                        updateDatabaseStatsUI(jsonObject)
                        updateRecentURLsList(jsonObject)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error fetching database stats: ${e.message}")
            }
        }.start()
    }

    private fun updateDatabaseStatsUI(data: JSONObject) {
        try {
            val stats = data.getJSONObject("statistics")
            val totalURLs = stats.getInt("total_urls")
            val validURLs = stats.getInt("valid_urls")
            val expiredURLs = stats.getInt("expired_urls")
            val totalExtractions = stats.getInt("total_extractions")
            val dbSizeKB = stats.getLong("database_size_bytes") / 1024

            val statsText = """
                📊 DATABASE STATISTICS
                ━━━━━━━━━━━━━━━━━━━━
                Total URLs: $totalURLs
                Valid: $validURLs | Expired: $expiredURLs
                Total Extractions: $totalExtractions
                Size: ${dbSizeKB} KB
            """.trimIndent()

            databaseStatsText.text = statsText
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error updating database stats UI", e)
        }
    }

    private fun updateRecentURLsList(data: JSONObject) {
        try {
            val recentURLsArray = data.getJSONArray("recent_urls")
            val recentURLs = mutableListOf<RecentURLEntry>()

            for (i in 0 until minOf(recentURLsArray.length(), 5)) {
                val urlData = recentURLsArray.getJSONObject(i)
                recentURLs.add(
                    RecentURLEntry(
                        videoId = urlData.getString("video_id"),
                        videoTitle = urlData.getString("video_title"),
                        type = urlData.getString("type"),
                        extractionCount = urlData.getInt("extraction_count"),
                        expiresInSeconds = urlData.getLong("expires_in_seconds"),
                        lastAccessedAt = urlData.getLong("last_accessed_at"),
                        isValid = urlData.getBoolean("is_valid")
                    )
                )
            }

            runOnUiThread {
                if (recentURLs.isNotEmpty()) {
                    recentURLsAdapter.updateData(recentURLs)
                    recentURLsRecyclerView.visibility = RecyclerView.VISIBLE
                } else {
                    recentURLsRecyclerView.visibility = RecyclerView.GONE
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error updating recent URLs list", e)
        }
    }

    private fun exportDatabase() {
        exportDatabaseButton.isEnabled = false
        exportDatabaseButton.text = "📤 Esportazione..."

        Thread {
            try {
                val serverAddress = prefs.getString("server_address", "localhost") ?: "localhost"
                val serverPort = prefs.getInt("server_port", 8080)
                val url = URL("http://$serverAddress:$serverPort/database/export")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val exportContent = connection.inputStream.bufferedReader().readText()

                    // Salva il file nella directory Downloads
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val fileName = "tinydb_export_$timestamp.json"

                    // ✅ CORREZIONE: Specifica esplicitamente File(String, String)
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val outputFile = File(downloadsDir.absolutePath, fileName)  // <-- CORRETTO

                    outputFile.writeText(exportContent)

                    runOnUiThread {
                        exportDatabaseButton.isEnabled = true
                        exportDatabaseButton.text = "📤 Esporta Database"

                        // Mostra dialog con percorso file
                        android.app.AlertDialog.Builder(this)
                            .setTitle("✅ Export Completato")
                            .setMessage("Database esportato in:\n${outputFile.absolutePath}\n\nFormato: TinyDB compatible (Kodular)")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .setNeutralButton("Condividi") { _, _ ->
                                shareFile(outputFile)
                            }
                            .show()
                    }
                } else {
                    throw IOException("Export failed: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    exportDatabaseButton.isEnabled = true
                    exportDatabaseButton.text = "📤 Esporta Database"
                    Toast.makeText(this, "❌ Errore export: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "TinyDB Export - Music Server")
                putExtra(Intent.EXTRA_TEXT, "Database export from Music Server\nCompatible with Kodular TinyDB")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            startActivity(Intent.createChooser(shareIntent, "Condividi Database"))
        } catch (e: Exception) {
            Toast.makeText(this, "Errore condivisione: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanExpiredURLs() {
        cleanDatabaseButton.isEnabled = false
        cleanDatabaseButton.text = "🧹 Pulizia..."

        Thread {
            try {
                val serverAddress = prefs.getString("server_address", "localhost") ?: "localhost"
                val serverPort = prefs.getInt("server_port", 8080)
                val url = URL("http://$serverAddress:$serverPort/database/clean")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonObject = JSONObject(response)
                    val removedCount = jsonObject.getInt("removed_count")

                    runOnUiThread {
                        cleanDatabaseButton.isEnabled = true
                        cleanDatabaseButton.text = "🧹 Pulisci Scaduti"
                        Toast.makeText(this, "🧹 Rimossi $removedCount URL scaduti", Toast.LENGTH_SHORT).show()
                        refreshDatabaseStats()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    cleanDatabaseButton.isEnabled = true
                    cleanDatabaseButton.text = "🧹 Pulisci Scaduti"
                    Toast.makeText(this, "❌ Errore pulizia: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveSettings() {
        try {
            val address = serverAddressInput.text.toString()
            val port = serverPortInput.text.toString().toIntOrNull() ?: 8080
            val cacheExp = cacheExpirationInput.text.toString().toIntOrNull() ?: 30
            val netTimeout = networkTimeoutInput.text.toString().toIntOrNull() ?: 30

            prefs.edit().apply {
                putString("server_address", address)
                putInt("server_port", port)
                putInt("cache_expiration", cacheExp)
                putInt("network_timeout", netTimeout)
                putBoolean("auto_clear_cache", autoClearCacheSwitch.isChecked)
                putBoolean("low_power_mode", lowPowerModeSwitch.isChecked)
                apply()
            }

            Toast.makeText(this, "✅ Impostazioni salvate", Toast.LENGTH_SHORT).show()
            // Riavvia il timer delle statistiche se la modalità risparmio energetico è cambiata
            startStatsUpdateTimer()
        } catch (e: Exception) {
            Toast.makeText(this, "❌ Errore salvataggio: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearCache() {
        Thread {
            try {
                val serverAddress = prefs.getString("server_address", "localhost") ?: "localhost"
                val serverPort = prefs.getInt("server_port", 8080)
                val url = URL("http://$serverAddress:$serverPort/cache/clear")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonObject = JSONObject(response)
                    val itemsRemoved = jsonObject.optInt("items_removed", 0)

                    runOnUiThread {
                        Toast.makeText(this, "🗑️ Cache pulita: $itemsRemoved elementi rimossi", Toast.LENGTH_SHORT).show()
                        refreshServerStatsQuiet()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "❌ Errore pulizia cache", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "❌ Server non raggiungibile", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun startStatsUpdateTimer() {
        statsUpdateTimer?.cancel()
        statsUpdateTimer = Timer()
        val interval = if (lowPowerModeSwitch.isChecked) 15000L else 10000L
        statsUpdateTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    refreshServerStatsQuiet()
                    refreshDatabaseStats() // ⭐ AGGIUNGI questa riga
                }
            }
        }, 0, interval)
    }
    private fun refreshServerStatsQuiet() {
        Thread {
            try {
                val serverAddress = prefs.getString("server_address", "localhost") ?: "localhost"
                val serverPort = prefs.getInt("server_port", 8080)
                val url = URL("http://$serverAddress:$serverPort/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonObject = JSONObject(response)

                    runOnUiThread {
                        updateStatsUI(jsonObject, true)
                        updateCacheHistory(jsonObject)
                    }
                } else {
                    runOnUiThread {
                        updateStatsUI(null, false)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatsUI(null, false)
                }
            }
        }.start()
    }

    private fun refreshServerStats() {
        refreshStatsButton.isEnabled = false
        refreshStatsButton.text = "🔄 ..."

        Thread {
            try {
                val serverAddress = prefs.getString("server_address", "localhost") ?: "localhost"
                val serverPort = prefs.getInt("server_port", 8080)
                val url = URL("http://$serverAddress:$serverPort/status")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode

                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonObject = JSONObject(response)

                    runOnUiThread {
                        updateStatsUI(jsonObject, true)
                        updateCacheHistory(jsonObject)
                        refreshStatsButton.isEnabled = true
                        refreshStatsButton.text = "🔄 Aggiorna"
                        Toast.makeText(this@MainActivity, "✅ Statistiche aggiornate", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        updateStatsUI(null, false)
                        refreshStatsButton.isEnabled = true
                        refreshStatsButton.text = "🔄 Aggiorna"
                        Toast.makeText(this@MainActivity, "⚠️ Server offline", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateStatsUI(null, false)
                    refreshStatsButton.isEnabled = true
                    refreshStatsButton.text = "🔄 Aggiorna"
                    Toast.makeText(this@MainActivity, "❌ Server non raggiungibile", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun updateStatsUI(stats: JSONObject?, isOnline: Boolean) {
        if (isOnline && stats != null) {
            serverStatusText.text = "ONLINE"
            serverStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

            val uptimeSeconds = stats.optLong("uptime_seconds", 0)
            uptimeText.text = formatUptime(uptimeSeconds)

            totalRequestsText.text = stats.optInt("total_requests", 0).toString()
            cacheHitsText.text = stats.optInt("cache_hits", 0).toString()
            cacheElementsText.text = stats.optInt("cache_size", 0).toString()

            startStopServerButton.text = "⏸️ Ferma Server"
        } else {
            serverStatusText.text = "OFFLINE"
            serverStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))

            uptimeText.text = "0s"
            totalRequestsText.text = "0"
            cacheHitsText.text = "0"
            cacheElementsText.text = "0"

            startStopServerButton.text = "▶️ Avvia Server"
        }
    }




    private fun updateCacheHistory(stats: JSONObject) {
        Thread {
            try {
                val serverAddress = prefs.getString("server_address", "localhost") ?: "localhost"
                val serverPort = prefs.getInt("server_port", 8080)
                val url = URL("http://$serverAddress:$serverPort/cache/list")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val jsonObject = JSONObject(response)
                    val cacheItems = jsonObject.optJSONArray("cache_items")

                    val entries = mutableListOf<CacheEntry>()
                    if (cacheItems != null) {
                        for (i in 0 until cacheItems.length()) {
                            val item = cacheItems.getJSONObject(i)
                            val videoId = item.optString("video_id", "unknown")
                            val videoTitle = item.optString("video_title", videoId)
                            val timestamp = item.optLong("timestamp", 0)
                            val youtubeExpiresAtMillis = item.optLong("youtube_expires_at_millis", 0)
                            val accessCount = item.optInt("access_count", 0)

                            entries.add(
                                CacheEntry(
                                    videoId = videoId,
                                    videoName = videoTitle,
                                    timestamp = timestamp,
                                    youtubeExpiresAtMillis = youtubeExpiresAtMillis,
                                    accessCount = accessCount
                                )
                            )
                        }
                    }

                    runOnUiThread {
                        if (entries.isNotEmpty()) {
                            val sortedEntries = entries.sortedByDescending { it.timestamp }
                            val limitedEntries = sortedEntries.take(10) // ✅ Mostra solo le ultime 10
                            cacheAdapter.updateData(limitedEntries)

                            // Mostra pulsante "Mostra tutte"
                            findViewById<Button>(R.id.showAllCacheButton)?.apply {
                                visibility = View.VISIBLE
                                setOnClickListener {
                                    cacheAdapter.updateData(sortedEntries)
                                    visibility = View.GONE // Nasconde il pulsante
                                }
                            }

                            cacheHistoryRecyclerView.visibility = RecyclerView.VISIBLE
                            emptyCacheText.visibility = TextView.GONE
                        } else {
                            cacheHistoryRecyclerView.visibility = RecyclerView.GONE
                            emptyCacheText.visibility = TextView.GONE // Nascondi anche il testo vuoto se non ci sono elementi
                            findViewById<Button>(R.id.showAllCacheButton)?.visibility = View.GONE
                        }
                    }
                } else {
                    runOnUiThread {
                        cacheHistoryRecyclerView.visibility = RecyclerView.GONE
                        emptyCacheText.visibility = TextView.GONE // Nascondi anche il testo vuoto
                        findViewById<Button>(R.id.showAllCacheButton)?.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error fetching cache list: ${e.message}")
                runOnUiThread {
                    cacheHistoryRecyclerView.visibility = RecyclerView.GONE
                    emptyCacheText.visibility = TextView.GONE // Nascondi anche il testo vuoto
                    findViewById<Button>(R.id.showAllCacheButton)?.visibility = View.GONE
                }
            }
        }.start()
    }


    private fun generateMockCacheEntries(count: Int): List<CacheEntry> {
        // Dati mock - sostituire con dati reali dall'API
        // Questa funzione è solo per dati di esempio, non usa la cache del server reale.
        // Il tempo di scadenza di YouTube è stimato per i mock.
        val currentTime = System.currentTimeMillis()
        val estimatedYoutubeExpiresInMinutes = prefs.getInt("cache_expiration", 30)

        return (1..minOf(count, 10)).map { i ->
            val videoId = "mock_video_${i}"
            val videoName = "Video in cache #$i (Mock)"
            val mockTimestamp = currentTime - (i * 30 * 1000L) // Messo in cache a tempi diversi
            val mockYoutubeExpiresAtMillis = mockTimestamp + (estimatedYoutubeExpiresInMinutes * 60 * 1000L) - (i * 10 * 1000L) // Scade in momenti diversi

            CacheEntry(
                videoId = videoId,
                videoName = videoName,
                timestamp = mockTimestamp,
                youtubeExpiresAtMillis = mockYoutubeExpiresAtMillis // Corretto!
            )
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

    private fun toggleServer() {
        try {
            startStopServerButton.isEnabled = false
            startStopServerButton.text = "⏳ ..."

            val command = if (serverStatusText.text == "ONLINE") "STOP" else "START"

            // SOLUZIONE 1: Usa Intent diretto al Service (più pulito, nessun lampeggio)
            val serviceIntent = Intent(this, HttpServerService::class.java).apply {
                putExtra("COMMAND", command)
            }

            if (command == "START") {
                // Avvia il service in foreground
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } else {
                // Per STOP, invia semplicemente l'intent
                startService(serviceIntent)
            }

            // Attendi e aggiorna UI
            Handler(Looper.getMainLooper()).postDelayed({
                startStopServerButton.isEnabled = true
                refreshServerStatsQuiet()

                val statusMessage = when (command) {
                    "START" -> "✅ Server avviato"
                    "STOP" -> "⏸️ Server arrestato"
                    else -> "Comando $command inviato"
                }
                Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
            }, 1500) // Ridotto a 1.5s per feedback più rapido

        } catch (e: Exception) {
            startStopServerButton.isEnabled = true
            startStopServerButton.text = if (serverStatusText.text == "ONLINE") "⏸️ Ferma Server" else "▶️ Avvia Server"
            Toast.makeText(this, "❌ Errore: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    private fun showLibrariesInfoPopup() {
        val librariesInfo = """
            Librerie Utilizzate:

            - NewPipeExtractor: ${BuildConfig.NEWPIPE_EXTRACTOR_VERSION}
            - androidx.media3.exoplayer: ${BuildConfig.MEDIA3_EXOPLAYER_VERSION}
            - androidx.media3.session: ${BuildConfig.MEDIA3_EXOPLAYER_VERSION}
            - androidx.media3.ui: ${BuildConfig.MEDIA3_EXOPLAYER_VERSION}

            Ambiente di Sviluppo:
            - Kotlin: 1.9.23
            - Target SDK: 35
            - Min SDK: 26
            - JVM Target: 17

            developed by Regis - October 2025

        """.trimIndent()

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Informazioni Librerie")
        builder.setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }

        val scrollView = NestedScrollView(this)
        val textView = TextView(this).apply {
            text = librariesInfo
            setPadding(40, 20, 40, 20) // Padding interno
            setTextIsSelectable(true) // Rende il testo copiabile
        }
        scrollView.addView(textView)
        builder.setView(scrollView)

        val dialog = builder.create()
        dialog.show()
    }

    // ==========================================================
    // NUOVE FUNZIONI PER L'AGGIORNAMENTO AUTOMATICO (GitHub)
    // ==========================================================
    // Aggiungi queste funzioni come membri della tua MainActivity
    private fun setUpdateProgress(message: String, enabled: Boolean = false) {
        updateAppButton.isEnabled = enabled
        updateAppButton.text = message
    }

    private fun resetUpdateButton() {
        updateAppButton.isEnabled = true
        updateAppButton.text = "Aggiorna app" // O il testo originale del tuo pulsante
    }

    private fun checkForUpdate() {
        // 1. Controllo preliminare e stato iniziale del pulsante (nel thread principale)
        setUpdateProgress("Ricerca aggiornamento...")

        activityScope.launch(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                // Controllo sicurezza PAT (opzionale, ma consigliato)
                if (GITHUB_PAT == "IL_TUO_VERO_TOKEN_QUI" || GITHUB_PAT.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        setUpdateProgress("❌ Errore: Token non configurato.", true)
                    }
                    return@launch
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()

                // =================================================================
                // PASSAGGIO 1: Ottenere l'URL di download dell'Asset
                // =================================================================
                val releaseRequest = Request.Builder()
                    .url(GITHUB_LATEST_RELEASE_API)
                    .addHeader("Authorization", "token $GITHUB_PAT") // Autenticazione PAT
                    .build()

                response = client.newCall(releaseRequest).execute()

                if (!response.isSuccessful) {
                    val errorMsg = when (response.code) {
                        401, 403 -> "Autenticazione fallita. Controlla il PAT e i permessi."
                        404 -> "Release non trovata. Controlla l'URL del repository."
                        else -> "Errore nel recupero della Release: ${response.code}"
                    }
                    throw IOException(errorMsg)
                }

                val releaseJson = JSONObject(response.body?.string() ?: throw IOException("Risposta Release vuota."))
                val assetsArray = releaseJson.optJSONArray("assets")
                    ?: throw IOException("Nessun asset trovato. Assicurati che l'APK sia stato caricato.")

                var assetDownloadUrl: String? = null
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    if (asset.getString("name") == APK_FILE_NAME) {
                        assetDownloadUrl = asset.getString("url")
                        break
                    }
                }

                if (assetDownloadUrl == null) {
                    throw IOException("APK asset '$APK_FILE_NAME' non trovato nella Release.")
                }

                response.close() // Chiudiamo la prima risposta

                // =================================================================
                // PASSAGGIO 2: Scaricare l'Asset con indicatore di avanzamento
                // =================================================================
                val downloadRequest = Request.Builder()
                    .url(assetDownloadUrl)
                    .addHeader("Authorization", "token $GITHUB_PAT")
                    .addHeader("Accept", "application/octet-stream") // Richiede il download binario
                    .build()

                response = client.newCall(downloadRequest).execute()

                if (!response.isSuccessful) {
                    throw IOException("Errore durante il download dell'Asset: ${response.code}")
                }

                // Ottenimento dimensione totale e stream
                val totalSize = response.header("Content-Length")?.toLongOrNull() ?: -1L
                val inputStream = response.body?.byteStream() ?: throw IOException("Corpo della risposta nullo.")
                val apkFile = File(getExternalFilesDir(null), APK_FILE_NAME)

                // Mostra ProgressBar e testo di progresso
                withContext(Dispatchers.Main) {
                    downloadProgressBar.visibility = View.VISIBLE
                    downloadProgressText.visibility = View.VISIBLE
                    setUpdateProgress("Download 0%", false)
                }

                // Ciclo di lettura e scrittura con tracciamento avanzamento
                var downloadedSize = 0L
                val buffer = ByteArray(4096)
                var bytesRead: Int

                FileOutputStream(apkFile).use { outputStream ->
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead

                        // Aggiorna l'avanzamento ogni 2% del totale
                        if (totalSize > 0 && downloadedSize % (totalSize / 50).coerceAtLeast(1024L) == 0L) {
                            val progress = ((downloadedSize * 100) / totalSize).toInt()

                            // Aggiornamento della UI nel Main Thread
                            withContext(Dispatchers.Main) {
                                downloadProgressBar.progress = progress
                                downloadProgressText.text = "$progress%"
                                setUpdateProgress("Download $progress%", false)
                            }
                        }
                    }
                    outputStream.flush()
                }

                // -----------------------------------------------------------
                // Passaggio completato: Avvio installazione
                // -----------------------------------------------------------
                withContext(Dispatchers.Main) {
                    // Nascondi gli indicatori di progresso
                    downloadProgressBar.visibility = View.GONE
                    downloadProgressText.visibility = View.GONE

                    // Controlli finali (es. dimensione file)
                    if (apkFile.length() < 1_000_000) {
                        setUpdateProgress("❌ Download fallito o incompleto!", true)
                        Toast.makeText(this@MainActivity, "❌ Download APK incompleto. Dimensione: ${apkFile.length()} bytes", Toast.LENGTH_LONG).show()
                        return@withContext
                    }

                    setUpdateProgress("APK Scaricato. Avvio installazione...", false)
                    requestInstallPermission(apkFile)
                }
            } catch (e: Exception) {
                android.util.Log.e("Update", "Errore aggiornamento (Release Privata): ${e.message}", e)
                withContext(Dispatchers.Main) {
                    // Gestione e riabilitazione del pulsante in caso di errore
                    downloadProgressBar.visibility = View.GONE
                    downloadProgressText.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "❌ Errore aggiornamento: ${e.message}", Toast.LENGTH_LONG).show()
                    setUpdateProgress("❌ Errore: Riprova", true)
                }
            } finally {
                response?.close()
            }
        }
    }


    private fun requestInstallPermission(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                }
                requestInstallPermissionLauncher.launch(intent)
                return
            }
        }
        installApk(apkFile)
    }

    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            Toast.makeText(this, "File APK non trovato.", Toast.LENGTH_SHORT).show()
            updateAppButton.isEnabled = true
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider", // Deve corrispondere all'authority nell'AndroidManifest
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(installIntent)
            // L'app si chiuderà per l'installazione e verrà riavviata dal sistema.
        } catch (e: Exception) {
            android.util.Log.e("Update", "Errore installazione APK: ${e.message}", e)
            Toast.makeText(this, "❌ Errore installazione: ${e.message}", Toast.LENGTH_LONG).show()
            updateAppButton.isEnabled = true
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        statsUpdateTimer?.cancel()
        statsUpdateTimer = null
        activityScope.cancel() // Cancella il coroutine scope dell'attività
    }

    override fun onPause() {
        super.onPause()
        statsUpdateTimer?.cancel()
    }

    override fun onResume() {
        super.onResume()
        if (statsUpdateTimer == null) {
            startStatsUpdateTimer()
        }
        refreshServerStatsQuiet()
        resetUpdateButton()
        updateAppButton.isEnabled = true
    }

    override fun onStop() {
        super.onStop()
        statsUpdateTimer?.cancel()
    }

    override fun onRestart() {
        super.onRestart()
    }
}
data class RecentURLEntry(
    val videoId: String,
    val videoTitle: String,
    val type: String,
    val extractionCount: Int,
    val expiresInSeconds: Long,
    val lastAccessedAt: Long,
    val isValid: Boolean
)

// ⭐ NUOVO Adapter per Recent URLs
class RecentURLsAdapter(private var entries: List<RecentURLEntry>) :
    RecyclerView.Adapter<RecentURLsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleText: TextView = view.findViewById(R.id.urlTitleText)
        val statsText: TextView = view.findViewById(R.id.urlStatsText)
        val statusIcon: TextView = view.findViewById(R.id.urlStatusIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_url, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]

        holder.titleText.text = entry.videoTitle
        holder.statsText.text = "Count: ${entry.extractionCount} • ${formatExpiry(entry.expiresInSeconds)} • ${entry.type}"
        holder.statusIcon.text = if (entry.isValid) "✅" else "⏰"

        // Colore diverso per URL scaduti
        val textColor = if (entry.isValid)
            android.graphics.Color.WHITE
        else
            android.graphics.Color.GRAY
        holder.titleText.setTextColor(textColor)
        holder.statsText.setTextColor(textColor)
    }

    override fun getItemCount() = entries.size

    fun updateData(newEntries: List<RecentURLEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    private fun formatExpiry(seconds: Long): String {
        return when {
            seconds < 0 -> "Expired"
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            else -> "${seconds / 3600}h"
        }
    }
}