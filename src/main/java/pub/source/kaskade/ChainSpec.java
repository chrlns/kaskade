package pub.source.kaskade;

import java.util.List;

/**
 * Immutable description of an escalation chain: the single source of truth
 * produced by both frontends (the fluent Java API in {@link Chain} and the
 * YAML loader in {@code pub.source.kaskade.yaml}).
 *
 * @param name   human-readable chain name, never blank
 * @param budget cost budget, or {@code null} for no budget
 * @param steps  steps in escalation order; the first accepted response ends the chain
 */
public record ChainSpec(String name, BudgetSpec budget, List<StepSpec> steps) {

    public ChainSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
