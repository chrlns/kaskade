package pub.source.kaskade;

/**
 * Attempts to repair a raw model response before it is validated.
 * Fixers run in list order; each one receives the output of the previous one.
 *
 * <p>Contract: implementations must not return {@code null}. A fixer that
 * throws a {@code RuntimeException} marks its step as failed and escalates
 * the chain to the next step.</p>
 */
@FunctionalInterface
public interface Fixer {

    /**
     * @param response the raw or previously fixed response, never {@code null} when called by the engine
     * @return the repaired response, never {@code null}
     */
    String fix(String response);
}
