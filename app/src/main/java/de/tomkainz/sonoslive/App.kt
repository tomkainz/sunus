package de.tomkainz.sonoslive

import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // libadb-android uses hidden platform APIs; without the
        // exemption, connecting fails on Android 9+.
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
