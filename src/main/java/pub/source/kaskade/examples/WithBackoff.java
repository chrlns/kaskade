package pub.source.kaskade.examples;

import pub.source.kaskade.ModelRef;
import pub.source.kaskade.Transport;
import pub.source.kaskade.TransportResult;

/**
 * Transport decorator from scenario 5 of {@code docs/scenarios.md}: it pauses
 * once before letting a failure escalate. Unlike the engine's built-in
 * {@code transportRetries}, which retries immediately, this decorator owns a
 * polite backoff pause between its two attempts.
 *
 * <p>Combine it with per-step {@code transportRetries} for defense in depth,
 * or set the step's retries to 0 so the decorator owns all retrying.</p>
 */
public final class WithBackoff implements Transport {

    private final Transport delegate;
    private final long pauseMillis;

    /**
     * @param delegate    the wrapped transport, usually the real client
     * @param pauseMillis pause before the second attempt, in milliseconds
     */
    public WithBackoff(Transport delegate, long pauseMillis) {
        this.delegate = delegate;
        this.pauseMillis = pauseMillis;
    }

    @Override
    public TransportResult call(ModelRef model, String prompt) throws Exception {
        try {
            return delegate.call(model, prompt);
        } catch (Exception first) {
            Thread.sleep(pauseMillis);
            return delegate.call(model, prompt);   // second failure propagates to the engine
        }
    }
}
