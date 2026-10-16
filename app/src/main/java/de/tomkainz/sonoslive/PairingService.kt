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
 * Einmalige Kopplung fuer den Alle-Apps-Modus.
 *
 * Android schliesst den Dialog "Geraet mit Kopplungscode koppeln", sobald die
 * Einstellungen in den Hintergrund gehen, und mit ihm verfaellt der Code.
 * Deshalb wird der Code hier ueber eine Benachrichtigung mit Eingabefeld
 * abgefragt, waehrend der Dialog offen bleibt. Den Port findet der Dienst
 * selbst per mDNS (_adb-tls-pairing._tcp).
 *
 * Als Vordergrunddienst, weil Android Apps im Hintergrund sonst einfriert und
 * die Suche dann keine Antwort mehr bekommt.
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CODE -> {
                onCode(intent)
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                finish("Kopplung abgebrochen.")
                return START_NOT_STICKY
            }
        }

        createChannel()
        intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 }?.let { port = it }
        startForeground(
            NOTIFICATION_ID,
            notification("Kopplungsdialog öffnen, dann hier den Code eingeben", withInput = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        AppState.adbBusy.value = true
        AppState.adbMessage.value = "Warte auf den Kopplungsdialog..."
        startDiscovery()
        handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
        handler.postAtTime({ finish("Zeit abgelaufen. Bitte erneut starten.") },
            TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
                    update("Kopplungsdialog gefunden. Jetzt den 6-stelligen Code eingeben.", withInput = true)
                    AppState.adbMessage.value = "Kopplungsdialog gefunden. Code in der Benachrichtigung eingeben."
                }
            }
        }.also { it.start() }
    }

    private fun onCode(intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_CODE)?.toString()?.filter { it.isDigit() } ?: ""
        if (code.length != 6) {
            update("Der Code hat 6 Ziffern. Bitte erneut eingeben.", withInput = true)
            return
        }
        if (busy) return
        busy = true
        update("Kopple...", withInput = false)
        AppState.adbMessage.value = "Kopple..."

        AppState.scope.launch {
            // Kam der Code schneller als die Suche, kurz auf den Port warten.
            var waited = 0
            while (port <= 0 && waited < 15_000) {
                delay(250)
                waited += 250
            }
            if (port <= 0) {
                busy = false
                update("Kein Kopplungsdialog gefunden. Ist er noch offen?", withInput = true)
                AppState.adbMessage.value = "Kein Kopplungsdialog gefunden."
                return@launch
            }

            val paired = try {
                AdbManager.pair(this@PairingService, host, port, code)
            } catch (e: Exception) {
                false
            }
            if (!paired) {
                busy = false
                update("Kopplung fehlgeschlagen. Code prüfen; der Dialog muss offen sein.", withInput = true)
                AppState.adbMessage.value = "Kopplung fehlgeschlagen. Code prüfen."
                return@launch
            }

            update("Gekoppelt. Verbinde...", withInput = false)
            val connected = runCatching { AdbManager.connect(this@PairingService) }.getOrDefault(false)
            AppState.setAdbPaired(true)
            AppState.requestPairing.value = false
            if (connected) {
                finish("Gekoppelt. Alle Apps ist eingerichtet.")
            } else {
                finish("Gekoppelt. Verbindung folgt beim Start.")
            }
        }
    }

    private fun finish(message: String) {
        AppState.adbMessage.value = message
        AppState.adbBusy.value = false
        mdns?.stop()
        mdns = null
        handler.removeCallbacksAndMessages(null)
        // Ergebnis kurz stehen lassen, dann aufraeumen
        stopForeground(STOP_FOREGROUND_DETACH)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, withInput = false, ongoing = false))
        stopSelf()
    }

    // ---- Benachrichtigung ---------------------------------------------------

    private fun update(text: String, withInput: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text, withInput))
    }

    private fun notification(text: String, withInput: Boolean, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Sonos Live koppeln")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)

        if (withInput) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel("6-stelliger Code").build()
            // FLAG_MUTABLE ist fuer RemoteInput noetig, damit das System den
            // eingegebenen Text einfuegen kann. Die Absicht ist explizit.
            val reply = PendingIntent.getService(
                this, 1,
                Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val icon = Icon.createWithResource(this, R.drawable.ic_launcher_foreground)
            builder.addAction(
                Notification.Action.Builder(icon, "Code eingeben", reply)
                    .addRemoteInput(input)
                    .build()
            )
        }
        if (ongoing) {
            val cancel = PendingIntent.getService(
                this, 2,
                Intent(this, PairingService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(null, "Abbrechen", cancel).build())
        }
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // Hohe Wichtigkeit: die Benachrichtigung soll ueber dem Kopplungsdialog
        // einblenden, damit man den Code eingeben kann, ohne ihn zu verlassen.
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_pairing), NotificationManager.IMPORTANCE_HIGH
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "sonos-live-pairing"
        private const val NOTIFICATION_ID = 2
        private const val KEY_CODE = "code"
        private const val ACTION_CODE = "de.tomkainz.sonoslive.PAIR_CODE"
        private const val ACTION_CANCEL = "de.tomkainz.sonoslive.PAIR_CANCEL"
        private const val EXTRA_PORT = "port"
        private const val TIMEOUT_MS = 5 * 60_000L
        private val TIMEOUT_TOKEN = Any()

        /** Startet die Kopplung; [manualPort] nur, falls die Suche im WLAN scheitert. */
        fun start(context: Context, manualPort: Int? = null) {
            val intent = Intent(context, PairingService::class.java)
            manualPort?.let { intent.putExtra(EXTRA_PORT, it) }
            context.startForegroundService(intent)
        }
    }
}
