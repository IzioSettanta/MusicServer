package com.example.audioextractor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Receiver per avviare automaticamente il server al boot del dispositivo
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "📱 Device booted - Starting HTTP Server Service")

            // Verifica se l'utente ha abilitato l'avvio automatico nelle impostazioni
            val prefs = context.getSharedPreferences("ServerSettings", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_on_boot", true)

            if (autoStart) {
                val serviceIntent = Intent(context, HttpServerService::class.java).apply {
                    putExtra("COMMAND", HttpServerService.ACTION_START)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("BootReceiver", "✅ Server started successfully")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "❌ Failed to start server: ${e.message}", e)
                }
            } else {
                Log.d("BootReceiver", "⏸️ Auto-start disabled by user")
            }
        }
    }
}