package pub.source.kaskade.examples;

import java.util.regex.Pattern;

import pub.source.kaskade.ChainEngine;
import pub.source.kaskade.ChainResult;
import pub.source.kaskade.ChainSpec;
import pub.source.kaskade.Chain;
import pub.source.kaskade.Fixers;
import pub.source.kaskade.Validators;

/**
 * Scenario 3 of {@code docs/scenarios.md}: a strict output contract for a
 * downstream reporting engine. Whatever passes validation must be exactly
 * one {@code SELECT} statement, no trailing semicolon, bounded length.
 *
 * <p>Also demonstrates that Kaskade is a usable one-model pipeline:
 * escalation is optional.</p>
 */
public final class StrictContractExample {

    private StrictContractExample() {
    }

    /**
     * @return the documented select-only chain
     */
    public static ChainSpec spec() {
        return Chain.builder("select-only")
                .step(s -> s.model("cloud", "strong-sql")
                        .fixer(Fixers.trim())
                        .fixer(Fixers.regexReplace(Pattern.compile(";\\s*$"), ""))
                        .validator(Validators.matches(
                                Pattern.compile("SELECT .+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)))
                        .validator(Validators.maxLength(2000))
                        .paid())
                .build();
    }

    /**
     * Runs the chain against a model that answers with a trailing semicolon
     * and a newline; the fixers normalize it into the contract shape.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        StubTransport transport = new StubTransport()
                .respond("strong-sql", "SELECT id, name FROM users;\n", 0.01);

        ChainResult result = new ChainEngine(transport).run(spec(), "Build the report query");

        System.out.println(result);
    }
}
