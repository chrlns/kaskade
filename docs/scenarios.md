# Usage scenarios

Complete recipes for the patterns Kaskade was built for. Each scenario is
self-contained; combine them freely. All snippets compile against the public
API of `pub.source.kaskade` (plus `pub.source.kaskade.yaml` where noted).
Every scenario also exists as executable Java with a scripted stub transport
in the `pub.source.kaskade.examples` package; `ExamplesTest` pins the
behavior of each one.

## Scenario 1: Structured output from a fast local model

**Goal.** Extract JSON from prompts using a cheap local model, paying for the
cloud model only when the local output is beyond repair.

This is the classic Kaskade chain: repair pipeline on the cheap step, strict
validator, compliant fallback.

```java
ChainSpec spec = Chain.builder("extract-entities")
        .step(s -> s.model("ollama", "llama3.2")
                .param("temperature", "0.1")
                .fixer(Fixers.stripCodeFence())     // ```json ... ``` -> content
                .fixer(Fixers.extractJson())         // strip prose around the object
                .validator(Validators.isJson())
                .transportRetries(1))
        .step(s -> s.model("cloud", "gpt-5")
                .fixer(Fixers.trim())
                .validator(Validators.isJson())
                .paid())
        .budget(b -> b.maxCostUsd(0.50).onExhausted(OnExhausted.STOP_BEFORE_PAID))
        .build();
```

The same chain as YAML, resolved through the built-in functions:

```yaml
name: extract-entities
budget:
  maxCostUsd: 0.5
  onExhausted: STOP_BEFORE_PAID
steps:
  - model:
      provider: ollama
      model: llama3.2
      params:
        temperature: "0.1"
    fixers: [stripCodeFence, extractJson]
    validators: [isJson]
    transportRetries: 1
  - model:
      provider: cloud
      model: gpt-5
    fixers: [trim]
    validators: [isJson]
    paid: true
```

**Notes.**

- Fixer order matters: `stripCodeFence` must run before `extractJson`, or the
  fence characters sit inside the extracted text.
- The fixers live on the *cheap* step. The paid step gets only `trim()`,
  because you pay precisely for a model that should not need repair.
- Typical yield: with a well-prompted local model, 90 to 100 percent of runs
  never reach step 1. Log `Accepted.stepIndex()` for a while to measure your
  actual escalation rate before you tune the budget.

## Scenario 2: Cost-capped batch jobs

**Goal.** Process 10,000 prompts overnight with a hard ceiling on provider
cost, preferring the free model everywhere possible.

Budgets apply **per run**, so the clean pattern is: one spec, one engine,
reused across the batch, with accounting per item.

```java
ChainSpec spec = Chain.builder("batch-item")
        .budget(b -> b.maxCostUsd(0.02).onExhausted(OnExhausted.STOP_BEFORE_PAID))
        .step(s -> s.model("local", "llama3.2")
                .fixer(Fixers.trim())
                .validator(Validators.nonEmpty()))
        .step(s -> s.model("cloud", "gpt-5-mini")
                .fixer(Fixers.trim())
                .validator(Validators.nonEmpty())
                .paid())
        .build();

ChainEngine engine = new ChainEngine(transport);

for (String prompt : prompts) {
    ChainResult result = engine.run(spec, prompt);
    switch (result) {
        case ChainResult.Accepted a -> collect(prompt, a.content(), a.stepIndex());
        case ChainResult.Rejected r -> quarantine(prompt, r.content());
        case ChainResult.Failed f -> logSkipped(prompt, f.reason());
    }
}
```

**Notes.**

- Each run starts with a fresh budget: `maxCostUsd` limits the cost of one
  item, not the whole batch. For a batch ceiling, sum
  `result.totalCostUsd()` and stop the loop when the batch total is reached.
- `STOP_BEFORE_PAID` means: once an item has already cost 0.02 USD, later paid
  fallbacks for that item are skipped, but the free model still runs. Use
  `FAIL` if a partially-answered item is worthless to you.
- Free steps are unaffected by an exhausted budget under
  `STOP_BEFORE_PAID`, so batch noise from the local model never aborts items.

## Scenario 3: Strict output contracts for downstream systems

**Goal.** An LLM generates SQL fragments that go straight into a reporting
engine. Whatever passes validation must be exactly one `SELECT` statement,
no trailing semicolon, bounded length.

Here the validators are the contract; the fixers normalize small lapses.

```java
import java.util.regex.Pattern;

ChainSpec spec = Chain.builder("select-only")
        .step(s -> s.model("cloud", "strong-sql")
                .fixer(Fixers.trim())
                .fixer(Fixers.regexReplace(Pattern.compile(";\\s*$"), ""))
                .validator(Validators.matches(
                        Pattern.compile("SELECT .+", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)))
                .validator(Validators.maxLength(2000))
                .paid())
        .build();
```

**Notes.**

- `Validators.matches` uses `Matcher.matches()`, i.e. the whole response must
  match. Combined with `maxLength`, downstream code can rely on both shape
  and size.
- This chain has one step: Kaskade is also a usable one-model pipeline with
  normalization and acceptance checks. Escalation is optional.
- For actual SQL injection safety, keep the output away from write
  operations; a validator checks syntax shape, not semantics.

## Scenario 4: Classification with label normalization

**Goal.** Classify text into a fixed set of labels. The small model mostly
answers, but wraps labels in prose ("Positive.", "negative!"). Normalize
first, validate against the label set, escalate to a big model only if the
label is still unrecognizable.

```java
import java.util.Set;
import java.util.regex.Pattern;

Validator oneOfLabels = s -> Set.of("positive", "neutral", "negative").contains(s);

ChainSpec spec = Chain.builder("sentiment")
        .step(s -> s.model("local", "llama3.2")
                .fixer(Fixers.trim())
                .fixer(Fixers.regexReplace(Pattern.compile("(?i)\\bpositive\\b[.!]?"), "positive"))
                .fixer(Fixers.regexReplace(Pattern.compile("(?i)\\bnegative\\b[.!]?"), "negative"))
                .fixer(Fixers.regexReplace(Pattern.compile("(?i)\\bneutral\\b[.!]?"), "neutral"))
                .validator(oneOfLabels)
                .transportRetries(1))
        .step(s -> s.model("cloud", "gpt-5")
                .validator(oneOfLabels)
                .paid())
        .build();
```

**Notes.**

- Domain validators like `oneOfLabels` are plain lambdas; on the YAML side,
  register them by name (see scenario 6).
- The paid step deliberately has no fixers: if the strong model still answers
  outside the label set, you want the `Rejected`, not a silently repaired
  guess.
- Consider a final normalization fixer after validation is impossible (there
  is none; fixers always run before validators), so push all normalization
  into the fixer list.

## Scenario 5: Graceful degradation with flaky local infrastructure

**Goal.** Survive a local Ollama instance that occasionally refuses
connections, without hammering it and without losing the request.

Two layers of defense: `transportRetries` per step, and an escalating
fallback step. Kaskade retries immediately without backoff; if you want
polite pauses, wrap your transport.

```java
/** Transport decorator that pauses once before letting a failure escalate. */
public final class WithBackoff implements Transport {
    private final Transport delegate;
    private final long pauseMillis;

    public WithBackoff(Transport delegate, long pauseMillis) {
        this.delegate = delegate;
        this.pauseMillis = pauseMillis;
    }

    @Override
    public TransportResult call(ModelRef model, String prompt) throws Exception {
        try {
            return delegate.call(model, prompt);
        } catch (Exception first) {
            Thread.sleep(pauseMillis);
            return delegate.call(model, prompt);   // second failure propagates to the engine
        }
    }
}

ChainSpec spec = Chain.builder("resilient")
        .step(s -> s.model("local", "llama3.2")
                .validator(Validators.nonEmpty())
                .transportRetries(2))
        .step(s -> s.model("cloud", "gpt-5")
                .validator(Validators.nonEmpty())
                .paid())
        .build();

ChainEngine engine = new ChainEngine(new WithBackoff(transport, 2_000));
```

**Notes.**

- `transportRetries` counts *additional* attempts: `2` means up to three
  calls. Retries happen per step, so the paid step gets its own retry count.
- With the decorator above, one engine-level retry already includes one
  pause; set `transportRetries(0)` on the local step if you prefer the
  decorator to own all retrying.
- The engine logs every failed transport attempt with the retry counter
  (`WARNING`), so degrading infrastructure is visible in your logs before it
  becomes an incident.

## Scenario 6: YAML-driven environments (dev / staging / prod)

**Goal.** Same code everywhere, different chains per environment: dev uses
only the free local model; prod adds the paid fallback and a budget.

The chain lives in YAML next to the code; the resolver registers
domain-specific functions under stable names.

`config/dev.yaml`:

```yaml
name: extract-product
steps:
  - model:
      provider: ollama
      model: llama3.2
    fixers: [extractProductNumber]
    validators: [isProductNumber]
```

`config/prod.yaml`:

```yaml
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
```

Loading code, environment-independent:

```java
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import pub.source.kaskade.*;
import pub.source.kaskade.yaml.ChainYaml;
import pub.source.kaskade.yaml.FunctionResolver;

FunctionResolver resolver = FunctionResolver.of(
                Map.of("extractProductNumber",
                        (Fixer) s -> s.replaceAll(".*?(\\d{4,6}).*", "$1")),
                Map.of("isProductNumber", (Validator) s -> s.matches("\\d{4,6}")))
        .withFallback(FunctionResolver.builtins());

// ... "dev" or "prod":
ChainSpec spec;
try {
    spec = ChainYaml.load(Path.of("config", env + ".yaml"), resolver);
} catch (IOException e) {
    throw new IllegalStateException("no chain config for environment " + env, e);
}
```

**Notes.**

- The strict loader turns a typo in either YAML file into an immediate,
  located `IllegalArgumentException` at startup, in every environment.
- `FunctionResolver.of(...)` owns the domain functions;
  `withFallback(builtins())` keeps `trim`, `isJson` and friends available.
- Ops can retune chains (add a step, raise the budget) without a rebuild.

## Scenario 7: Measure costs before enforcing them

**Goal.** You plan to introduce budgets, but first want real numbers: how
much does this workload actually cost when nothing is blocked?

Run with `WARN`, sum the totals, then decide the limit.

```java
ChainSpec spec = Chain.builder("cost-probe")
        .budget(b -> b.maxCostUsd(1.0).onExhausted(OnExhausted.WARN))
        .step(s -> s.model("local", "llama3.2").validator(Validators.nonEmpty()))
        .step(s -> s.model("cloud", "gpt-5").validator(Validators.nonEmpty()).paid())
        .build();

List<ChainResult> results = prompts.stream()
        .map(p -> engine.run(spec, p))
        .toList();

double totalCost = results.stream().mapToDouble(ChainResult::totalCostUsd).sum();
double paidRuns = results.stream()
        .filter(r -> r instanceof ChainResult.Accepted a && a.model().provider().equals("cloud"))
        .count();
```

**Notes.**

- `WARN` keeps every step running, paid ones included, and logs one warning
  per run that crosses the limit, so the log itself is your cost profile.
- Once you know the real distribution, switch to `STOP_BEFORE_PAID` (keep
  working, stop spending) or `FAIL` (fail fast, alert the caller).
- Per-run totals compose into batch accounting: `totalCostUsd()` is present
  on all three result shapes, so no result type blocks your aggregation.

## Scenario 8: Unit-testing an entire ladder

**Goal.** Reproduce and pin every escalation decision in a fast test, no
network, no mocking framework.

Script a `Transport` and assert on the sealed result:

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pub.source.kaskade.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LadderTest {

    private static final ModelRef LOCAL = new ModelRef("local", "llama3.2", Map.of());
    private static final ModelRef CLOUD = new ModelRef("cloud", "gpt-5", Map.of());

    @Test
    void repairOnCheapStepAvoidsTheCloudModel() {
        List<String> calls = new ArrayList<>();
        Transport transport = (model, prompt) -> {
            calls.add(model.model());
            return TransportResult.free("Sure! ```json\n{\"a\":1}\n```");
        };
        ChainSpec spec = Chain.builder("t")
                .step(s -> s.model("local", "llama3.2")
                        .fixer(Fixers.stripCodeFence())
                        .validator(Validators.isJson()))
                .step(s -> s.model("cloud", "gpt-5").paid().validator(Validators.isJson()))
                .build();

        ChainResult.Accepted accepted = assertInstanceOf(ChainResult.Accepted.class,
                new ChainEngine(transport).run(spec, "p"));

        assertEquals(List.of("llama3.2"), calls);      // cloud never called
        assertEquals(0, accepted.stepIndex());
    }
}
```

**Notes.**

- The transport captures its calls in a list; assertion on that list is the
  cheapest way to prove which step answered.
- `assertInstanceOf` returns the cast subtype, so you can chain component
  assertions without an explicit cast.
- For failure paths, make the lambda throw: every engine behavior (retries,
  budget policies, throwing fixers) is deterministic and covered in the
  project's own test suite, which doubles as a cookbook for edge cases.
