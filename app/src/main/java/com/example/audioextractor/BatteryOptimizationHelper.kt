package com.example.audioextractor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

/**
 * Helper per gestire le ottimizzazioni della batteria
 * che potrebbero causare la chiusura del servizio
 */
object BatteryOptimizationHelper {

    /**
     * Verifica se l'app è esclusa dalle ottimizzazioni della batteria
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true // Pre-Android M non ha ottimizzazioni batteria
    }

    /**
     * Mostra un dialogo per richiedere l'esclusione dalle ottimizzazioni
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations(context)) {
                AlertDialog.Builder(context)
                    .setTitle("🔋 Ottimizzazione Batteria")
                    .setMessage(
                        "Per garantire che il server funzioni sempre in background, " +
                                "è consigliato disabilitare l'ottimizzazione della batteria per questa app.\n\n" +
                                "Questo non influirà significativamente sulla durata della batteria."
                    )
                    .setPositiveButton("Impostazioni") { _, _ ->
                        openBatteryOptimizationSettings(context)
                    }
                    .setNegativeButton("Più tardi", null)
                    .show()
            }
        }
    }

    /**
     * Apre le impostazioni per disabilitare l'ottimizzazione batteria
     */
    private fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback: apri le impostazioni generali
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(intent)
            }
        }
    }

    /**
     * Verifica se il dispositivo ha manufacturer-specific battery optimizations
     * (es. MIUI, EMUI, OneUI)
     */
    fun checkManufacturerOptimizations(context: Context): String? {
        val manufacturer = Build.MANUFACTURER.lowercase()

        return when {
            manufacturer.contains("xiaomi") ->
                "Su dispositivi Xiaomi/MIUI, vai in Impostazioni → App → Gestisci app → Music Server → " +
                        "Risparmio energetico → Nessuna restrizione"

            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                "Su dispositivi Huawei/Honor, vai in Impostazioni → Batteria → Avvio app → " +
                        "Music Server → Gestisci manualmente (abilita tutto)"

            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ->
                "Su dispositivi OPPO/Realme/OnePlus, vai in Impostazioni → Batteria → " +
                        "Ottimizzazione batteria → Music Server → Non ottimizzare"

            manufacturer.contains("samsung") ->
                "Su dispositivi Samsung, vai in Impostazioni → Batteria e cura del dispositivo → " +
                        "Batteria → Limiti di utilizzo in background → App non monitorate → Aggiungi Music Server"

            manufacturer.contains("vivo") ->
                "Su dispositivi Vivo, vai in Impostazioni → Batteria → Gestione in background → " +
                        "Music Server → Consenti esecuzione in background"

            else -> null
        }
    }

    /**
     * Mostra un dialogo con istruzioni specifiche per il produttore
     */
    fun showManufacturerInstructions(context: Context) {
        val instructions = checkManufacturerOptimizations(context)

        if (instructions != null) {
            AlertDialog.Builder(context)
                .setTitle("⚠️ Impostazioni Specifiche")
                .setMessage(instructions)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}