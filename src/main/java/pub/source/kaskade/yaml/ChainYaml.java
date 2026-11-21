package pub.source.kaskade.yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import pub.source.kaskade.BudgetSpec;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Fixer;
import pub.source.kaskade.ModelRef;
import pub.source.kaskade.OnExhausted;
import pub.source.kaskade.StepSpec;
import pub.source.kaskade.Validator;

/**
 * YAML frontend producing a {@link ChainSpec}.
 *
 * <pre>
 * name: my-chain
 * budget:
 *   maxCostUsd: 0.5
 *   onExhausted: STOP_BEFORE_PAID
 * steps:
 *   - model:
 *       provider: ollama
 *       model: llama3.2
 *       params:
 *         temperature: "0.2"
 *     fixers: [stripCodeFence]
 *     validators: [isJson]
 *     transportRetries: 1
 *     paid: false
 *   - model:
 *       provider: cloud
 *       model: big-model
 *     validators: [isJson]
 *     paid: true
 * </pre>
 *
 * <p>{@code budget} is optional (no budget by default). Within a step,
 * {@code model} is required; {@code fixers}, {@code validators},
 * {@code transportRetries} and {@code paid} default to empty, empty, 0 and
 * false. Unknown keys are rejected so typos fail loudly instead of being
 * silently ignored.</p>
 */
public final class ChainYaml {

    private static final Set<String> ROOT_KEYS = Set.of("name", "budget", "steps");
    private static final Set<String> BUDGET_KEYS = Set.of("maxCostUsd", "onExhausted");
    private static final Set<String> STEP_KEYS =
            Set.of("model", "fixers", "validators", "transportRetries", "paid");
    private static final Set<String> MODEL_KEYS = Set.of("provider", "model", "params");

    private ChainYaml() {
    }

    /**
     * Loads a chain from YAML using only the built-in fixers and validators.
     *
     * @param yaml the YAML document
     * @return the parsed spec
     * @throws IllegalArgumentException on invalid documents or unknown names
     */
    public static ChainSpec load(String yaml) {
        return load(yaml, FunctionResolver.builtins());
    }

    /**
     * Loads a chain from YAML.
     *
     * @param yaml     the YAML document
     * @param resolver resolves fixer and validator names to implementations
     * @return the parsed spec
     * @throws IllegalArgumentException on invalid documents or unknown names
     */
    public static ChainSpec load(String yaml, FunctionResolver resolver) {
        Objects.requireNonNull(yaml, "yaml");
        Objects.requireNonNull(resolver, "resolver");

        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid YAML: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new IllegalArgumentException("empty YAML document");
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException("document root must be a mapping, got " + typeName(root));
        }
        checkKeys(rootMap, ROOT_KEYS, "document root");

        String name = requireString(rootMap, "name", "document root");
        BudgetSpec budget = rootMap.containsKey("budget")
                ? budget(asMap(rootMap.get("budget"), "budget"))
                : null;

        List<StepSpec> steps = new ArrayList<>();
        if (rootMap.containsKey("steps")) {
            List<?> stepList = asList(rootMap.get("steps"), "steps");
            for (int i = 0; i < stepList.size(); i++) {
                steps.add(step(stepList.get(i), i, resolver));
            }
        }
        return new ChainSpec(name, budget, steps);
    }

    /**
     * Loads a chain from a YAML file.
     *
     * @param path     file containing the YAML document
     * @param resolver resolves fixer and validator names to implementations
     * @return the parsed spec
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException on invalid documents or unknown names
     */
    public static ChainSpec load(Path path, FunctionResolver resolver) throws IOException {
        Objects.requireNonNull(path, "path");
        return load(Files.readString(path), resolver);
    }

    private static BudgetSpec budget(Map<?, ?> map) {
        checkKeys(map, BUDGET_KEYS, "budget");
        double maxCostUsd = 0.0;
        if (map.containsKey("maxCostUsd")) {
            Object value = map.get("maxCostUsd");
            if (!(value instanceof Number number) || Double.isNaN(number.doubleValue())) {
                throw new IllegalArgumentException(
                        "budget.maxCostUsd must be a number, got " + typeName(value));
            }
            maxCostUsd = number.doubleValue();
        }
        OnExhausted onExhausted = OnExhausted.FAIL;
        if (map.containsKey("onExhausted")) {
            String raw = String.valueOf(map.get("onExhausted")).trim();
            try {
                onExhausted = OnExhausted.valueOf(raw);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("budget.onExhausted must be one of "
                        + Arrays.toString(OnExhausted.values()) + ", got '" + raw + "'");
            }
        }
        return new BudgetSpec(maxCostUsd, onExhausted);
    }

    private static StepSpec step(Object element, int index, FunctionResolver resolver) {
        String ctx = "steps[" + index + "]";
        Map<?, ?> map = asMap(element, ctx);
        checkKeys(map, STEP_KEYS, ctx);
        if (!map.containsKey("model")) {
            throw new IllegalArgumentException(ctx + " is missing required key 'model'");
        }
        ModelRef model = model(asMap(map.get("model"), ctx + ".model"), ctx + ".model");
        List<Fixer> fixers = functions(map.get("fixers"), ctx + ".fixers", "fixer", resolver::fixer);
        List<Validator> validators =
                functions(map.get("validators"), ctx + ".validators", "validator", resolver::validator);
        int transportRetries = intOpt(map, "transportRetries", ctx, 0);
        boolean paid = boolOpt(map, "paid", ctx, false);
        return new StepSpec(model, fixers, validators, transportRetries, paid);
    }

    private static ModelRef model(Map<?, ?> map, String ctx) {
        checkKeys(map, MODEL_KEYS, ctx);
        String provider = requireString(map, "provider", ctx);
        String model = requireString(map, "model", ctx);
        Map<String, String> params = new HashMap<>();
        if (map.containsKey("params")) {
            Map<?, ?> paramsMap = asMap(map.get("params"), ctx + ".params");
            for (Map.Entry<?, ?> entry : paramsMap.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException(ctx + ".params." + entry.getKey() + " must not be null");
                }
                params.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return new ModelRef(provider, model, params);
    }

    private static <T> List<T> functions(Object value, String ctx, String kind, Function<String, T> lookup) {
        if (value == null) {
            return List.of();
        }
        List<?> names = asList(value, ctx);
        List<T> result = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String name = String.valueOf(names.get(i));
            T function;
            try {
                function = lookup.apply(name);
            } catch (NoSuchElementException e) {
                throw new IllegalArgumentException(
                        "unknown " + kind + " '" + name + "' in " + ctx + "[" + i + "]", e);
            }
            result.add(function);
        }
        return List.copyOf(result);
    }

    private static int intOpt(Map<?, ?> map, String key, String ctx, int fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number && number.intValue() == number.doubleValue() && number.intValue() >= 0) {
            return number.intValue();
        }
        throw new IllegalArgumentException(ctx + "." + key + " must be a non-negative integer, got " + typeName(value));
    }

    private static boolean boolOpt(Map<?, ?> map, String key, String ctx, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(ctx + "." + key + " must be true or false, got " + typeName(value));
    }

    private static void checkKeys(Map<?, ?> map, Set<String> allowed, String ctx) {
        for (Object key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException(
                        "unexpected key '" + key + "' in " + ctx + "; allowed keys: " + String.join(", ", allowed));
            }
        }
    }

    private static String requireString(Map<?, ?> map, String key, String ctx) {
        if (!map.containsKey(key)) {
            throw new IllegalArgumentException(ctx + " is missing required key '" + key + "'");
        }
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(
                    ctx + "." + key + " must be a non-blank string, got " + typeName(value));
        }
        return s;
    }

    private static Map<?, ?> asMap(Object value, String ctx) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(ctx + " must be a mapping, got " + typeName(value));
        }
        return map;
    }

    private static List<?> asList(Object value, String ctx) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(ctx + " must be a list, got " + typeName(value));
        }
        return list;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
