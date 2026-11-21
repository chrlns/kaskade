package pub.source.kaskade;

/**
 * Strict JSON syntax check backing {@link Validators#isJson()}.
 * Hand-written (no third-party parser), package-private, with a nesting
 * limit so hostile input is rejected cleanly instead of overflowing the stack.
 */
final class JsonSyntax {

    private static final int MAX_DEPTH = 256;

    private final String s;
    private int i;

    private JsonSyntax(String s) {
        this.s = s;
    }

    static boolean isValid(String json) {
        if (json == null) {
            return false;
        }
        JsonSyntax parser = new JsonSyntax(json);
        parser.skipWhitespace();
        if (!parser.value(0)) {
            return false;
        }
        parser.skipWhitespace();
        return parser.i == parser.s.length();
    }

    private void skipWhitespace() {
        while (i < s.length() && isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private boolean value(int depth) {
        if (depth > MAX_DEPTH || i >= s.length()) {
            return false;
        }
        return switch (s.charAt(i)) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> number();
        };
    }

    private boolean object(int depth) {
        i++; // consume '{'
        skipWhitespace();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return true;
        }
        while (true) {
            if (i >= s.length() || s.charAt(i) != '"') {
                return false;
            }
            if (!string()) {
                return false;
            }
            skipWhitespace();
            if (i >= s.length() || s.charAt(i) != ':') {
                return false;
            }
            i++; // consume ':'
            skipWhitespace();
            if (!value(depth)) {
                return false;
            }
            skipWhitespace();
            if (i >= s.length()) {
                return false;
            }
            char c = s.charAt(i);
            if (c == ',') {
                i++;
                skipWhitespace();
                continue;
            }
            if (c == '}') {
                i++;
                return true;
            }
            return false;
        }
    }

    private boolean array(int depth) {
        i++; // consume '['
        skipWhitespace();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return true;
        }
        while (true) {
            if (!value(depth)) {
                return false;
            }
            skipWhitespace();
            if (i >= s.length()) {
                return false;
            }
            char c = s.charAt(i);
            if (c == ',') {
                i++;
                skipWhitespace();
                continue;
            }
            if (c == ']') {
                i++;
                return true;
            }
            return false;
        }
    }

    private boolean string() {
        i++; // consume opening '"'
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                i++;
                return true;
            }
            if (c < 0x20) {
                return false; // raw control characters are not allowed
            }
            if (c == '\\') {
                i++;
                if (i >= s.length()) {
                    return false;
                }
                char escaped = s.charAt(i);
                if (escaped == 'u') {
                    if (i + 4 >= s.length()) {
                        return false;
                    }
                    for (int k = 1; k <= 4; k++) {
                        if (!isHexDigit(s.charAt(i + k))) {
                            return false;
                        }
                    }
                    i += 4;
                } else if ("\"\\/bfnrt".indexOf(escaped) < 0) {
                    return false;
                }
            }
            i++;
        }
        return false;
    }

    private boolean number() {
        int start = i;
        if (i < s.length() && s.charAt(i) == '-') {
            i++;
        }
        int digitsStart = i;
        while (i < s.length() && isDigit(s.charAt(i))) {
            i++;
        }
        int digits = i - digitsStart;
        if (digits == 0) {
            i = start;
            return false;
        }
        if (digits > 1 && s.charAt(digitsStart) == '0') {
            return false; // no leading zeros
        }
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            int fracStart = i;
            while (i < s.length() && isDigit(s.charAt(i))) {
                i++;
            }
            if (i == fracStart) {
                return false;
            }
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                i++;
            }
            int expStart = i;
            while (i < s.length() && isDigit(s.charAt(i))) {
                i++;
            }
            if (i == expStart) {
                return false;
            }
        }
        return true;
    }

    private boolean literal(String word) {
        if (s.regionMatches(false, i, word, 0, word.length())) {
            i += word.length();
            return true;
        }
        return false;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
