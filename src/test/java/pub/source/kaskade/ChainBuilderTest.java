package pub.source.kaskade;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainBuilderTest {

    @Test
    void buildsSpecMatchingDirectConstruction() {
        Fixer trim = Fixers.trim();
        Validator isJson = Validators.isJson();
        ChainSpec spec = Chain.builder("demo")
                .budget(b -> b.maxCostUsd(0.5).onExhausted(OnExhausted.STOP_BEFORE_PAID))
                .step(s -> s.model("local", "llama3")
                        .param("temperature", "0.2")
                        .fixer(trim)
                        .validator(isJson)
                        .transportRetries(1)
                        .free())
                .step(s -> s.model("cloud", "big")
                        .paid()
                        .validator(isJson))
                .build();

        assertEquals(new ChainSpec("demo",
                new BudgetSpec(0.5, OnExhausted.STOP_BEFORE_PAID),
                List.of(
                        new StepSpec(new ModelRef("local", "llama3", Map.of("temperature", "0.2")),
                                List.of(trim), List.of(isJson), 1, false),
                        new StepSpec(new ModelRef("cloud", "big", Map.of()),
                                List.of(), List.of(isJson), 0, true))),
                spec);
    }

    @Test
    void defaultsAreNoBudgetAndAcceptingSteps() {
        ChainSpec spec = Chain.builder("minimal")
                .step(s -> s.model("p", "m"))
                .build();

        assertNull(spec.budget());
        StepSpec step = spec.steps().get(0);
        assertTrue(step.fixers().isEmpty());
        assertTrue(step.validators().isEmpty());
        assertEquals(0, step.transportRetries());
        assertFalse(step.paid());
    }

    @Test
    void budgetDefaultsToUnlimitedWithFail() {
        ChainSpec spec = Chain.builder("b")
                .budget(b -> { })
                .step(s -> s.model("p", "m"))
                .build();

        assertEquals(new BudgetSpec(0.0, OnExhausted.FAIL), spec.budget());
        assertTrue(spec.budget().unlimited());
    }

    @Test
    void stepWithoutModelFails() {
        assertThrows(IllegalArgumentException.class, () ->
                Chain.builder("broken").step(s -> { }).build());
    }

    @Test
    void blankNameFails() {
        assertThrows(IllegalArgumentException.class, () -> Chain.builder(" "));
    }

    @Test
    void paramBeforeModelFails() {
        assertThrows(IllegalStateException.class, () ->
                Chain.builder("x").step(s -> s.param("k", "v")));
    }

    @Test
    void negativeTransportRetriesFails() {
        assertThrows(IllegalArgumentException.class, () ->
                new StepSpec(new ModelRef("p", "m", Map.of()), List.of(), List.of(), -1, false));
    }
}
