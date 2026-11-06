package de.tomkainz.sonoslive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.net.InetAddress
import kotlinx.coroutines.launch

/**
 * Lets other apps (Tasker, Home Assistant) start and stop the stream and pick a
 * speaker. Off until the user turns it on under "⋯", because any app on the phone
 * could otherwise start capturing.
 *
 * An invisible activity and not a broadcast receiver: Android does not let an
 * app start a foreground service from the background, but a visible activity may.
 */
class AutomationActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (!AppState.automationAllowed.value) {
            AppState.log("Automation refused, it is turned off: $action")
            toast(getString(R.string.auto_off))
            finish()
            return
        }
        AppState.log("Automation: $action")
        when (action) {
            Automation.ACTION_STOP -> {
                if (AppState.running.value) LiveService.stop(this)
                finish()
            }
            Automation.ACTION_START, Automation.ACTION_SELECT_SPEAKER -> {
                val query = intent.getStringExtra(Automation.EXTRA_SPEAKER)?.trim().orEmpty()
                if (query.isEmpty()) {
                    done(action)
                } else {
                    val app = applicationContext
                    AppState.scope.launch {
                        val speaker = Automation.resolve(app, query)
                        runOnUiThread {
                            if (speaker == null) {
                                toast(getString(R.string.auto_no_speaker, query))
                                finish()
                            } else {
                                AppState.select(speaker)
                                done(action)
                            }
                        }
                    }
                }
            }
            else -> finish()
        }
    }

    private fun done(action: String) {
        if (action == Automation.ACTION_START) start()
        finish()
    }

    private fun start() {
        when {
            AppState.running.value -> Unit
            AppState.selected.value == null -> toast(getString(R.string.msg_pick_speaker))
            AppState.mode.value == CaptureMode.ALL_APPS && AppState.adbPaired.value ->
                LiveService.startAllApps(this)
            // Standard mode needs Android's consent, an unpaired All apps needs setup.
            else -> startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

object Automation {
    const val ACTION_START = "de.tomkainz.sonoslive.action.START"
    const val ACTION_STOP = "de.tomkainz.sonoslive.action.STOP"
    const val ACTION_SELECT_SPEAKER = "de.tomkainz.sonoslive.action.SELECT_SPEAKER"

    /** Room name or IP address of the speaker. */
    const val EXTRA_SPEAKER = "speaker"

    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /** Matches a room name (ignoring case) or an IP address. */
    fun findSpeaker(speakers: List<Speaker>, query: String): Speaker? =
        speakers.firstOrNull { it.ip == query || it.room.equals(query, ignoreCase = true) }

    /** Only local addresses: this input comes from other apps. */
    fun isLocalAddress(query: String): Boolean {
        if (!IPV4.matches(query)) return false
        return runCatching { InetAddress.getByName(query).isSiteLocalAddress }.getOrDefault(false)
    }

    /** Looks in what is known first, then asks the network. Network: not on the main thread. */
    fun resolve(context: Context, query: String): Speaker? {
        val known = AppState.speakers.value + listOfNotNull(AppState.selected.value)
        findSpeaker(known, query)?.let { return it }
        if (IPV4.matches(query)) return if (isLocalAddress(query)) Ssdp.probe(query) else null
        val found = Ssdp.discover(context).ifEmpty {
            Net.localIp(context)?.let { Ssdp.scanSubnet(it) }.orEmpty()
        }
        AppState.speakers.value = (AppState.speakers.value + found).distinctBy { it.udn }.sortedBy { it.room.lowercase() }
        return findSpeaker(found, query)
    }
}
