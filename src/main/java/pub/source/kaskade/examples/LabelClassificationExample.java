package pub.source.kaskade.examples;

import java.util.Set;
import java.util.regex.Pattern;

import pub.source.kaskade.Chain;
import pub.source.kaskade.ChainEngine;
import pub.source.kaskade.ChainResult;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Fixer;
import pub.source.kaskade.Fixers;
import pub.source.kaskade.Validator;
import pub.source.kaskade.Validators;

/**
 * Scenario 4 of {@code docs/scenarios.md}: classification into a fixed label
 * set. The small model mostly answers, but wraps labels in prose
 * ("Positive.", "negative!") - fixers normalize before the domain validator
 * decides, and the big model is only consulted when the label is still
 * unrecognizable.
 *
 * <p>The paid step deliberately has no fixers: if the strong model still
 * answers outside the label set, you want the {@code Rejected}, not a
 * silently repaired guess.</p>
 */
public final class LabelClassificationExample {

    private LabelClassificationExample() {
    }

    /**
     * Domain validator shared by both steps: the whole (normalized) response
     * must be one of the three labels.
     */
    public static Validator oneOfLabels() {
        return response -> Set.of("positive", "neutral", "negative").contains(response);
    }

    /**
     * @return the documented sentiment chain
     */
    public static ChainSpec spec() {
        return Chain.builder("sentiment")
                .step(s -> s.model("local", "llama3.2")
                        .fixer(Fixers.trim())
                        .fixer(regexNormalizeLabel("positive"))
                        .fixer(regexNormalizeLabel("negative"))
                        .fixer(regexNormalizeLabel("neutral"))
                        .validator(oneOfLabels())
                        .transportRetries(1))
                .step(s -> s.model("cloud", "gpt-5")
                        .validator(oneOfLabels())
                        .paid())
                .build();
    }

    /**
     * Runs the chain against a prosaic local answer that normalization saves.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        StubTransport transport = new StubTransport()
                .respond("llama3.2", "Positive.")                       // normalized, no escalation
                .respond("gpt-5", "negative", 0.004);                   // never called in this run

        ChainResult result = new ChainEngine(transport).run(spec(),
                "Classify the tone of this review");

        System.out.println(result);
        System.out.println("calls: " + transport.calledModels());
    }

    private static Fixer regexNormalizeLabel(String label) {
        return Fixers.regexReplace(
                Pattern.compile("(?i)\\b" + label + "\\b[.!]?"),
                label);
    }
}
