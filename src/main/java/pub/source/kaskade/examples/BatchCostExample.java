package pub.source.kaskade.examples;

import java.util.List;

import pub.source.kaskade.Chain;
import pub.source.kaskade.ChainEngine;
import pub.source.kaskade.ChainResult;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Fixers;
import pub.source.kaskade.OnExhausted;
import pub.source.kaskade.Validators;

/**
 * Scenario 2 of {@code docs/scenarios.md}: process a batch of prompts with a
 * hard per-item ceiling on provider cost, preferring the free model
 * everywhere possible. One spec, one engine, accounting per item; the batch
 * ceiling is the caller's loop, not the budget.
 */
public final class BatchCostExample {

    private BatchCostExample() {
    }

    /**
     * @return the documented batch-item chain: 0.02 USD per run, paid
     *         fallback skipped once an item has already cost that much
     */
    public static ChainSpec spec() {
        return Chain.builder("batch-item")
                .budget(b -> b.maxCostUsd(0.02).onExhausted(OnExhausted.STOP_BEFORE_PAID))
                .step(s -> s.model("local", "llama3.2")
                        .fixer(Fixers.trim())
                        .validator(Validators.nonEmpty()))
                .step(s -> s.model("cloud", "gpt-5-mini")
                        .fixer(Fixers.trim())
                        .validator(Validators.nonEmpty())
                        .paid())
                .build();
    }

    /**
     * Processes three items. The local model answers blank on the first item,
     * so that item escalates to the paid fallback; afterwards the local model
     * is healthy again.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "   ")                          // item 1: rejected, escalates
                .respond("llama3.2", "local answer")               // items 2 and 3: healthy
                .respond("gpt-5-mini", "cloud answer", 0.01);      // fallback for item 1

        ChainEngine engine = new ChainEngine(transport);
        double batchTotal = 0.0;
        int quarantined = 0;

        for (String prompt : List.of("item 1", "item 2", "item 3")) {
            ChainResult result = engine.run(spec(), prompt);
            batchTotal += result.totalCostUsd();
            switch (result) {
                case ChainResult.Accepted a -> System.out.println(prompt + " accepted by "
                        + a.model().model() + " for " + a.totalCostUsd() + " USD");
                case ChainResult.Rejected r -> {
                    quarantined++;
                    System.out.println(prompt + " quarantined: " + r.content());
                }
                case ChainResult.Failed f -> System.out.println(prompt + " skipped: " + f.reason());
            }
        }

        System.out.println("batch total: " + batchTotal + " USD, quarantined: " + quarantined);
    }
}
