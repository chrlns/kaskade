package pub.source.kaskade.yaml;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pub.source.kaskade.BudgetSpec;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Fixer;
import pub.source.kaskade.Fixers;
import pub.source.kaskade.ModelRef;
import pub.source.kaskade.OnExhausted;
import pub.source.kaskade.StepSpec;
import pub.source.kaskade.Validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainYamlTest {

    @Test
    void loadsCompleteDocument() {
        ChainSpec spec = ChainYaml.load("""
                name: demo
                budget:
                  maxCostUsd: 0.5
                  onExhausted: STOP_BEFORE_PAID
                steps:
                  - model:
                      provider: local
                      model: llama3
                      params:
                        temperature: 0.2
                    fixers: [trim, stripCodeFence]
                    validators: [nonEmpty]
                    transportRetries: 1
                    paid: false
                  - model:
                      provider: cloud
                      model: big
                    validators: [isJson]
                    paid: true
                """);

        assertEquals("demo", spec.name());
        assertEquals(new BudgetSpec(0.5, OnExhausted.STOP_BEFORE_PAID), spec.budget());
        assertEquals(2, spec.steps().size());

        StepSpec first = spec.steps().get(0);
        assertEquals(new ModelRef("local", "llama3", Map.of("temperature", "0.2")), first.model());
        assertEquals(2, first.fixers().size());
        assertEquals(1, first.validators().size());
        assertEquals(1, first.transportRetries());
        assertFalse(first.paid());

        StepSpec second = spec.steps().get(1);
        assertEquals(new ModelRef("cloud", "big", Map.of()), second.model());
        assertTrue(second.fixers().isEmpty());
        assertEquals(1, second.validators().size());
        assertTrue(second.paid());
    }

    @Test
    void appliesDocumentedDefaults() {
        ChainSpec spec = ChainYaml.load("""
                name: minimal
                steps:
                  - model:
                      provider: local
                      model: llama3
                """);

        assertNull(spec.budget());
        StepSpec step = spec.steps().get(0);
        assertTrue(step.fixers().isEmpty());
        assertTrue(step.validators().isEmpty());
        assertEquals(0, step.transportRetries());
        assertFalse(step.paid());
    }

    @Test
    void resolvesCustomFunctions() {
        Fixer customFixer = String::strip;
        Validator customValidator = s -> true;
        ChainSpec spec = ChainYaml.load("""
                name: custom
                steps:
                  - model:
                      provider: local
                      model: m
                    fixers: [shout]
                    validators: [always]
                """, FunctionResolver.of(Map.of("shout", customFixer), Map.of("always", customValidator)));

        assertSame(customFixer, spec.steps().get(0).fixers().get(0));
        assertSame(customValidator, spec.steps().get(0).validators().get(0));
    }

    @Test
    void composesResolversWithFallback() {
        Fixer custom = Fixers.trim();
        ChainSpec spec = ChainYaml.load("""
                name: mixed
                steps:
                  - model: {provider: local, model: m}
                    fixers: [customTrim, trim]
                    validators: [nonEmpty]
                """, FunctionResolver.of(Map.of("customTrim", custom), Map.of())
                .withFallback(FunctionResolver.builtins()));

        List<Fixer> fixers = spec.steps().get(0).fixers();
        assertEquals(2, fixers.size());
        assertSame(custom, fixers.get(0));
    }

    @Test
    void unknownFunctionNameFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                ChainYaml.load("""
                        name: broken
                        steps:
                          - model: {provider: p, model: m}
                            fixers: [noSuchFixer]
                        """));
        assertTrue(e.getMessage().contains("noSuchFixer"));
    }

    @Test
    void unknownKeysFailLoudly() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                ChainYaml.load("""
                        name: broken
                        steps:
                          - model: {provider: p, model: m}
                            validator: [nonEmpty]
                        """));
        assertTrue(e.getMessage().contains("validator"));
    }

    @Test
    void invalidOnExhaustedFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                ChainYaml.load("""
                        name: broken
                        budget:
                          maxCostUsd: 1.0
                          onExhausted: IGNORE
                        steps:
                          - model: {provider: p, model: m}
                        """));
        assertTrue(e.getMessage().contains("IGNORE"));
    }

    @Test
    void invalidDocumentsFail() {
        assertThrows(IllegalArgumentException.class, () -> ChainYaml.load("- just\n- a\n- list\n"));
        assertThrows(IllegalArgumentException.class, () -> ChainYaml.load(""));
        assertThrows(IllegalArgumentException.class, () -> ChainYaml.load("""
                steps:
                  - model: {provider: p, model: m}
                """));
        assertThrows(IllegalArgumentException.class, () -> ChainYaml.load("""
                name: x
                steps:
                  - {fixers: []}
                """));
    }
}
