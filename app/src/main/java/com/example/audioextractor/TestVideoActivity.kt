package com.example.audioextractor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class TestVideoActivity : AppCompatActivity() {

    private lateinit var videoIdInput: EditText
    private lateinit var extractAudioButton: Button
    private lateinit var extractVideoButton: Button
    private lateinit var resultView: TextView
    private lateinit var copyButton: Button
    private lateinit var openButton: Button
    private lateinit var qualitySpinner: Spinner
    private lateinit var scrollView: NestedScrollView
    private lateinit var backButton: Button

    private var currentUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_video)

        videoIdInput = findViewById(R.id.videoIdInput)
        extractAudioButton = findViewById(R.id.extractAudioButton)
        extractVideoButton = findViewById(R.id.extractVideoButton)
        resultView = findViewById(R.id.resultView)
        copyButton = findViewById(R.id.copyButton)
        openButton = findViewById(R.id.openButton)
        qualitySpinner = findViewById(R.id.qualitySpinner)
        scrollView = findViewById(R.id.scrollView)
        backButton = findViewById(R.id.backButton)

        val qualities = listOf("144", "240", "360", "480", "720")
        qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities)

        extractAudioButton.setOnClickListener {
            extractStream(isAudio = true)
        }

        extractVideoButton.setOnClickListener {
            extractStream(isAudio = false)
        }

        copyButton.setOnClickListener {
            currentUrl?.let { url ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                Toast.makeText(this, "URL copiato negli appunti", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this, "Nessun URL da copiare", Toast.LENGTH_SHORT).show()
            }
        }

        openButton.setOnClickListener {
            currentUrl?.let { url ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Errore apertura URL: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } ?: run {
                Toast.makeText(this, "Nessun URL da aprire", Toast.LENGTH_SHORT).show()
            }
        }

        backButton.setOnClickListener {
            finish()
        }
    }

    private fun extractStream(isAudio: Boolean) {
        val input = videoIdInput.text.toString().trim()
        if (input.isEmpty()) {
            resultView.text = "⚠️ Inserisci un videoId o URL YouTube valido"
            Toast.makeText(this, "Inserisci un Video ID", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedQualityStr = qualitySpinner.selectedItem.toString()
        val selectedQualityInt = selectedQualityStr.toIntOrNull() ?: 720

        extractAudioButton.isEnabled = false
        extractVideoButton.isEnabled = false

        lifecycleScope.launch {
            resultView.text = "🔄 Estrazione in corso...\n\nConnessione al server locale...\nRichiesta URL ${if (isAudio) "audio" else "video"}..."

            currentUrl = withContext(Dispatchers.IO) {
                try {
                    val prefs = getSharedPreferences("ServerSettings", Context.MODE_PRIVATE)
                    val serverAddress = prefs.getString("server_address", "localhost") ?: "localhost"
                    val serverPort = prefs.getInt("server_port", 8080)

                    val extractType = if (isAudio) "audio" else "video"
                    val url = URL("http://$serverAddress:$serverPort/extract?videoId=$input&type=$extractType&quality=$selectedQualityInt&OnlyURL=true")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 30000
                    connection.readTimeout = 30000

                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().readText()
                        if (response.startsWith("ERROR:")) {
                            null
                        } else {
                            response.trim()
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    android.util.Log.e("TestVideoActivity", "Errore estrazione via server", e)
                    null
                }
            }

            scrollView.post { scrollView.fullScroll(NestedScrollView.FOCUS_DOWN) }

            if (currentUrl != null && currentUrl!!.isNotEmpty()) {
                resultView.text = "✅ ESTRAZIONE COMPLETATA\n\n" +
                        "Tipo: ${if (isAudio) "Audio" else "Video (${selectedQualityStr}p)"}\n" +
                        "Video ID: $input\n\n" +
                        "URL estratto:\n$currentUrl\n\n" +
                        "Usa i pulsanti sottostanti per copiare o aprire l'URL."
                Toast.makeText(this@TestVideoActivity, "✅ URL estratto con successo!", Toast.LENGTH_SHORT).show()
            } else {
                resultView.text = "❌ ESTRAZIONE FALLITA\n\n" +
                        "Impossibile estrarre l'URL per il video richiesto.\n\n" +
                        "Possibili cause:\n" +
                        "• Server non attivo\n" +
                        "• Video non disponibile\n" +
                        "• Video privato o rimosso\n" +
                        "• Problemi di connessione\n" +
                        "• Video ID non valido\n\n" +
                        "Riprova con un altro video."
                Toast.makeText(this@TestVideoActivity, "❌ Estrazione fallita", Toast.LENGTH_LONG).show()
            }

            extractAudioButton.isEnabled = true
            extractVideoButton.isEnabled = true
        }
    }
}
