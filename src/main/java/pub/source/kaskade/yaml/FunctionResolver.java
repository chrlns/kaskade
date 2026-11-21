package pub.source.kaskade.yaml;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

import pub.source.kaskade.Fixer;
import pub.source.kaskade.Fixers;
import pub.source.kaskade.Validator;
import pub.source.kaskade.Validators;

/**
 * Resolves the fixer and validator names used in YAML to implementations.
 *
 * <p>Implementations throw {@link NoSuchElementException} for unknown
 * names, which the loader converts into a descriptive
 * {@link IllegalArgumentException}.</p>
 */
public interface FunctionResolver {

    /**
     * @param name fixer name as written in YAML
     * @return the implementation, never null
     * @throws NoSuchElementException if the name is unknown
     */
    Fixer fixer(String name);

    /**
     * @param name validator name as written in YAML
     * @return the implementation, never null
     * @throws NoSuchElementException if the name is unknown
     */
    Validator validator(String name);

    /**
     * Built-in fixers: {@code trim}, {@code stripCodeFence}, {@code extractJson};
     * built-in validators: {@code nonEmpty}, {@code isJson}.
     *
     * @return a resolver for the built-in functions
     */
    static FunctionResolver builtins() {
        return of(
                Map.of(
                        "trim", Fixers.trim(),
                        "stripCodeFence", Fixers.stripCodeFence(),
                        "extractJson", Fixers.extractJson()),
                Map.of(
                        "nonEmpty", Validators.nonEmpty(),
                        "isJson", Validators.isJson()));
    }

    /**
     * Builds a resolver from explicit name-to-function maps.
     *
     * @param fixers     fixer implementations by YAML name
     * @param validators validator implementations by YAML name
     * @return a resolver backed by the given maps
     */
    static FunctionResolver of(Map<String, ? extends Fixer> fixers,
                               Map<String, ? extends Validator> validators) {
        Objects.requireNonNull(fixers, "fixers");
        Objects.requireNonNull(validators, "validators");
        return new FunctionResolver() {
            @Override
            public Fixer fixer(String name) {
                Fixer fixer = fixers.get(Objects.requireNonNull(name, "name"));
                if (fixer == null) {
                    throw new NoSuchElementException("unknown fixer '" + name + "'");
                }
                return fixer;
            }

            @Override
            public Validator validator(String name) {
                Validator validator = validators.get(Objects.requireNonNull(name, "name"));
                if (validator == null) {
                    throw new NoSuchElementException("unknown validator '" + name + "'");
                }
                return validator;
            }
        };
    }

    /**
     * Delegates to {@code fallback} when this resolver does not know a name.
     *
     * @param fallback consulted after this resolver
     * @return a composed resolver
     */
    default FunctionResolver withFallback(FunctionResolver fallback) {
        Objects.requireNonNull(fallback, "fallback");
        FunctionResolver primary = this;
        return new FunctionResolver() {
            @Override
            public Fixer fixer(String name) {
                try {
                    return primary.fixer(name);
                } catch (NoSuchElementException e) {
                    return fallback.fixer(name);
                }
            }

            @Override
            public Validator validator(String name) {
                try {
                    return primary.validator(name);
                } catch (NoSuchElementException e) {
                    return fallback.validator(name);
                }
            }
        };
    }
}
