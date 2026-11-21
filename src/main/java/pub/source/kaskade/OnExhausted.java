package pub.source.kaskade;

/**
 * Behavior of a chain once its cost budget is exhausted.
 */
public enum OnExhausted {

    /**
     * Abort the chain immediately with a {@code Failed} result.
     */
    FAIL,

    /**
     * Skip remaining paid steps; free steps keep running.
     */
    STOP_BEFORE_PAID,

    /**
     * Keep running all steps, including paid ones.
     */
    WARN
}
