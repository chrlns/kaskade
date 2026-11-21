# Getting started

Kaskade is a small Java 25 library for declarative LLM escalation chains: a
request goes to model A; if the response fails a task-specific validation, the
chain escalates to model B, then C, and so on. This guide takes you from an
empty `pom.xml` to a running chain against real models.

## Requirements

- JDK 25 (`java -version` must report 25.x)
- Maven 3.9 or newer
- No third-party dependencies in application code that only uses the core
  package. The `pub.source.kaskade.yaml` package requires SnakeYAML.

## Add the dependency

```xml
<dependency>
  <groupId>pub.source</groupId>
  <artifactId>kaskade</artifactId>
  <version>0.1.0</version>
</dependency>
```

If you consume the library without a dependency manager that resolves
transitives, add SnakeYAML for YAML support:

```xml
<dependency>
  <groupId>org.yaml</groupId>
  <artifactId>snakeyaml</artifactId>
  <version>2.3</version>
</dependency>
```

## The idea in five sentences

1. A **`ChainSpec`** is an ordered list of **steps**. Each step names a model,
   a list of **fixers** (repair functions), a list of **validators**
   (acceptance rules), and transport retry behavior.
2. A **`ChainEngine`** sends your prompt to the first step's model through
   your **`Transport`** implementation. Kaskade never talks to a network
   itself; the transport is yours.
3. Fixers run in order on the raw response, then all validators must accept.
   The first accepted response ends the chain as `Accepted`.
4. If validation fails (or the transport dies after retries), the engine
   escalates to the next step, typically a stronger or paid model.
5. An optional **budget** caps accumulated cost per run; when it is
   exhausted, the `OnExhausted` policy decides what happens.

## Your first chain

This example is fully runnable without a network connection: the transport is
a stand-in that answers from a fixed script, so you can see the escalation
flow immediately.

```java
import pub.source.kaskade.*;

public class FirstChain {

    public static void main(String[] args) {
        // 1. Describe the chain. No side effects happen here.
        ChainSpec spec = Chain.builder("extract-email")
                .step(s -> s.model("local", "llama3.2")          // fast, free
                        .fixer(Fixers.stripCodeFence())          // drop ```...``` fences
                        .fixer(Fixers.extractJson())             // drop prose around the JSON
                        .validator(Validators.isJson())          // accept only valid JSON
                        .transportRetries(1))
                .step(s -> s.model("cloud", "big-model")         // slower, paid
                        .paid()
                        .fixer(Fixers.trim())
                        .validator(Validators.isJson()))
                .build();

        // 2. Provide a transport. In production this speaks HTTP;
        //    here it answers deterministically.
        Transport transport = (model, prompt) -> {
            if (model.provider().equals("local")) {
                return TransportResult.free("Sure! ```json\n{\"email\": \"a@b.c\"}\n``` hope that helps");
            }
            return new TransportResult("{\"email\": \"a@b.c\"}", 0.02);
        };

        // 3. Run it.
        ChainResult result = new ChainEngine(transport).run(spec, "Extract the sender's email as JSON");

        // 4. Inspect the outcome. ChainResult is sealed, so the
        //    compiler knows this switch is exhaustive.
        switch (result) {
            case ChainResult.Accepted a ->
                    System.out.println("Accepted by " + a.model().model()
                            + " at step " + a.stepIndex()
                            + " for " + a.totalCostUsd() + " USD:\n" + a.content());
            case ChainResult.Rejected r ->
                    System.out.println("Rejected by every step. Last model: "
                            + r.model().model() + ". Spent " + r.totalCostUsd() + " USD.");
            case ChainResult.Failed f ->
                    System.out.println("Failed: " + f.reason());
        }
    }
}
```

What happens: the local model answers with fenced JSON surrounded by chit-chat.
The fixers on step 0 repair that (`stripCodeFence` then `extractJson`), so
`isJson` accepts, and the paid model is never contacted. The output is:

```
Accepted by llama3.2 at step 0 for 0.0 USD:
{"email": "a@b.c"}
```

Delete the first step, or make the local answer unrecoverably broken, and the
engine escalates to the paid step at step index 1 and reports the 0.02 USD.

## Interpreting `ChainResult`

`ChainResult` is a sealed interface with exactly three shapes:

| Result | Meaning | Key fields |
|---|---|---|
| `Accepted` | A step accepted a response. | `content` (fixed), `model`, `stepIndex`, `totalCostUsd` |
| `Rejected` | Every step ran; all received responses were rejected by their validators. | `content` of the most recent response, `model`, `stepIndex`, `totalCostUsd` |
| `Failed` | No response at all: empty chain, budget exhaustion under `FAIL`, or transport failure on every step. | `reason`, `totalCostUsd` |

`totalCostUsd()` is always the accumulated transport cost of the run, even in
failure cases, so you can bill or log it.

## Connecting a real model

Implement the `Transport` SPI. Kaskade deliberately prescribes nothing about
the wire protocol: `ModelRef.provider`/`model`/`params` are opaque strings
that your transport interprets, and `TransportResult.costUsd` is whatever your
provider reports (use `0.0` for free local models).

A realistic sketch for an OpenAI-compatible endpoint using only
`java.net.http`:

```java
import pub.source.kaskade.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Collectors;

public final class OpenAiCompatibleTransport implements Transport {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final URI endpoint = URI.create("https://api.example.com/v1/chat/completions");
    private final String apiKey;

    public OpenAiCompatibleTransport(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public TransportResult call(ModelRef model, String prompt) throws Exception {
        String params = model.params().entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(","));
        if (!params.isEmpty()) {
            params = "," + params;
        }

        String requestBody = """
                {
                  "model": "%s",
                  "messages": [{"role": "user", "content": %s}]%s
                }
                """.formatted(model.model(), quote(prompt), params);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("provider returned HTTP " + response.statusCode());
        }

        // Illustrative extraction: in real code, parse the JSON body with the
        // JSON library of your choice and read `choices[0].message.content`
        // plus the reported usage/cost.
        String content = extractContent(response.body());
        double costUsd = "cloud".equals(model.provider()) ? readCostFrom(response.body()) : 0.0;
        return new TransportResult(content, costUsd);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String extractContent(String body) {
        int i = body.indexOf("\"content\":\"") + 11;
        return body.substring(i, body.indexOf("\"", i)).replace("\\n", "\n").replace("\\\"", "\"");
    }

    private static double readCostFrom(String body) {
        // Read from the provider's usage fields if available.
        return 0.02;
    }

    public static void main(String[] args) {
        Transport transport = new OpenAiCompatibleTransport(System.getenv("API_KEY"));
        ChainSpec spec = Chain.builder("real-chain")
                .step(s -> s.model("cloud", "gpt-5").param("temperature", "0.0")
                        .validator(Validators.nonEmpty()).paid())
                .build();
        System.out.println(new ChainEngine(transport).run(spec, "Say hi"));
    }
}
```

Notes on the contract:

- **Throwing means transport failure.** Any exception from `call` is caught
  by the engine, retried per the step's `transportRetries`, and then
  escalates. Task-level failure is expressed differently: return a response
  your validators will reject.
- **Report cost truthfully.** Budget behavior is only as good as the
  `costUsd` values your transport reports.
- The example above is deliberately dependency-free; if your application
  already uses a JSON library, use it for request and response bodies instead
  of string surgery.

## Configuring via YAML

The YAML frontend parses the same spec model, so YAML and the fluent API are
interchangeable:

```yaml
name: extract-email
budget:
  maxCostUsd: 0.5
  onExhausted: STOP_BEFORE_PAID
steps:
  - model:
      provider: local
      model: llama3.2
      params:
        temperature: "0.2"
    fixers: [stripCodeFence, extractJson]
    validators: [isJson]
    transportRetries: 1
  - model:
      provider: cloud
      model: gpt-5
    validators: [isJson]
    paid: true
```

```java
ChainSpec spec = ChainYaml.load(yaml);           // built-in functions only
ChainResult result = new ChainEngine(transport).run(spec, prompt);
```

Fixer and validator names in YAML are resolved through a `FunctionResolver`.
The built-ins (`trim`, `stripCodeFence`, `extractJson`, `nonEmpty`, `isJson`)
are always available; register your own functions by name and compose with a
fallback:

```java
FunctionResolver resolver = FunctionResolver.of(
                Map.of("normalizeLabel", (Fixer) s -> s.strip().toLowerCase()),
                Map.of("isOneOfOurLabels", (Validator) s -> s.equals("yes") || s.equals("no")))
        .withFallback(FunctionResolver.builtins());

ChainSpec spec = ChainYaml.load(yaml, resolver);
```

The loader is strict: unknown keys (a typo like `validator` instead of
`validators`) and unknown function names throw an `IllegalArgumentException`
with the offending location instead of silently misconfiguring your chain.
Files work too: `ChainYaml.load(Path, FunctionResolver)`.

## Built-ins reference

| Fixer | Effect |
|---|---|
| `Fixers.trim()` | Strips leading/trailing whitespace. |
| `Fixers.stripCodeFence()` | Removes one surrounding ` ```lang ... ``` ` fence, if present. |
| `Fixers.extractJson()` | Cuts from the first `{` to the last `}`, dropping prose. |
| `Fixers.regexReplace(pattern, replacement)` | Regex `replaceAll` on the response. |

| Validator | Accepts when |
|---|---|
| `Validators.nonEmpty()` | At least one non-whitespace character. |
| `Validators.contains(literal)` | The literal occurs in the response. |
| `Validators.matches(pattern)` | The response fully matches the pattern. |
| `Validators.maxLength(n)` | At most `n` characters. |
| `Validators.isJson()` | The response is one syntactically valid JSON document (strict: no leading zeros, no raw control characters, nesting capped at 256). |

A step with no validators accepts any response; a step with no fixers passes
the raw response to the validators unchanged.

## Budgets

Add a budget to any chain (both frontends):

- `budget: null` (or no `budget` key) means **no limit**.
- `maxCostUsd <= 0` also means **unlimited**.
- The budget is checked **before** each step: it is exhausted once the
  accumulated cost has reached `maxCostUsd`.
- On exhaustion, the policy applies:
  - `FAIL` (default) — the run ends immediately as `Failed`.
  - `STOP_BEFORE_PAID` — paid steps are skipped; free steps keep running.
  - `WARN` — everything keeps running; the engine logs a warning.
- Budgets are never applied retroactively. If a response is accepted and the
  call pushed the total over the limit, you still get `Accepted` with the
  true `totalCostUsd`; the policy takes effect from the next step on.

## Logging

The engine uses `java.lang.System.Logger` (level `WARNING`) to explain every
escalation: transport failures with retry counts, throwing fixers/validators,
skipped paid steps, and budget warnings. Configure the `java.util.logging`
backend to adjust levels; expect warnings in the console during tests that
simulate failures.

## Testing chains

Because all I/O sits behind `Transport`, chains are trivial to unit test.
Script the transport with a lambda and assert on `ChainResult`:

```java
import java.io.IOException;

import org.junit.jupiter.api.Test;

import pub.source.kaskade.*;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MyChainTest {

    @Test
    void cheapModelWinsWhenRepairSucceeds() {
        Transport flakyLocal = (model, prompt) -> {
            if (prompt.contains("draft")) throw new IOException("down");
            return TransportResult.free("```json\n{\"ok\": true}\n```");
        };
        ChainSpec spec = Chain.builder("t")
                .step(s -> s.model("local", "m").fixer(Fixers.stripCodeFence())
                        .validator(Validators.isJson()).transportRetries(1))
                .build();

        ChainResult result = new ChainEngine(flakyLocal).run(spec, "draft me some JSON");

        assertInstanceOf(ChainResult.Accepted.class, result);
    }
}
```

The project's own test suite (`src/test/java`) contains full patterns for
retries, budgets, YAML loading, and every escalation path; use it as a
cookbook.

## Where to go next

- [Usage scenarios](scenarios.md) — complete recipes: cost-capped batch jobs,
  strict output contracts, YAML-driven environments, graceful degradation,
  and more.
- Runnable versions of every scenario, with a scripted `StubTransport` and
  pinning tests, live in the `pub.source.kaskade.examples` package
  (`mvn test -Dtest=ExamplesTest`).
- The Javadoc (`mvn javadoc:javadoc`, then open
  `target/reports/apidocs/index.html`) documents every public type.
- Browsable HTML versions of these pages:
  `mvn -Pdocs generate-resources`, then open `target/docs-html/`.
