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
 * Adapted from scrcpy (Workarounds.java) for Sunus.
 * Changed: reduced to what AudioRecord needs, renamed, comments rewritten.
 */

package de.tomkainz.sonoslive.shell;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Sets up, for a process started via app_process, the minimum of
 * Android runtime that AudioRecord expects: an ActivityThread, a
 * system context and an Application that poses as com.android.shell.
 *
 * Approach and order follow scrcpy (Workarounds.java, Apache-2.0), reduced to
 * the audio part.
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi"})
final class ShellRuntime {

    private static final Class<?> ACTIVITY_THREAD_CLASS;
    private static final Object ACTIVITY_THREAD;

    static {
        try {
            ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
            Constructor<?> ctor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
            ctor.setAccessible(true);
            ACTIVITY_THREAD = ctor.newInstance();

            Field current = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, ACTIVITY_THREAD);

            Field systemThread = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread");
            systemThread.setAccessible(true);
            systemThread.setBoolean(ACTIVITY_THREAD, true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private ShellRuntime() {
    }

    static void apply() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Must come before the application context, otherwise some
            // Samsung devices have no valid system context.
            fillConfigurationController();
        }
        if (!Build.BRAND.equalsIgnoreCase("ONYX")) {
            fillAppInfo();
        }
        fillAppContext();
    }

    static Context systemContext() {
        try {
            Method m = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
            return (Context) m.invoke(ACTIVITY_THREAD);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void fillAppInfo() {
        try {
            Class<?> bindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> ctor = bindDataClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object bindData = ctor.newInstance();

            ApplicationInfo info = new ApplicationInfo();
            info.packageName = ShellContext.PACKAGE_NAME;

            Field appInfo = bindDataClass.getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            appInfo.set(bindData, info);

            Field bound = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(ACTIVITY_THREAD, bindData);
        } catch (Throwable ignored) {
            // only a workaround; most devices work without it too
        }
    }

    private static void fillAppContext() {
        try {
            Application app = Instrumentation.newApplication(Application.class, ShellContext.get());
            Field initial = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
            initial.setAccessible(true);
            initial.set(ACTIVITY_THREAD, app);
        } catch (Throwable ignored) {
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ctor = controllerClass.getDeclaredConstructor(internalClass);
            ctor.setAccessible(true);
            Object controller = ctor.newInstance(ACTIVITY_THREAD);

            Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(ACTIVITY_THREAD, controller);
        } catch (Throwable ignored) {
        }
    }
}
