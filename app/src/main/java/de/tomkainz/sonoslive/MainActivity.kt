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
import androidx.lifecycle.Lifecycle
import de.tomkainz.sonoslive.adb.AdbManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        // If the notification request came from "Start pairing", continue right away.
        val port = pairingAfterPermission
        pairingAfterPermission = null
        if (port != null && notificationsAllowed()) startPairing(port.takeIf { it > 0 })
    }

    /** Port (0 = automatic) of a pairing that is waiting for the notification permission. */
    private var pairingAfterPermission: Int? = null

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
        // The app launches with the black splash screen (Theme.SonosLive.Start).
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
        // Connect ADB as soon as the app opens: finding the port takes
        // a few seconds, so "Start" then begins immediately.
        if (AppState.mode.value == CaptureMode.ALL_APPS && AppState.adbPaired.value && !AppState.running.value) {
            val app = applicationContext
            AppState.scope.launch { runCatching { AdbManager.connect(app) } }
        }
    }

    // ---- Start of the selected mode --------------------------------------

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
                // Audio only: we create no VirtualDisplay. Force the full display
                // so the "single app" choice does not restrict the capture.
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

    // ---- Permissions ------------------------------------------------------

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
     * Starts pairing: a service with the code notification and port search,
     * then "Wireless debugging". This does not work without notifications,
     * because the code is entered there.
     */
    private fun startPairing(manualPort: Int?) {
        if (!notificationsAllowed()) {
            AppState.adbMessage.value = getString(R.string.pair_need_notifications)
            pairingAfterPermission = manualPort ?: 0
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        PairingService.start(this, manualPort)
        openWirelessDebugging()
    }

    private fun notificationsAllowed() =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    /**
     * Opens the "Wireless debugging" page directly, where "Pair device with
     * pairing code" is listed. There is no public intent for this; the path
     * is the same as a long press on the Quick Settings tile (tested in the
     * emulator with Android 17). If the tile is missing, for example on
     * older versions or other manufacturers: Developer options.
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
        // Developer tiles stay disabled until added; all that matters is whether the tile exists.
        val hasTile = runCatching {
            packageManager.getServiceInfo(tile, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.isSuccess
        if (hasTile) {
            val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES)
                .setClassName(SETTINGS, "$SETTINGS.qstile.QSTileLongPressGatewayActivity")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, tile)
            val opened = runCatching { startActivity(intent) }
            if (opened.isSuccess) {
                // On some devices the gateway accepts the intent and still
                // opens nothing. If the app is still in front afterwards: Developer options.
                window.decorView.postDelayed({
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        AppState.log("Wireless debugging did not open, opening Developer options")
                        openDeveloperOptions()
                    }
                }, OPEN_CHECK_MS)
                return
            }
            AppState.log("Cannot open Wireless debugging directly: ${opened.exceptionOrNull()}")
        } else {
            AppState.log("No tile for Wireless debugging, opening Developer options")
        }
        openDeveloperOptions()
    }

    private fun openDeveloperOptions() {
        // This key makes Settings scroll straight to "Wireless
        // debugging" and highlight the entry.
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
        const val OPEN_CHECK_MS = 1500L
    }
}
