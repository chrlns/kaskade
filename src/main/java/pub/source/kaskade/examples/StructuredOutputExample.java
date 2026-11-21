package pub.source.kaskade.examples;

import pub.source.kaskade.Chain;
import pub.source.kaskade.ChainEngine;
import pub.source.kaskade.ChainResult;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Fixers;
import pub.source.kaskade.OnExhausted;
import pub.source.kaskade.Validators;

/**
 * Scenario 1 of {@code docs/scenarios.md}: extract JSON with a cheap local
 * model, repair what can be repaired, and pay for the cloud model only when
 * the local output is beyond repair.
 *
 * <p>Repair pipeline on the cheap step, strict validator, compliant fallback
 * step. Fixer order matters: {@code stripCodeFence} must run before
 * {@code extractJson}.</p>
 */
public final class StructuredOutputExample {

    private StructuredOutputExample() {
    }

    /**
     * @return the documented extract-entities chain
     */
    public static ChainSpec spec() {
        return Chain.builder("extract-entities")
                .step(s -> s.model("ollama", "llama3.2")
                        .param("temperature", "0.1")
                        .fixer(Fixers.stripCodeFence())     // ```json ... ``` -> content
                        .fixer(Fixers.extractJson())        // strip prose around the object
                        .validator(Validators.isJson())
                        .transportRetries(1))
                .step(s -> s.model("cloud", "gpt-5")
                        .fixer(Fixers.trim())
                        .validator(Validators.isJson())
                        .paid())
                .budget(b -> b.maxCostUsd(0.50).onExhausted(OnExhausted.STOP_BEFORE_PAID))
                .build();
    }

    /**
     * Runs the chain against a local model that wraps broken JSON in a code
     * fence, so the run escalates to the paid fallback.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "```json\n{name: \"Ada Lovelace\"}\n```")  // beyond repair
                .respond("gpt-5", "{\"name\": \"Ada Lovelace\"}", 0.002);         // compliant fallback

        ChainResult result = new ChainEngine(transport).run(spec(),
                "Extract the person as JSON");

        System.out.println(result);
        System.out.println("calls: " + transport.calledModels() + ", accepted? "
                + (result instanceof ChainResult.Accepted));
    }
}
