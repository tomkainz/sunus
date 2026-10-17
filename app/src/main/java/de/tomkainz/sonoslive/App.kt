package de.tomkainz.sonoslive

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // libadb-android greift auf verborgene Plattform-APIs zu; ohne die
        // Freigabe scheitert der Verbindungsaufbau auf Android 9+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        Diagnose.init(this)
    }
}
