package de.tomkainz.sonoslive.shell;

import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;

/**
 * Context, der sich als com.android.shell (uid 2000) ausgibt.
 *
 * AudioFlinger prueft beim Oeffnen einer Aufnahme nicht nur die uid, sondern
 * auch den Paketnamen der AttributionSource. Ohne diesen Context wird die
 * Aufnahme trotz Shell-Rechten abgelehnt. Nach scrcpy (FakeContext.java).
 */
final class ShellContext extends ContextWrapper {

    static final String PACKAGE_NAME = "com.android.shell";
    private static final int SHELL_UID = 2000;

    private static final ShellContext INSTANCE = new ShellContext();

    static ShellContext get() {
        return INSTANCE;
    }

    private ShellContext() {
        super(ShellRuntime.systemContext());
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @TargetApi(Build.VERSION_CODES.S)
    @Override
    public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(SHELL_UID).setPackageName(PACKAGE_NAME).build();
    }

    @Override
    public int getDeviceId() {
        return 0;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }
}
