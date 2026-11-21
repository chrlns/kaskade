package pub.source.kaskade;

/**
 * Cost budget for a chain. A {@code null} budget anywhere in the API means
 * "no budget": every step runs and costs accumulate unchecked.
 *
 * @param maxCostUsd  maximum allowed accumulated cost in USD;
 *                    values {@code <= 0} or {@code NaN} mean unlimited
 * @param onExhausted what to do once the accumulated cost reaches the maximum
 */
public record BudgetSpec(double maxCostUsd, OnExhausted onExhausted) {

    public BudgetSpec {
        if (onExhausted == null) {
            onExhausted = OnExhausted.FAIL;
        }
    }

    /**
     * @return true if this budget imposes no cost limit
     */
    public boolean unlimited() {
        return !(maxCostUsd > 0.0);
    }
}
