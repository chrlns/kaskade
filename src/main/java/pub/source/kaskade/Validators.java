package pub.source.kaskade;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ready-made {@link Validator}s for typical LLM output checks.
 */
public final class Validators {

    private Validators() {
    }

    /**
     * Accepts responses that contain at least one non-whitespace character.
     */
    public static Validator nonEmpty() {
        return response -> response != null && !response.isBlank();
    }

    /**
     * Accepts responses that contain the given literal.
     */
    public static Validator contains(String literal) {
        Objects.requireNonNull(literal, "literal");
        return response -> response != null && response.contains(literal);
    }

    /**
     * Accepts responses that fully match the given pattern.
     */
    public static Validator matches(Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        return response -> response != null && pattern.matcher(response).matches();
    }

    /**
     * Accepts responses of at most {@code maxLength} characters.
     */
    public static Validator maxLength(int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be >= 0, got " + maxLength);
        }
        return response -> response == null || response.length() <= maxLength;
    }

    /**
     * Accepts responses that form a single syntactically valid JSON document.
     * Implemented without third-party parsers; strict about leading zeros and
     * raw control characters inside strings.
     */
    public static Validator isJson() {
        return JsonSyntax::isValid;
    }
}
