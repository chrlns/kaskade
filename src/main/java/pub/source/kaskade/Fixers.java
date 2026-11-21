package pub.source.kaskade;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Ready-made {@link Fixer}s for typical LLM output repair.
 */
public final class Fixers {

    private Fixers() {
    }

    /**
     * Strips leading and trailing whitespace.
     */
    public static Fixer trim() {
        return String::strip;
    }

    /**
     * Removes a single surrounding Markdown code fence such as
     * {@code ```json ... ```}, if present. Content without a fence is
     * returned unchanged.
     */
    public static Fixer stripCodeFence() {
        return response -> {
            String s = response.strip();
            if (!s.startsWith("```")) {
                return response;
            }
            int firstNewline = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNewline < 0 || lastFence <= firstNewline) {
                return response;
            }
            return s.substring(firstNewline + 1, lastFence);
        };
    }

    /**
     * Extracts the substring from the first {@code '{'} to the last
     * {@code '}'}, dropping surrounding prose. Returns the input unchanged
     * if no object is found.
     */
    public static Fixer extractJson() {
        return response -> {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start < 0 || end < start) {
                return response;
            }
            return response.substring(start, end + 1);
        };
    }

    /**
     * Replaces every match of {@code pattern} with {@code replacement},
     * as per {@link java.util.regex.Matcher#replaceAll(String)}.
     */
    public static Fixer regexReplace(Pattern pattern, String replacement) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(replacement, "replacement");
        return response -> pattern.matcher(response).replaceAll(replacement);
    }
}
