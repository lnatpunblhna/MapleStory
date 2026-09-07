package handling.login.bridge;

import java.nio.charset.Charset;

/** Minimal JSON helpers for LoginBridge (Java 7, no external JSON lib). */
final class LoginBridgeJson {
    static final Charset UTF8 = Charset.forName("UTF-8");

    private LoginBridgeJson() {
    }

    static String escape(final String s) {
        if (s == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); ++i) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    static String stringField(final String body, final String key) {
        if (body == null) {
            return null;
        }
        final String needle = "\"" + key + "\"";
        int i = body.indexOf(needle);
        if (i < 0) {
            return null;
        }
        i = body.indexOf(':', i + needle.length());
        if (i < 0) {
            return null;
        }
        ++i;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            ++i;
        }
        if (i >= body.length() || body.charAt(i) != '"') {
            return null;
        }
        ++i;
        final StringBuilder sb = new StringBuilder();
        while (i < body.length()) {
            final char c = body.charAt(i++);
            if (c == '\\' && i < body.length()) {
                final char n = body.charAt(i++);
                if (n == 'n') {
                    sb.append('\n');
                } else if (n == 'r') {
                    sb.append('\r');
                } else if (n == 't') {
                    sb.append('\t');
                } else {
                    sb.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static Integer intField(final String body, final String key) {
        if (body == null) {
            return null;
        }
        final String needle = "\"" + key + "\"";
        int i = body.indexOf(needle);
        if (i < 0) {
            return null;
        }
        i = body.indexOf(':', i + needle.length());
        if (i < 0) {
            return null;
        }
        ++i;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            ++i;
        }
        int j = i;
        if (j < body.length() && body.charAt(j) == '-') {
            ++j;
        }
        while (j < body.length() && Character.isDigit(body.charAt(j))) {
            ++j;
        }
        if (j == i || (j == i + 1 && body.charAt(i) == '-')) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(body.substring(i, j)));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
