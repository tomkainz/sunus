package de.tomkainz.sonoslive

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import de.tomkainz.sonoslive.adb.AdbManager
import kotlinx.coroutines.launch

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
            AppState.message.value = getString(R.string.msg_no_consent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Gestartet wird mit dem schwarzen Startbild (Theme.SonosLive.Start).
        setTheme(R.style.Theme_SonosLive)
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
                    onStartPairing = { port -> startPairing(port) }
                )
            }
        }

        AppState.discover(this)
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        // ADB schon beim Oeffnen verbinden: die Suche nach dem Port dauert
        // einige Sekunden, "Start" geht dann sofort los.
        if (AppState.mode.value == CaptureMode.ALL_APPS && AppState.adbPaired.value && !AppState.running.value) {
            val app = applicationContext
            AppState.scope.launch { runCatching { AdbManager.connect(app) } }
        }
    }

    // ---- Start der gewaehlten Betriebsart --------------------------------

    private fun requestStart() {
        if (AppState.selected.value == null) {
            AppState.message.value = getString(R.string.msg_pick_speaker)
            return
        }
        when (AppState.mode.value) {
            CaptureMode.STANDARD -> {
                if (!granted(Manifest.permission.RECORD_AUDIO)) {
                    ensurePermissions()
                    AppState.message.value = getString(R.string.msg_need_record)
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
            AppState.message.value = getString(R.string.msg_need_network)
        }
    }

    /**
     * Startet die Kopplung: Dienst mit Code-Benachrichtigung und Port-Suche,
     * dann "Kabelloses Debugging". Ohne Benachrichtigungen geht es nicht, weil
     * der Code dort eingegeben wird.
     */
    private fun startPairing(manualPort: Int?) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            AppState.adbMessage.value = getString(R.string.pair_need_notifications)
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        PairingService.start(this, manualPort)
        openWirelessDebugging()
    }

    /**
     * Oeffnet direkt die Seite "Kabelloses Debugging", auf der "Geraet mit
     * Kopplungscode koppeln" steht. Dafuer gibt es keine oeffentliche Absicht;
     * der Weg ist derselbe wie beim langen Druck auf die Schnelleinstellungs-
     * Kachel (im Emulator mit Android 17 geprueft). Fehlt die Kachel, etwa bei
     * aelteren Versionen oder anderen Herstellern: Entwickleroptionen.
     */
    private fun openWirelessDebugging() {
        val developerOptions = android.provider.Settings.Global.getInt(
            contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1
        if (!developerOptions) {
            AppState.adbMessage.value = getString(R.string.pair_unlock_dev)
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            return
        }
        val tile = ComponentName(SETTINGS, "$SETTINGS.development.qstile.AdbWirelessDebuggingDevelopmentTile")
        // Entwickler-Kacheln sind ausgeschaltet, bis man sie hinzufuegt; es geht nur darum, ob es sie gibt.
        val hasTile = runCatching {
            packageManager.getServiceInfo(tile, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.isSuccess
        if (hasTile) {
            val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES)
                .setClassName(SETTINGS, "$SETTINGS.qstile.QSTileLongPressGatewayActivity")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, tile)
            val opened = runCatching { startActivity(intent) }
            if (opened.isSuccess) return
            AppState.log("Kabelloses Debugging nicht direkt zu öffnen: ${opened.exceptionOrNull()}")
        } else {
            AppState.log("Keine Kachel für Kabelloses Debugging, öffne Entwickleroptionen")
        }
        openDeveloperOptions()
    }

    private fun openDeveloperOptions() {
        // Der Schluessel laesst die Einstellungen direkt zu "Kabelloses
        // Debugging" scrollen und den Eintrag hervorheben.
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                AppState.adbMessage.value = getString(R.string.pair_dev_locked)
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS))
                }
            }
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
    }
}
