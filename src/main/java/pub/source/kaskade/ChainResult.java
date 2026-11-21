package pub.source.kaskade;

/**
 * Outcome of a chain run.
 */
public sealed interface ChainResult {

    /**
     * @return the accumulated cost of all calls made during the run
     */
    double totalCostUsd();

    /**
     * A step accepted a response; the chain is done.
     *
     * @param content      the fixed, accepted response
     * @param model        the model that produced it
     * @param stepIndex    index of the accepting step in {@link ChainSpec#steps()}
     * @param totalCostUsd accumulated cost
     */
    record Accepted(String content, ModelRef model, int stepIndex, double totalCostUsd) implements ChainResult {
    }

    /**
     * All steps ran and every received response was rejected by its validators.
     *
     * @param content      the fixed content of the most recently received response
     * @param model        the model that produced it
     * @param stepIndex    index of that step in {@link ChainSpec#steps()}
     * @param totalCostUsd accumulated cost
     */
    record Rejected(String content, ModelRef model, int stepIndex, double totalCostUsd) implements ChainResult {
    }

    /**
     * The chain could not produce a response: empty step list, exhausted
     * budget under {@link OnExhausted#FAIL}, or transport failure on every step.
     *
     * @param reason       human-readable failure reason
     * @param totalCostUsd accumulated cost
     */
    record Failed(String reason, double totalCostUsd) implements ChainResult {
    }
}
