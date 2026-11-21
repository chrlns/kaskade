package pub.source.kaskade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Fluent frontend producing a {@link ChainSpec}.
 *
 * <pre>{@code
 * ChainSpec spec = Chain.builder("my-chain")
 *     .budget(b -> b.maxCostUsd(0.50).onExhausted(OnExhausted.STOP_BEFORE_PAID))
 *     .step(s -> s.model("ollama", "llama3.2")
 *                 .param("temperature", "0.2")
 *                 .fixer(Fixers.stripCodeFence())
 *                 .validator(Validators.isJson())
 *                 .transportRetries(1))
 *     .step(s -> s.model("cloud", "big-model").paid().validator(Validators.isJson()))
 *     .build();
 * }</pre>
 */
public final class Chain {

    private Chain() {
    }

    /**
     * @param name chain name, never blank
     * @return a new builder
     * @throws IllegalArgumentException if the name is null or blank
     */
    public static Builder builder(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        return new Builder(name);
    }

    /**
     * Builds the {@link ChainSpec} for one chain.
     */
    public static final class Builder {

        private final String name;
        private BudgetSpec budget;
        private final List<StepSpec> steps = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        /**
         * Configures the cost budget. Optional; no budget means unlimited.
         */
        public Builder budget(Consumer<BudgetBuilder> config) {
            Objects.requireNonNull(config, "config");
            BudgetBuilder builder = new BudgetBuilder();
            config.accept(builder);
            this.budget = builder.build();
            return this;
        }

        /**
         * Adds one escalation step. The first added step is tried first.
         */
        public Builder step(Consumer<StepBuilder> config) {
            Objects.requireNonNull(config, "config");
            StepBuilder builder = new StepBuilder();
            config.accept(builder);
            steps.add(builder.build());
            return this;
        }

        /**
         * @return the immutable spec
         */
        public ChainSpec build() {
            return new ChainSpec(name, budget, List.copyOf(steps));
        }
    }

    /**
     * Builds a {@link BudgetSpec}. Defaults: unlimited cost, {@link OnExhausted#FAIL}.
     */
    public static final class BudgetBuilder {

        private double maxCostUsd = 0.0;
        private OnExhausted onExhausted = OnExhausted.FAIL;

        /**
         * @param maxCostUsd maximum accumulated cost in USD; {@code <= 0} means unlimited
         */
        public BudgetBuilder maxCostUsd(double maxCostUsd) {
            this.maxCostUsd = maxCostUsd;
            return this;
        }

        public BudgetBuilder onExhausted(OnExhausted onExhausted) {
            this.onExhausted = Objects.requireNonNull(onExhausted, "onExhausted");
            return this;
        }

        BudgetSpec build() {
            return new BudgetSpec(maxCostUsd, onExhausted);
        }
    }

    /**
     * Builds one {@link StepSpec}. Defaults: no fixers, no validators
     * (accept anything), no transport retries, free model.
     */
    public static final class StepBuilder {

        private ModelRef model;
        private final List<Fixer> fixers = new ArrayList<>();
        private final List<Validator> validators = new ArrayList<>();
        private int transportRetries;
        private boolean paid;

        /**
         * Selects the model to call.
         *
         * @param provider transport-side provider id, e.g. {@code "ollama"}
         * @param model    provider-side model id, e.g. {@code "llama3.2"}
         */
        public StepBuilder model(String provider, String model) {
            return model(new ModelRef(provider, model, Map.of()));
        }

        /**
         * Selects the model to call, e.g. one loaded from config.
         */
        public StepBuilder model(ModelRef model) {
            this.model = Objects.requireNonNull(model, "model");
            return this;
        }

        /**
         * Sets or overwrites one provider parameter (requires a previously set model).
         */
        public StepBuilder param(String key, String value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            if (model == null) {
                throw new IllegalStateException("model(...) must be set before param(...)");
            }
            Map<String, String> params = new HashMap<>(model.params());
            params.put(key, value);
            model = new ModelRef(model.provider(), model.model(), params);
            return this;
        }

        /**
         * Appends one fixer to the repair pipeline.
         */
        public StepBuilder fixer(Fixer fixer) {
            fixers.add(Objects.requireNonNull(fixer, "fixer"));
            return this;
        }

        /**
         * Appends one acceptance rule.
         */
        public StepBuilder validator(Validator validator) {
            validators.add(Objects.requireNonNull(validator, "validator"));
            return this;
        }

        /**
         * Number of additional transport attempts after a failed call.
         */
        public StepBuilder transportRetries(int transportRetries) {
            this.transportRetries = transportRetries;
            return this;
        }

        /**
         * Marks this model as paid (budget-relevant).
         */
        public StepBuilder paid() {
            this.paid = true;
            return this;
        }

        /**
         * Marks this model as free (the default).
         */
        public StepBuilder free() {
            this.paid = false;
            return this;
        }

        StepSpec build() {
            return new StepSpec(model, fixers, validators, transportRetries, paid);
        }
    }
}
