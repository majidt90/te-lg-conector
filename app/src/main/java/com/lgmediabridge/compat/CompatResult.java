package com.lgmediabridge.compat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The verdict for one file against the target TV's documented capabilities. */
public final class CompatResult {

    public enum Verdict {
        /** Streamed untouched: matches webOS 6 documented support. */
        DIRECT,
        /** Image is re-encoded to JPEG on the fly (HEIC/HEIF/AVIF/oversized). */
        PHOTO_CONVERT,
        /** Audio is decoded and re-encoded to AAC once, then cached and seekable. */
        AUDIO_CONVERT,
        /** Neither direct play nor a supported conversion path exists. */
        UNSUPPORTED;

        public boolean playable() {
            return this != UNSUPPORTED;
        }
    }

    public final Verdict verdict;
    public final List<String> reasons;
    public final Map<String, String> details;
    /** True when the file's tracks were actually read on this device. */
    public final boolean inspected;

    public CompatResult(Verdict verdict, List<String> reasons, Map<String, String> details, boolean inspected) {
        this.verdict = verdict;
        this.reasons = Collections.unmodifiableList(reasons);
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        this.inspected = inspected;
    }

    public String reasonText() {
        if (reasons.isEmpty()) {
            return verdict == Verdict.DIRECT
                    ? "Matches the TV's documented playback support."
                    : "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reasons.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("• ").append(reasons.get(i));
        }
        return sb.toString();
    }
}
