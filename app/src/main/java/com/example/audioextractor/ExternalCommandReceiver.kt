package com.example.audioextractor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receiver statico che gestisce i comandi esterni da altre app.
 * Avvia il HttpServerService se necessario.
 */
class ExternalCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ExternalCommandReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        Log.d(TAG, "📥 Received broadcast: $action")

        when (action) {
            "com.example.audioextractor.action.SERVER_COMMAND" -> {
                val command = intent.getStringExtra("command") ?: return
                Log.d(TAG, "🎯 Server command: $command")

                when (command) {
                    "START" -> {
                        // Avvia il service
                        Log.d(TAG, "▶️ Starting HttpServerService...")
                        val serviceIntent = Intent(context, HttpServerService::class.java).apply {
                            putExtra("COMMAND", "START")
                        }
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                            Log.d(TAG, "✅ HttpServerService started successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to start service: ${e.message}", e)
                        }
                    }

                    "STOP", "TOGGLE", "TERMINATE", "SHOW_INFO" -> {
                        // Forward command to service (se è già in esecuzione)
                        Log.d(TAG, "📤 Forwarding command to service: $command")
                        val serviceIntent = Intent(context, HttpServerService::class.java).apply {
                            putExtra("COMMAND", command)
                        }
                        try {
                            context.startService(serviceIntent)
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Failed to forward command: ${e.message}", e)
                        }
                    }
                }
            }

            // Forward altri comandi al service (se è in esecuzione)
            "com.example.audioextractor.action.PLAYER_PLAY",
            "com.example.audioextractor.action.PLAYER_PAUSE",
            "com.example.audioextractor.action.PLAYER_STOP",
            "com.example.audioextractor.action.PLAYER_SEEK",
            "com.example.audioextractor.action.EXTPLAY",
            "com.example.audioextractor.action.EXTRACT_ONLY",
            "com.example.audioextractor.action.PLAYER_STATUS_REQUEST" -> {
                Log.d(TAG, "📤 Forwarding action to service: $action")
                // Questi comandi richiedono che il service sia già in esecuzione
                // Il service registra un receiver dinamico per gestirli
                // Qui possiamo solo assicurarci che il service sia avviato
                try {
                    val serviceIntent = Intent(context, HttpServerService::class.java).apply {
                        putExtra("COMMAND", "START") // Assicura che il service sia avviato
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to ensure service is running: ${e.message}", e)
                }
            }
        }
    }
}