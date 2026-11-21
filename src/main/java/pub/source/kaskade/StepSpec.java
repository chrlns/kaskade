package pub.source.kaskade;

import java.util.List;

/**
 * One escalation step: the model to call, the repair pipeline, the acceptance
 * rules and transport-level retry behavior.
 *
 * @param model            the model to call, never null
 * @param fixers           repair functions applied to the raw response, in order, before validation
 * @param validators       acceptance rules; a response is accepted if every validator accepts it
 * @param transportRetries additional transport attempts after a failed call; 0 means a single attempt
 * @param paid             whether calls to this model cost money (relevant to budget handling)
 */
public record StepSpec(ModelRef model, List<Fixer> fixers, List<Validator> validators,
                       int transportRetries, boolean paid) {

    public StepSpec {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        fixers = fixers == null ? List.of() : List.copyOf(fixers);
        validators = validators == null ? List.of() : List.copyOf(validators);
        if (transportRetries < 0) {
            throw new IllegalArgumentException("transportRetries must be >= 0, got " + transportRetries);
        }
    }
}
