package com.example.audioextractor

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import android.util.Log

class ServiceLauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("ServiceLauncherActivity", "🔵 ServiceLauncherActivity onCreate called")

        val command = intent.getStringExtra("COMMAND") ?: HttpServerService.ACTION_START

        val serviceIntent = Intent(this, HttpServerService::class.java).apply {
            putExtra("COMMAND", command)
        }

        try {
            // Usa 'this' per il contesto dell'Activity
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.startForegroundService(serviceIntent) // CORRETTO
                Log.d("ServiceLauncherActivity", "✅ Started foreground service: $command")
            } else {
                this.startService(serviceIntent) // CORRETTO
                Log.d("ServiceLauncherActivity", "✅ Started service: $command")
            }
        } catch (e: Exception) {
            Log.e("ServiceLauncherActivity", "❌ Failed to start HttpServerService: ${e.message}", e)
            // Qui potresti mostrare un Toast o una notifica all'utente se il service non parte
        }

        // Chiudi l'Activity immediatamente in un piccolo delay per non prendere il focus
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d("ServiceLauncherActivity", "⏳ Finishing ServiceLauncherActivity")
            finish()
        }, 100) // 100ms di delay per chiudere l'Activity
    }
}
