package pub.source.kaskade;

import java.util.Map;

/**
 * Reference to an LLM, as resolved by the user-supplied {@link Transport}.
 *
 * @param provider transport-side provider identifier, e.g. {@code "ollama"}, never blank
 * @param model    provider-side model identifier, e.g. {@code "llama3.2"}, never blank
 * @param params   provider-specific call parameters, e.g. {@code temperature -> 0.2}, never null
 */
public record ModelRef(String provider, String model, Map<String, String> params) {

    public ModelRef {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be null or blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be null or blank");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}
