package pub.source.kaskade;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;

/**
 * Executes a {@link ChainSpec}.
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>Steps run in order. Fixers repair the raw response, then the validators
 *   decide acceptance; the first accepted response ends the chain as
 *   {@link ChainResult.Accepted}. A step without validators accepts any response.</li>
 *   <li>Transport exceptions are retried {@code transportRetries} additional
 *   times, then the chain escalates to the next step. A fixer or validator that
 *   throws also escalates.</li>
 *   <li>If every step ran without an acceptance, the run ends as
 *   {@link ChainResult.Rejected} with the most recently received content,
 *   or as {@link ChainResult.Failed} when no step ever reached the transport.</li>
 *   <li>The budget is checked before each step: once the accumulated cost has
 *   reached {@code maxCostUsd}, {@link OnExhausted} decides whether the chain
 *   fails ({@code FAIL}), paid steps are skipped ({@code STOP_BEFORE_PAID}),
 *   or the chain continues with a warning ({@code WARN}). Budgets are never
 *   applied retroactively: a response accepted in the current call is returned
 *   even if it pushed the total over the limit.</li>
 * </ul>
 */
public final class ChainEngine {

    private static final Logger LOG = System.getLogger(ChainEngine.class.getName());

    private final Transport transport;

    /**
     * @param transport delivers prompts to models; implemented by the library user
     */
    public ChainEngine(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * Runs the chain against the given prompt.
     *
     * @param spec   the chain to run
     * @param prompt the user prompt
     * @return the outcome, never null
     */
    public ChainResult run(ChainSpec spec, String prompt) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(prompt, "prompt");

        List<StepSpec> steps = spec.steps();
        if (steps.isEmpty()) {
            return new ChainResult.Failed("chain '" + spec.name() + "' has no steps", 0.0);
        }

        double total = 0.0;
        String lastContent = null;
        ModelRef lastModel = null;
        int lastIndex = -1;

        for (int i = 0; i < steps.size(); i++) {
            StepSpec step = steps.get(i);
            BudgetSpec budget = spec.budget();

            if (isExhausted(budget, total)) {
                switch (budget.onExhausted()) {
                    case FAIL -> {
                        return new ChainResult.Failed(
                                "budget of " + budget.maxCostUsd() + " USD exhausted; spent " + total + " USD",
                                total);
                    }
                    case STOP_BEFORE_PAID -> {
                        if (step.paid()) {
                            LOG.log(Level.WARNING, "budget exhausted; skipping paid step {0} ({1})", new Object[]{i, step.model().model()});
                            continue;
                        }
                    }
                    case WARN -> LOG.log(Level.WARNING, "budget exhausted; spent {0} of {1} USD; continuing", new Object[]{total, budget.maxCostUsd()});
                }
            }

            TransportResult result = callWithRetries(step, prompt);
            if (result == null) {
                continue; // transport failed after retries: escalate to the next step
            }
            total += result.costUsd();

            String content = fix(step, result.content());
            if (content != null) {
                lastContent = content;
                lastModel = step.model();
                lastIndex = i;
                if (accepts(step, content)) {
                    return new ChainResult.Accepted(content, step.model(), i, total);
                }
            }
        }

        return lastContent != null
                ? new ChainResult.Rejected(lastContent, lastModel, lastIndex, total)
                : new ChainResult.Failed("all " + steps.size() + " step(s) failed at transport", total);
    }

    private TransportResult callWithRetries(StepSpec step, String prompt) {
        int attempts = step.transportRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return transport.call(step.model(), prompt);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "transport attempt {0}/{1} failed for {2}", new Object[]{attempt, attempts, step.model()});
            }
        }
        return null;
    }

    private String fix(StepSpec step, String content) {
        for (Fixer fixer : step.fixers()) {
            try {
                content = fixer.fix(content);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "fixer failed for " + step.model(), e);
                return null;
            }
        }
        return content;
    }

    private boolean accepts(StepSpec step, String content) {
        for (Validator validator : step.validators()) {
            boolean ok;
            try {
                ok = validator.test(content);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "validator failed for " + step.model(), e);
                ok = false;
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExhausted(BudgetSpec budget, double total) {
        return budget != null && !budget.unlimited() && total >= budget.maxCostUsd();
    }
}
