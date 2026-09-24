package com.lgmediabridge.core;

/** XML helpers for the UPnP device/service descriptions and DIDL-Lite documents. */
public final class Xml {

    private Xml() {
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    // Control characters are illegal in XML 1.0 even when escaped:
                    // drop them so a weird file name cannot break the DIDL document.
                    if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Serialises the common {@code "a=b;c=d"} UPnP form, escaping values. */
    public static String attributes(String... keyValues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(keyValues[i]).append('=').append(keyValues[i + 1]);
        }
        return sb.toString();
    }

    /** Extracts the text of the first {@code <name>…</name>} element. */
    public static String element(String xml, String name) {
        if (xml == null) {
            return null;
        }
        int start = xml.indexOf("<" + name);
        if (start < 0) {
            return null;
        }
        int open = xml.indexOf('>', start);
        if (open < 0) {
            return null;
        }
        int close = xml.indexOf("</" + name + ">", open);
        if (close < 0) {
            return null;
        }
        return unescape(xml.substring(open + 1, close));
    }

    /** Extracts an attribute value from the first occurrence of {@code <name …>}. */
    public static String attribute(String xml, String elementName, String attributeName) {
        if (xml == null) {
            return null;
        }
        int start = xml.indexOf("<" + elementName);
        if (start < 0) {
            return null;
        }
        int end = xml.indexOf('>', start);
        if (end < 0) {
            return null;
        }
        String header = xml.substring(start, end);
        String needle = attributeName + "=\"";
        int at = header.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int valueStart = at + needle.length();
        int valueEnd = header.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return null;
        }
        return unescape(header.substring(valueStart, valueEnd));
    }

    public static String unescape(String value) {
        if (value == null || value.indexOf('&') < 0) {
            return value;
        }
        return value.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    /** Trims a configId/updateId style numeric string. */
    public static int intOrDefault(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
