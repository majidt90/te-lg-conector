package android.util;

/**
 * JVM stand-in for Android's Log, used only by tools/verify.py.
 *
 * The classes under test (log bus included) call android.util.Log; providing the
 * same signatures lets the real sources compile and run on a plain JVM so the
 * wire-format code can be verified without a device. This file is never part of
 * the APK - the Android platform supplies the real class there.
 */
public final class Log {

    public static int v(String tag, String message) {
        return 0;
    }

    public static int d(String tag, String message) {
        return 0;
    }

    public static int i(String tag, String message) {
        return 0;
    }

    public static int w(String tag, String message) {
        return 0;
    }

    public static int e(String tag, String message) {
        return 0;
    }

    public static int e(String tag, String message, Throwable error) {
        return 0;
    }

    public static String getStackTraceString(Throwable error) {
        return String.valueOf(error);
    }
}
