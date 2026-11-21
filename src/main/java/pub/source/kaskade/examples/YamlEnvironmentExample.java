package pub.source.kaskade.examples;

import java.util.Map;

import pub.source.kaskade.ChainEngine;
import pub.source.kaskade.ChainResult;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Fixer;
import pub.source.kaskade.Validator;
import pub.source.kaskade.yaml.ChainYaml;
import pub.source.kaskade.yaml.FunctionResolver;

/**
 * Scenario 6 of {@code docs/scenarios.md}: the same code in every
 * environment, different chains per environment. Dev uses only the free
 * local model; prod adds the paid fallback and a budget. The chain lives in
 * YAML, the resolver registers domain-specific fixer and validator functions
 * under stable names, and the strict loader rejects unknown keys and unknown
 * function names at startup.
 *
 * <p>The YAML is inlined here so the example runs from a single file; in an
 * application the same strings would live in {@code config/dev.yaml} and
 * {@code config/prod.yaml}, loaded via
 * {@code ChainYaml.load(Path.of("config", env + ".yaml"), resolver())}.</p>
 */
public final class YamlEnvironmentExample {

    private YamlEnvironmentExample() {
    }

    /** Dev chain: only the free local model, no budget. */
    public static final String DEV_YAML = """
            name: extract-product
            steps:
              - model:
                  provider: ollama
                  model: llama3.2
                fixers: [extractProductNumber]
                validators: [isProductNumber]
            """;

    /** Prod chain: same repair step, plus paid fallback, retries and a budget. */
    public static final String PROD_YAML = """
            name: extract-product
            budget:
              maxCostUsd: 0.10
              onExhausted: FAIL
            steps:
              - model:
                  provider: ollama
                  model: llama3.2
                fixers: [extractProductNumber]
                validators: [isProductNumber]
                transportRetries: 1
              - model:
                  provider: cloud
                  model: gpt-5
                validators: [isProductNumber]
                paid: true
            """;

    /**
     * @return the environment-independent resolver: domain functions first,
     *         built-ins ({@code trim}, {@code isJson}, ...) as fallback
     */
    public static FunctionResolver resolver() {
        return FunctionResolver.of(
                Map.of("extractProductNumber",
                        (Fixer) response -> response.replaceAll(".*?(\\d{4,6}).*", "$1")),
                Map.of("isProductNumber",
                        (Validator) response -> response.matches("\\d{4,6}")))
                .withFallback(FunctionResolver.builtins());
    }

    /**
     * Loads a chain configuration with the domain resolver.
     *
     * @param yaml the YAML document
     * @return the parsed chain
     * @throws IllegalArgumentException on unknown keys or unknown function names
     */
    public static ChainSpec load(String yaml) {
        return ChainYaml.load(yaml, resolver());
    }

    /**
     * Runs both environment chains against the same scripted transport: the
     * local model answers with prose, the registered domain fixer extracts
     * the product number, and the validator accepts.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ChainSpec dev = load(DEV_YAML);
        ChainSpec prod = load(PROD_YAML);

        StubTransport transport = new StubTransport()
                .respond("llama3.2", "The product number is 12345, I believe.")
                .respond("gpt-5", "12345", 0.004);

        ChainResult devResult = new ChainEngine(transport).run(dev, "Extract the product number");
        ChainResult prodResult = new ChainEngine(transport).run(prod, "Extract the product number");

        System.out.println("dev has " + dev.steps().size() + " step(s): " + devResult);
        System.out.println("prod has " + prod.steps().size() + " step(s): " + prodResult);
    }
}
