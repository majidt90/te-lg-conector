package com.lgmediabridge;

/**
 * Minimal R stand-in for tools/verify.py.
 *
 * The harness compiles most app classes against the JDK rather than the full
 * Android build, so the aapt2-generated R class is not present. Only the members
 * the compiled classes actually reference are needed; the real R.java is
 * generated on every APK build, so a mismatch here can never reach the app - it
 * would simply fail this compile loudly.
 */
public final class R {

    public static final class string {
        public static final int time_never = 0;
        public static final int time_just_now = 0;
        public static final int time_minutes_ago = 0;
        public static final int time_hours_ago = 0;
        public static final int time_days_ago = 0;

        private string() {
        }
    }

    private R() {
    }
}
