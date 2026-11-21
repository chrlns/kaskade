package pub.source.kaskade;

/**
 * Raw response of a single model call.
 *
 * @param content the raw model response, never null
 * @param costUsd what this call cost in USD; 0.0 for free models
 */
public record TransportResult(String content, double costUsd) {

    public TransportResult {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (Double.isNaN(costUsd) || costUsd < 0.0) {
            throw new IllegalArgumentException("costUsd must be >= 0, got " + costUsd);
        }
    }

    /**
     * @param content the raw model response
     * @return a zero-cost result for free models
     */
    public static TransportResult free(String content) {
        return new TransportResult(content, 0.0);
    }
}
