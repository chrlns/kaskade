package pub.source.kaskade;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {

    @Test
    void modelRefDefaultsParamsAndValidates() {
        assertEquals(Map.of(), new ModelRef("p", "m", null).params());
        assertThrows(IllegalArgumentException.class, () -> new ModelRef("", "m", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ModelRef("p", " ", Map.of()));
    }

    @Test
    void chainSpecCopiesStepsAndDefaultsNullLists() {
        assertEquals(List.of(), new ChainSpec("c", null, null).steps());
        StepSpec step = new StepSpec(new ModelRef("p", "m", Map.of()), null, null, 0, false);
        assertTrue(step.fixers().isEmpty());
        assertTrue(step.validators().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new ChainSpec(" ", null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new StepSpec(null, List.of(), List.of(), 0, false));
    }

    @Test
    void budgetSpecDefaultsToFailAndKnowsUnlimited() {
        assertEquals(OnExhausted.FAIL, new BudgetSpec(0.0, null).onExhausted());
        assertTrue(new BudgetSpec(-1, OnExhausted.WARN).unlimited());
        assertTrue(new BudgetSpec(0.0, OnExhausted.FAIL).unlimited());
        assertTrue(new BudgetSpec(Double.NaN, OnExhausted.FAIL).unlimited());
        assertFalse(new BudgetSpec(1, OnExhausted.FAIL).unlimited());
    }

    @Test
    void transportResultValidatesAndHasFreeFactory() {
        assertEquals(0.0, TransportResult.free("x").costUsd(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> TransportResult.free(null));
        assertThrows(IllegalArgumentException.class, () -> new TransportResult("x", -0.1));
        assertThrows(IllegalArgumentException.class, () -> new TransportResult("x", Double.NaN));
    }
}
