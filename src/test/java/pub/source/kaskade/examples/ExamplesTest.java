package pub.source.kaskade.examples;

import java.util.List;

import org.junit.jupiter.api.Test;

import pub.source.kaskade.Chain;
import pub.source.kaskade.ChainEngine;
import pub.source.kaskade.ChainResult;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Fixers;
import pub.source.kaskade.OnExhausted;
import pub.source.kaskade.Validators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the documented behavior of every example in this package against the
 * scripted {@link StubTransport}. This is the "unit-testing an entire ladder"
 * pattern from scenario 8 of {@code docs/scenarios.md}: no network, no
 * mocking framework, just a scripted transport and assertions on the sealed
 * {@link ChainResult}.
 */
class ExamplesTest {

    @Test
    void repairPipelineSavesProseWrappedJson() {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "Sure! ```json\n{\"a\": 1}\n```");

        ChainResult.Accepted accepted = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(transport).run(StructuredOutputExample.spec(), "prompt"));

        assertEquals("{\"a\": 1}", accepted.content());
        assertEquals(0, accepted.stepIndex());
        assertEquals(List.of("llama3.2"), transport.calledModels());
    }

    @Test
    void structuredOutputEscalatesWhenBeyondRepair() {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "```json\n{name: \"Ada\"}\n```")
                .respond("gpt-5", "{\"name\": \"Ada\"}", 0.002);

        ChainResult.Accepted accepted = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(transport).run(StructuredOutputExample.spec(), "prompt"));

        assertEquals(1, accepted.stepIndex());
        assertEquals(0.002, accepted.totalCostUsd(), 1e-12);
        assertEquals(List.of("llama3.2", "gpt-5"), transport.calledModels());
    }

    @Test
    void strictContractNormalizesTheAnswer() {
        StubTransport transport = new StubTransport()
                .respond("strong-sql", "SELECT id FROM t;\n", 0.01);

        ChainResult.Accepted accepted = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(transport).run(StrictContractExample.spec(), "prompt"));

        assertEquals("SELECT id FROM t", accepted.content());
    }

    @Test
    void labelNormalizationAcceptsProsaicAnswer() {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "Positive.");

        ChainResult.Accepted accepted = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(transport).run(LabelClassificationExample.spec(), "prompt"));

        assertEquals("positive", accepted.content());
        assertEquals(0, accepted.stepIndex());
        assertEquals(List.of("llama3.2"), transport.calledModels());
    }

    @Test
    void labelUnknownToBothModelsIsRejected() {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "Honestly, I have no idea.")
                .respond("gpt-5", "possibly maybe", 0.004);

        ChainResult.Rejected rejected = assertInstanceOf(ChainResult.Rejected.class,
                new ChainEngine(transport).run(LabelClassificationExample.spec(), "prompt"));

        assertEquals(1, rejected.stepIndex());
        assertEquals("possibly maybe", rejected.content());
    }

    @Test
    void batchEscalatesOnceThenStaysLocal() {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "   ")
                .respond("llama3.2", "local answer")
                .respond("gpt-5-mini", "cloud answer", 0.01);

        ChainEngine engine = new ChainEngine(transport);
        ChainResult firstItem = engine.run(BatchCostExample.spec(), "item 1");
        ChainResult secondItem = engine.run(BatchCostExample.spec(), "item 2");
        ChainResult thirdItem = engine.run(BatchCostExample.spec(), "item 3");

        assertEquals("cloud answer", assertInstanceOf(ChainResult.Accepted.class, firstItem).content());
        assertEquals("local answer", assertInstanceOf(ChainResult.Accepted.class, secondItem).content());
        assertEquals("local answer", assertInstanceOf(ChainResult.Accepted.class, thirdItem).content());
        assertEquals(0.01, firstItem.totalCostUsd() + secondItem.totalCostUsd() + thirdItem.totalCostUsd(), 1e-12);
        assertEquals(List.of("llama3.2", "gpt-5-mini", "llama3.2", "llama3.2"), transport.calledModels());
    }

    @Test
    void stopBeforePaidSkipsTheFallbackOnceTheBudgetIsSpent() {
        // A paid first step that answers blanks: 0.02 USD for nothing, which
        // exhausts the budget before the (also paid) fallback step runs.
        StubTransport transport = new StubTransport()
                .respond("paid-a", "   ", 0.02)
                .respond("paid-b", "would have answered", 0.02);

        ChainSpec spec = Chain.builder("skip-when-spent")
                .budget(b -> b.maxCostUsd(0.02).onExhausted(OnExhausted.STOP_BEFORE_PAID))
                .step(s -> s.model("cloud", "paid-a")
                        .fixer(Fixers.trim())
                        .validator(Validators.nonEmpty())
                        .paid())
                .step(s -> s.model("cloud", "paid-b")
                        .validator(Validators.nonEmpty())
                        .paid())
                .build();

        ChainResult.Rejected rejected = assertInstanceOf(ChainResult.Rejected.class,
                new ChainEngine(transport).run(spec, "prompt"));

        assertEquals(0.02, rejected.totalCostUsd(), 1e-12);
        assertEquals(List.of("paid-a"), transport.calledModels());
    }

    @Test
    void flakyLocalInfrastructureRecoversWithoutEscalation() {
        StubTransport flaky = new StubTransport()
                .fail("llama3.2", new IllegalStateException("connection refused"))
                .fail("llama3.2", new IllegalStateException("connection refused"))
                .respond("llama3.2", "local answer");

        ChainResult.Accepted accepted = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(new WithBackoff(flaky, 5)).run(GracefulDegradationExample.spec(), "prompt"));

        assertEquals("local answer", accepted.content());
        assertEquals(0, accepted.stepIndex());
        assertEquals(List.of("llama3.2", "llama3.2", "llama3.2"), flaky.calledModels());
    }

    @Test
    void warnBudgetNeverBlocksPaidSteps() {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "   ")
                .respond("gpt-5-a", "   ", 0.10)
                .respond("gpt-5-b", "final answer", 0.10);

        ChainResult.Accepted accepted = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(transport).run(CostMeasurementExample.spec(), "prompt"));

        // The budget was crossed before step 2, but WARN keeps it running and
        // the acceptance is never applied retroactively.
        assertEquals(2, accepted.stepIndex());
        assertEquals(0.20, accepted.totalCostUsd(), 1e-12);
    }

    @Test
    void yamlChainsLoadAndRun() {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "The product number is 12345, I believe.");

        ChainResult.Accepted dev = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(transport).run(
                        YamlEnvironmentExample.load(YamlEnvironmentExample.DEV_YAML), "prompt"));
        ChainResult.Accepted prod = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(transport).run(
                        YamlEnvironmentExample.load(YamlEnvironmentExample.PROD_YAML), "prompt"));

        assertEquals("12345", dev.content());
        assertEquals("12345", prod.content());
        assertEquals(List.of("llama3.2", "llama3.2"), transport.calledModels());
    }

    @Test
    void yamlWithUnknownFunctionNameFailsFast() {
        String yaml = """
                name: bad-chain
                steps:
                  - model:
                      provider: ollama
                      model: llama3.2
                    fixers: [noSuchFixer]
                """;

        assertThrows(IllegalArgumentException.class, () -> YamlEnvironmentExample.load(yaml));
    }

    @Test
    void unscriptedTransportFailsTheWholeChain() {
        ChainSpec oneStep = Chain.builder("one-step")
                .step(s -> s.model("local", "llama3.2"))
                .build();

        ChainResult.Failed failed = assertInstanceOf(ChainResult.Failed.class,
                new ChainEngine(new StubTransport()).run(oneStep, "prompt"));

        assertTrue(failed.reason().contains("failed at transport"));
    }
}
