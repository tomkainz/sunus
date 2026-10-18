/*
 * Copyright (C) 2018 Genymobile
 * Copyright (C) 2018-2026 Romain Vimont
 * Copyright (c) 2026 tomkainz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from scrcpy (FakeContext.java) for Sunus.
 * Changed: reduced to the audio part, renamed, comments rewritten.
 */

package de.tomkainz.sonoslive.shell;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;

import androidx.annotation.RequiresApi;

/**
 * Context that poses as com.android.shell (uid 2000).
 *
 * When opening a recording, AudioFlinger checks not only the uid but
 * also the package name of the AttributionSource. Without this context the
 * recording is rejected despite shell permissions. Based on scrcpy (FakeContext.java).
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

    @RequiresApi(Build.VERSION_CODES.S)
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
