package de.tomkainz.sonoslive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            LiveService.startStandard(this, result.resultCode, data)
        } else {
            AppState.message.value = "Ohne Zustimmung kann der Standard-Modus nicht aufnehmen."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SonosLiveTheme {
                SonosLiveApp(
                    onStart = { requestStart() },
                    onStop = { LiveService.stop(this) },
                    onDiscover = { AppState.discover(this) },
                    onSelect = { AppState.select(it) },
                    onAddManual = { AppState.addManual(it) },
                    onSetVolume = { AppState.setVolume(it) },
                    onPair = { port, code -> AppState.pairAllApps(this, port, code) },
                    onOpenDevOptions = { openDeveloperOptions() }
                )
            }
        }

        AppState.discover(this)
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    // ---- Start der gewaehlten Betriebsart --------------------------------

    private fun requestStart() {
        if (AppState.selected.value == null) {
            AppState.message.value = "Erst einen Lautsprecher wählen."
            return
        }
        when (AppState.mode.value) {
            CaptureMode.STANDARD -> {
                if (!granted(Manifest.permission.RECORD_AUDIO)) {
                    ensurePermissions()
                    AppState.message.value = "Für den Standard-Modus die Mikrofon-/Aufnahme-Berechtigung erlauben."
                    return
                }
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                // Nur Audio: wir erzeugen kein VirtualDisplay. Vollbild erzwingen,
                // damit die "eine App"-Auswahl die Erfassung nicht einschraenkt.
                val intent = if (Build.VERSION.SDK_INT >= 34) {
                    val config = android.media.projection.MediaProjectionConfig
                        .createConfigForDefaultDisplay()
                    mpm.createScreenCaptureIntent(config)
                } else {
                    mpm.createScreenCaptureIntent()
                }
                projectionLauncher.launch(intent)
            }
            CaptureMode.ALL_APPS -> {
                if (!AppState.adbPaired.value) {
                    AppState.requestPairing.value = true
                    return
                }
                LiveService.startAllApps(this)
            }
        }
    }

    // ---- Berechtigungen ---------------------------------------------------

    private fun needed(): List<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 37) list += "android.permission.ACCESS_LOCAL_NETWORK"
        return list
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val missing = needed().filterNot { granted(it) }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun refreshPermissions() {
        val networkOk = Build.VERSION.SDK_INT < 37 || granted("android.permission.ACCESS_LOCAL_NETWORK")
        if (!networkOk) {
            AppState.message.value =
                "Ohne die Berechtigung für das lokale Netzwerk findet die App keinen Lautsprecher."
        }
    }

    private fun openDeveloperOptions() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                }
            }
    }
}
