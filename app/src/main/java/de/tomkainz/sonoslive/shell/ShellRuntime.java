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
 * Stellt fuer einen per app_process gestarteten Prozess das Minimum an
 * Android-Laufzeit her, das AudioRecord erwartet: einen ActivityThread, einen
 * System-Context und eine Application, die sich als com.android.shell ausgibt.
 *
 * Vorgehen und Reihenfolge nach scrcpy (Workarounds.java, Apache-2.0), auf den
 * Audio-Teil reduziert.
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
            // Muss vor dem Application-Context stehen, sonst gibt es auf manchen
            // Samsung-Geraeten keinen gueltigen System-Context.
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
            // nur ein Workaround; ohne ihn geht es auf den meisten Geraeten auch
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
