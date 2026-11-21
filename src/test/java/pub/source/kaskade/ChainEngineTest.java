package pub.source.kaskade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainEngineTest {

    private static final ModelRef A = new ModelRef("local", "model-a", Map.of());
    private static final ModelRef B = new ModelRef("cloud", "model-b", Map.of());
    private static final ModelRef C = new ModelRef("cloud", "model-c", Map.of());

    /**
     * Transport that remembers every call and delegates to a per-test script.
     */
    private static final class ScriptedTransport implements Transport {

        interface Call {
            TransportResult respond(ModelRef model, String prompt) throws Exception;
        }

        final List<String> calls = new ArrayList<>();
        private final Call script;

        ScriptedTransport(Call script) {
            this.script = script;
        }

        @Override
        public TransportResult call(ModelRef model, String prompt) throws Exception {
            calls.add(model.provider() + "/" + model.model());
            return script.respond(model, prompt);
        }
    }

    private static StepSpec step(ModelRef model, List<Fixer> fixers, List<Validator> validators) {
        return new StepSpec(model, fixers, validators, 0, false);
    }

    @Test
    void acceptsOnFirstStepAndReturnsFixedContent() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> TransportResult.free("  hello  "));
        ChainSpec spec = new ChainSpec("c", null, List.of(
                step(A, List.of(String::strip), List.of("hello"::equals))));

        ChainResult.Accepted accepted =
                assertInstanceOf(ChainResult.Accepted.class, new ChainEngine(transport).run(spec, "any prompt"));

        assertEquals("hello", accepted.content());
        assertEquals(A, accepted.model());
        assertEquals(0, accepted.stepIndex());
        assertEquals(0.0, accepted.totalCostUsd(), 1e-12);
        assertEquals(1, transport.calls.size());
    }

    @Test
    void stepWithoutValidatorsAcceptsAnything() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> TransportResult.free("anything"));
        ChainSpec spec = new ChainSpec("c", null, List.of(step(A, List.of(), List.of())));

        assertInstanceOf(ChainResult.Accepted.class, new ChainEngine(transport).run(spec, "p"));
    }

    @Test
    void escalatesToNextModelWhenValidationFails() {
        String fenced = "```json\n{\"a\":1}\n```";
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> TransportResult.free(fenced));
        ChainSpec spec = new ChainSpec("c", null, List.of(
                step(A, List.of(), List.of(Validators.isJson())),
                step(B, List.of(Fixers.stripCodeFence()), List.of(Validators.isJson()))));

        ChainResult.Accepted accepted =
                assertInstanceOf(ChainResult.Accepted.class, new ChainEngine(transport).run(spec, "p"));

        assertEquals(B, accepted.model());
        assertEquals(1, accepted.stepIndex());
        assertEquals("{\"a\":1}\n", accepted.content());
        assertEquals(List.of("local/model-a", "cloud/model-b"), transport.calls);
    }

    @Test
    void retriesTransportBeforeGivingUp() {
        AtomicInteger attempts = new AtomicInteger();
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new IOException("flaky");
            }
            return TransportResult.free("ok");
        });
        ChainSpec spec = new ChainSpec("c", null, List.of(
                new StepSpec(A, List.of(), List.of(), 2, false)));

        ChainResult.Accepted accepted =
                assertInstanceOf(ChainResult.Accepted.class, new ChainEngine(transport).run(spec, "p"));

        assertEquals(3, attempts.get()); // 1 attempt + 2 retries
        assertEquals("ok", accepted.content());
    }

    @Test
    void escalatesWhenTransportRetriesAreExhausted() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> {
            if (A.equals(model)) {
                throw new IOException("down");
            }
            return TransportResult.free("ok");
        });
        ChainSpec spec = new ChainSpec("c", null, List.of(
                new StepSpec(A, List.of(), List.of(), 1, false),
                step(B, List.of(), List.of())));

        assertInstanceOf(ChainResult.Accepted.class, new ChainEngine(transport).run(spec, "p"));
        assertEquals(3, transport.calls.size()); // 2 attempts on A, then 1 on B
    }

    @Test
    void rejectsWhenEveryStepFailsValidation() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> TransportResult.free("nope"));
        ChainSpec spec = new ChainSpec("c", null, List.of(
                step(A, List.of(), List.of(s -> false)),
                step(B, List.of(), List.of(s -> false))));

        ChainResult.Rejected rejected =
                assertInstanceOf(ChainResult.Rejected.class, new ChainEngine(transport).run(spec, "p"));

        assertEquals("nope", rejected.content());
        assertEquals(B, rejected.model());
        assertEquals(1, rejected.stepIndex());
        assertEquals(0.0, rejected.totalCostUsd(), 1e-12);
    }

    @Test
    void failsWhenEveryStepFailsAtTransport() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> {
            throw new IOException("down");
        });
        ChainSpec spec = new ChainSpec("c", null, List.of(
                new StepSpec(A, List.of(), List.of(), 1, false),
                step(B, List.of(), List.of())));

        ChainResult.Failed failed =
                assertInstanceOf(ChainResult.Failed.class, new ChainEngine(transport).run(spec, "p"));

        assertEquals(3, transport.calls.size());
        assertEquals(0.0, failed.totalCostUsd(), 1e-12);
    }

    @Test
    void failsOnEmptyChain() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> TransportResult.free("x"));
        ChainResult.Failed failed = assertInstanceOf(ChainResult.Failed.class,
                new ChainEngine(transport).run(new ChainSpec("c", null, List.of()), "p"));

        assertTrue(failed.reason().contains("no steps"));
        assertTrue(transport.calls.isEmpty());
    }

    @Test
    void escalatesWhenFixerThrows() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> TransportResult.free("whatever"));
        ChainSpec spec = new ChainSpec("c", null, List.of(
                step(A, List.of(s -> {
                    throw new IllegalStateException("bug");
                }), List.of(s -> true)),
                step(B, List.of(), List.of())));

        ChainResult.Accepted accepted =
                assertInstanceOf(ChainResult.Accepted.class, new ChainEngine(transport).run(spec, "p"));
        assertEquals(B, accepted.model());
    }

    @Test
    void escalatesWhenValidatorThrows() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> TransportResult.free("whatever"));
        ChainSpec spec = new ChainSpec("c", null, List.of(
                step(A, List.of(), List.of(s -> {
                    throw new IllegalStateException("bug");
                })),
                step(B, List.of(), List.of())));

        ChainResult.Accepted accepted =
                assertInstanceOf(ChainResult.Accepted.class, new ChainEngine(transport).run(spec, "p"));
        assertEquals(B, accepted.model());
    }

    @Test
    void budgetFailAbortsOnceExhausted() {
        ScriptedTransport transport =
                new ScriptedTransport((model, prompt) -> new TransportResult("nope", 0.06));
        ChainSpec spec = new ChainSpec("c", new BudgetSpec(0.10, OnExhausted.FAIL), List.of(
                step(A, List.of(), List.of(s -> false)),
                step(B, List.of(), List.of(s -> false)),
                step(C, List.of(), List.of(s -> false))));

        ChainResult.Failed failed =
                assertInstanceOf(ChainResult.Failed.class, new ChainEngine(transport).run(spec, "p"));

        assertEquals(0.12, failed.totalCostUsd(), 1e-12);
        assertEquals(2, transport.calls.size()); // third step never called
    }

    @Test
    void stopBeforePaidSkipsPaidStepsButRunsFreeOnes() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) ->
                C.equals(model) ? TransportResult.free("late") : new TransportResult("nope", 0.06));
        ChainSpec spec = new ChainSpec("c", new BudgetSpec(0.05, OnExhausted.STOP_BEFORE_PAID), List.of(
                new StepSpec(A, List.of(), List.of(s -> false), 0, true),
                new StepSpec(B, List.of(), List.of(s -> false), 0, true),
                step(C, List.of(), List.of(s -> false))));

        ChainResult.Rejected rejected =
                assertInstanceOf(ChainResult.Rejected.class, new ChainEngine(transport).run(spec, "p"));

        assertEquals(List.of("local/model-a", "cloud/model-c"), transport.calls);
        assertEquals("late", rejected.content());
        assertEquals(0.06, rejected.totalCostUsd(), 1e-12);
    }

    @Test
    void warnContinuesWithPaidStepsAfterExhaustion() {
        ScriptedTransport transport = new ScriptedTransport((model, prompt) ->
                B.equals(model) ? TransportResult.free("ok") : new TransportResult("nope", 0.06));
        ChainSpec spec = new ChainSpec("c", new BudgetSpec(0.05, OnExhausted.WARN), List.of(
                new StepSpec(A, List.of(), List.of(s -> false), 0, true),
                new StepSpec(B, List.of(), List.of("ok"::equals), 0, true)));

        ChainResult.Accepted accepted =
                assertInstanceOf(ChainResult.Accepted.class, new ChainEngine(transport).run(spec, "p"));

        assertEquals(B, accepted.model());
        assertEquals(0.06, accepted.totalCostUsd(), 1e-12);
    }

    @Test
    void deliversPromptToTransport() {
        StringBuilder seen = new StringBuilder();
        ScriptedTransport transport = new ScriptedTransport((model, prompt) -> {
            seen.append(prompt);
            return TransportResult.free("ok");
        });
        new ChainEngine(transport).run(
                new ChainSpec("c", null, List.of(step(A, List.of(), List.of()))), "hello world");
        assertEquals("hello world", seen.toString());
    }

    @Test
    void rejectsNullArguments() {
        ChainEngine engine = new ChainEngine((model, prompt) -> TransportResult.free("ok"));
        assertThrows(NullPointerException.class, () -> new ChainEngine(null));
        assertThrows(NullPointerException.class, () -> engine.run(null, "p"));
        assertThrows(NullPointerException.class, () -> engine.run(new ChainSpec("c", null, List.of()), null));
    }
}
