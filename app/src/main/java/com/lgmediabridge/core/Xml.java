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
                    // Control characters are illegal in XML 1.0 even when escaped,
                    // and so are unpaired surrogates (a truncated emoji in a file
                    // name). Dropping them keeps one odd media title from breaking
                    // the whole browse response, which the TV would show as an
                    // empty list.
                    if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                        break;
                    }
                    if (c == 0xFFFE || c == 0xFFFF) {
                        break;
                    }
                    if (Character.isHighSurrogate(c)) {
                        if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                            sb.append(c).append(value.charAt(i + 1));
                            i++;
                        }
                        break;
                    }
                    if (Character.isLowSurrogate(c)) {
                        break;
                    }
                    sb.append(c);
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

    /**
     * Index of the element whose name is exactly {@code name}.
     *
     * A plain {@code indexOf("<" + name)} also matches a longer element that
     * merely starts with it - asking for {@code <Result>} would find
     * {@code <ResultCode>} and return its digits plus the rest of the document.
     * The character after the name must therefore end the name: {@code >},
     * {@code /} (self-closing) or whitespace before attributes.
     */
    private static int findTag(String xml, String name) {
        int from = 0;
        while (true) {
            int at = xml.indexOf("<" + name, from);
            if (at < 0) {
                return -1;
            }
            int after = at + 1 + name.length();
            char next = after < xml.length() ? xml.charAt(after) : '>';
            if (next == '>' || next == '/' || Character.isWhitespace(next)) {
                return at;
            }
            from = at + 1;
        }
    }

    /** Extracts the text of the first {@code <name>…</name>} element. */
    public static String element(String xml, String name) {
        if (xml == null) {
            return null;
        }
        int start = findTag(xml, name);
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
        int start = findTag(xml, elementName);
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
