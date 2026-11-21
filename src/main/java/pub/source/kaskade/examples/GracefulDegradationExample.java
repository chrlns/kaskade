package pub.source.kaskade.examples;

import pub.source.kaskade.Chain;
import pub.source.kaskade.ChainEngine;
import pub.source.kaskade.ChainResult;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Validators;

/**
 * Scenario 5 of {@code docs/scenarios.md}: survive a local model server that
 * occasionally refuses connections, without hammering it and without losing
 * the request. Two layers of defense: {@code transportRetries} on the local
 * step and an escalating fallback step, with the {@link WithBackoff}
 * decorator adding a pause between attempts.
 *
 * <p>The demo uses a 10 ms pause; production value would be one to two
 * seconds.</p>
 */
public final class GracefulDegradationExample {

    private GracefulDegradationExample() {
    }

    /**
     * @return the documented resilient chain
     */
    public static ChainSpec spec() {
        return Chain.builder("resilient")
                .step(s -> s.model("local", "llama3.2")
                        .validator(Validators.nonEmpty())
                        .transportRetries(2))
                .step(s -> s.model("cloud", "gpt-5")
                        .validator(Validators.nonEmpty())
                        .paid())
                .build();
    }

    /**
     * Runs the chain against a local server that refuses the first two
     * connections and is healthy on the third, demonstrating that the request
     * survives without escalating to the paid model.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        StubTransport flaky = new StubTransport()
                .fail("llama3.2", new IllegalStateException("connection refused"))
                .fail("llama3.2", new IllegalStateException("connection refused"))
                .respond("llama3.2", "local answer")
                .respond("gpt-5", "cloud answer", 0.01);    // never called in this run

        ChainEngine engine = new ChainEngine(new WithBackoff(flaky, 10));
        ChainResult result = engine.run(spec(), "Summarize this");

        System.out.println(result);
        System.out.println("transport calls: " + flaky.calledModels());
    }
}
