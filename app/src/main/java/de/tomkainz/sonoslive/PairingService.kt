package de.tomkainz.sonoslive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import de.tomkainz.sonoslive.adb.AdbManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One-time pairing for the All apps mode.
 *
 * Android closes the "Pair device with pairing code" dialog as soon as
 * Settings goes to the background, and the code expires with it.
 * That is why the code is asked for here through a notification with an
 * input field, while the dialog stays open. The service finds the port
 * itself via mDNS (_adb-tls-pairing._tcp).
 *
 * Runs as a foreground service, because Android otherwise freezes background
 * apps and the search then gets no more answers.
 */
class PairingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var mdns: AdbMdns? = null

    @Volatile
    private var host: String? = null

    @Volatile
    private var port: Int = -1

    @Volatile
    private var busy = false
        set(value) {
            field = value
            AppState.adbBusy.value = value
        }

    @Volatile
    private var active = false

    // Last shown state, for showing it again after it was swiped away.
    private var lastText = ""
    private var lastInput = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CODE -> {
                onCode(intent)
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                finish(getString(R.string.pair_cancelled))
                return START_NOT_STICKY
            }
            ACTION_DISMISSED -> {
                // Swiped away (possible for foreground services since Android 14):
                // show it again right away, otherwise there is no field for the code.
                if (active) update(lastText, lastInput) else stopSelf()
                return START_NOT_STICKY
            }
        }

        // "Start pairing" again: show the notification again, the search
        // keeps running. While the code is being checked, change nothing.
        if (busy) return START_NOT_STICKY
        active = true

        createChannel()
        getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
        startForeground(
            NOTIFICATION_ID,
            notification(getString(R.string.pair_notif_open), withInput = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        lastText = getString(R.string.pair_notif_open)
        lastInput = true
        AppState.adbMessage.value = getString(R.string.pair_waiting)
        startDiscovery()
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        handler.postAtTime({ finish(getString(R.string.pair_timeout)) },
            TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        mdns?.stop()
        mdns = null
        handler.removeCallbacksAndMessages(null)
        AppState.adbBusy.value = false
        super.onDestroy()
    }

    private fun startDiscovery() {
        mdns?.stop()
        mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { address, p ->
            if (p > 0) {
                host = address?.hostAddress
                port = p
                if (!busy) {
                    update(getString(R.string.pair_found_notif), withInput = true)
                    AppState.adbMessage.value = getString(R.string.pair_found)
                }
            }
        }.also { it.start() }
    }

    private fun onCode(intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
        val input = PairingInput.parse(text)
        if (input == null) {
            update(getString(R.string.pair_digits_notif), withInput = true)
            AppState.adbMessage.value = getString(R.string.pair_digits)
            return
        }
        if (busy) return
        busy = true
        // A port typed in by hand wins over whatever the search found.
        input.port?.let {
            host = null
            port = it
        }
        val code = input.code
        update(getString(R.string.pair_pairing), withInput = false)
        AppState.adbMessage.value = getString(R.string.pair_pairing)

        AppState.scope.launch {
            // If the code arrived before the search finished, wait briefly for the port.
            var waited = 0
            while (port <= 0 && waited < MDNS_WAIT_MS) {
                delay(250)
                waited += 250
            }
            if (port <= 0) {
                // mDNS is not reliable on every phone: look at the open ports instead.
                AppState.log("Pairing: mDNS found no dialog, scanning local ports")
                if (!AdbManager.pairByScan(this@PairingService, code)) {
                    busy = false
                    update(getString(R.string.pair_no_dialog_notif), withInput = true)
                    AppState.adbMessage.value = getString(R.string.pair_no_dialog)
                    return@launch
                }
            } else {
                val paired = try {
                    AdbManager.pair(this@PairingService, host, port, code)
                } catch (e: Exception) {
                    AppState.log("Pairing failed on port $port (${e.javaClass.simpleName}: ${e.message})")
                    false
                }
                if (!paired) {
                    busy = false
                    update(getString(R.string.pair_failed_notif), withInput = true)
                    AppState.adbMessage.value = getString(R.string.pair_failed)
                    return@launch
                }
            }

            update(getString(R.string.pair_connecting), withInput = false)
            var connection = runCatching { AdbManager.connect(this@PairingService) }
            if (connection.getOrNull() == false) {
                delay(1000)
                connection = runCatching { AdbManager.connect(this@PairingService) }
            }
            // false: the phone refuses this app although pairing succeeded. Do not
            // report it as set up. An exception only means the service was not found
            // right now; the pairing stays valid and the next start connects.
            if (connection.getOrNull() == false) {
                AppState.setAdbPaired(false)
                busy = false
                update(getString(R.string.pair_rejected), withInput = true)
                AppState.adbMessage.value = getString(R.string.pair_rejected)
                return@launch
            }
            val connected = connection.getOrNull() == true
            AppState.setAdbPaired(true)
            AppState.requestPairing.value = false
            if (connected) {
                AdbManager.grantSelf(this@PairingService)
                finish(getString(R.string.pair_done))
            } else {
                finish(getString(R.string.pair_done_later))
            }
        }
    }

    private fun finish(message: String) {
        active = false
        busy = false
        AppState.adbMessage.value = message
        mdns?.stop()
        mdns = null
        handler.removeCallbacksAndMessages(null)
        // Result as a separate notification. Overwriting the ongoing one after
        // STOP_FOREGROUND_DETACH got lost in the emulator:
        // "Paired. Connecting..." stayed visible although everything was done.
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)
            .notify(RESULT_ID, notification(message, withInput = false, ongoing = false))
        stopSelf()
    }

    // ---- Notification -------------------------------------------------------

    private fun update(text: String, withInput: Boolean) {
        lastText = text
        lastInput = withInput
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, withInput))
    }

    private fun notification(text: String, withInput: Boolean, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(getString(R.string.pair_notif_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)

        if (withInput) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel(getString(R.string.pair_code_label)).build()
            // FLAG_MUTABLE is required for RemoteInput so the system can insert
            // the entered text. The intent is explicit.
            val reply = PendingIntent.getService(
                this, 1,
                Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val icon = Icon.createWithResource(this, R.drawable.ic_stat_logo)
            builder.addAction(
                Notification.Action.Builder(icon, getString(R.string.pair_enter_code), reply)
                    .addRemoteInput(input)
                    .build()
            )
        }
        if (!ongoing) builder.setAutoCancel(true).setTimeoutAfter(RESULT_VISIBLE_MS)
        if (ongoing) {
            val cancel = PendingIntent.getService(
                this, 2,
                Intent(this, PairingService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            val dismissed = PendingIntent.getService(
                this, 3,
                Intent(this, PairingService::class.java).setAction(ACTION_DISMISSED),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.setDeleteIntent(dismissed)
        }
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // High importance: the notification should pop up over the pairing dialog,
        // so the code can be entered without leaving it.
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_pairing), NotificationManager.IMPORTANCE_HIGH
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "sonos-live-pairing"
        private const val NOTIFICATION_ID = 2
        private const val RESULT_ID = 3
        private const val RESULT_VISIBLE_MS = 60_000L
        private const val KEY_CODE = "code"
        private const val ACTION_CODE = "de.tomkainz.sonoslive.PAIR_CODE"
        private const val ACTION_CANCEL = "de.tomkainz.sonoslive.PAIR_CANCEL"
        private const val ACTION_DISMISSED = "de.tomkainz.sonoslive.PAIR_DISMISSED"
        private const val MDNS_WAIT_MS = 5_000
        private const val TIMEOUT_MS = 5 * 60_000L
        private val TIMEOUT_TOKEN = Any()

        /** Starts pairing. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, PairingService::class.java))
        }
    }
}
