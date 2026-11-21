package pub.source.kaskade.examples;

import java.util.List;

import pub.source.kaskade.Chain;
import pub.source.kaskade.ChainEngine;
import pub.source.kaskade.ChainResult;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.OnExhausted;
import pub.source.kaskade.Validators;

/**
 * Scenario 7 of {@code docs/scenarios.md}: measure costs before enforcing
 * them. A {@code WARN} budget keeps every step running, paid ones included,
 * and logs one warning per run that crosses the limit, so the log itself is
 * the cost profile. Once the numbers are known, switch the policy to
 * {@code STOP_BEFORE_PAID} or {@code FAIL}.
 *
 * <p>Compared to the two-step version in the docs, this spec has a second
 * paid step so that the exhaustion warning fires visibly <em>inside</em> one
 * run - and so that the run still finishes, because budgets are never
 * applied retroactively.</p>
 */
public final class CostMeasurementExample {

    private CostMeasurementExample() {
    }

    /**
     * @return the cost-probe chain: unlimited in practice, 0.10 USD warn level
     */
    public static ChainSpec spec() {
        return Chain.builder("cost-probe")
                .budget(b -> b.maxCostUsd(0.10).onExhausted(OnExhausted.WARN))
                .step(s -> s.model("local", "llama3.2").validator(Validators.nonEmpty()))
                .step(s -> s.model("cloud", "gpt-5-a").validator(Validators.nonEmpty()).paid())
                .step(s -> s.model("cloud", "gpt-5-b").validator(Validators.nonEmpty()).paid())
                .build();
    }

    /**
     * Runs two prompts through the probe. Every run accepts on the last paid
     * step for 0.20 USD, crossing the 0.10 USD warn level once per run and
     * logging a warning instead of blocking.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "   ")                    // free, rejected: escalate
                .respond("gpt-5-a", "   ", 0.10)               // paid, rejected: escalate again
                .respond("gpt-5-b", "final answer", 0.10);     // accepted at 0.20 USD total

        ChainEngine engine = new ChainEngine(transport);
        double totalCost = 0.0;
        long paidRuns = 0;

        for (String prompt : List.of("prompt 1", "prompt 2")) {
            ChainResult result = engine.run(spec(), prompt);
            totalCost += result.totalCostUsd();
            if (result instanceof ChainResult.Accepted a && a.model().provider().equals("cloud")) {
                paidRuns++;
            }
            System.out.println(result);
        }

        System.out.println("measured: " + totalCost + " USD total, " + paidRuns + " paid run(s)");
        System.out.println("decision: a STOP_BEFORE_PAID budget of 0.10 USD would have "
                + "turned each 0.20 USD run into a free local rejection");
    }
}
