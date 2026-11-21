package pub.source.kaskade;

/**
 * Decides whether a response (after fixers ran) is acceptable for the task.
 * A step accepts when every one of its validators accepts.
 *
 * <p>Contract: a validator that throws a {@code RuntimeException} counts as a
 * rejection and escalates the chain to the next step.</p>
 */
@FunctionalInterface
public interface Validator {

    /**
     * @param response the fixed response, never {@code null} when called by the engine
     * @return true if the response is acceptable
     */
    boolean test(String response);
}
