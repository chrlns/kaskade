# Kaskade

Declarative LLM escalation chains for Java 25.

A request is sent to model A. If the response fails a task-specific validation,
the chain escalates to model B, then C, and so on. Typical use case: a fast
local model produces slightly malformed output, fixers attempt to repair it,
validators decide acceptance, and only if that fails does the chain fall back
to a more compliant (or paid cloud) model.

```xml
<dependency>
  <groupId>pub.source</groupId>
  <artifactId>kaskade</artifactId>
  <version>0.1.0</version>
</dependency>
```

## Documentation

- [Overview](docs/index.md) — landing page: what Kaskade is, a first
  snippet, and pointers to the two guides
- [Getting started](docs/getting-started.md) — concepts, first runnable chain,
  connecting a real transport, YAML configuration, built-ins reference,
  budgets, logging, testing
- [Usage scenarios](docs/scenarios.md) — complete recipes: structured output
  with repair pipelines, cost-capped batches, strict output contracts,
  label normalization, flaky infrastructure, YAML-driven environments,
  cost measuring, testing ladders

Browsable HTML versions of the documentation (with syntax-highlighted code
blocks when opened online):

```bash
mvn -Pdocs generate-resources
open target/docs-html/index.html
```

## Runnable examples

Every recipe from the documentation also exists as executable Java in
`pub.source.kaskade.examples`, together with a scripted `StubTransport`:
each example exposes a `spec()` method that builds the documented chain and
a `main` method that runs it without any model or network. The unit test
`ExamplesTest` pins the escalation behavior of all examples:

```bash
mvn test -Dtest=ExamplesTest
```

## Design rules

- Java 25, single Maven module, groupId `pub.source`, Java package
  `pub.source.kaskade`.
- Zero required runtime dependencies in the core: records, sealed interfaces,
  pattern matching, `java.lang.System.Logger`. No reflection, no annotation
  processing, no Lombok.
- SnakeYAML is the only third-party dependency and is confined to the
  `pub.source.kaskade.yaml` package. (Jackson would be allowed there too, but
  is not needed.)
- One internal spec model is the single source of truth shared by both
  frontends (fluent Java API and YAML).
- JUnit 5 tests, no frameworks.

## Core model

```java
public record ChainSpec(String name, BudgetSpec budget, List<StepSpec> steps) {}
public record StepSpec(ModelRef model, List<Fixer> fixers, List<Validator> validators,
                       int transportRetries, boolean paid) {}
public record BudgetSpec(double maxCostUsd, OnExhausted onExhausted) {}
public enum OnExhausted { FAIL, STOP_BEFORE_PAID, WARN }
public record ModelRef(String provider, String model, Map<String, String> params) {}
```

## Quickstart (fluent API)

```java
ChainSpec spec = Chain.builder("extract-contact")
        .budget(b -> b.maxCostUsd(0.50).onExhausted(OnExhausted.STOP_BEFORE_PAID))
        .step(s -> s.model("ollama", "llama3.2")          // fast, free
                .param("temperature", "0.2")
                .fixer(Fixers.stripCodeFence())            // normalizer
                .fixer(Fixers.extractJson())
                .validator(Validators.isJson())           // validator
                .transportRetries(1))
        .step(s -> s.model("openai", "gpt-5")              // compliant, paid
                .paid()
                .fixer(Fixers.trim())
                .validator(Validators.isJson()))
        .build();

ChainEngine engine = new ChainEngine((model, prompt) -> myHttpTransport(model, prompt));
ChainResult result = engine.run(spec, "Extract the contact as JSON");
```

## Quickstart (YAML)

```yaml
name: extract-contact
budget:
  maxCostUsd: 0.5
  onExhausted: STOP_BEFORE_PAID
steps:
  - model:
      provider: ollama
      model: llama3.2
      params:
        temperature: "0.2"
    fixers: [stripCodeFence, extractJson]
    validators: [isJson]
    transportRetries: 1
  - model:
      provider: openai
      model: gpt-5
    validators: [isJson]
    paid: true
```

```java
ChainSpec spec = ChainYaml.load(yaml,
        FunctionResolver.of(myFixers, myValidators).withFallback(FunctionResolver.builtins()));
```

Fixer and validator names in YAML are resolved through a `FunctionResolver`;
the built-ins (`trim`, `stripCodeFence`, `extractJson`, `nonEmpty`, `isJson`)
are always available, custom functions are registered by name. Unknown keys
are rejected so typos fail loudly.

## Execution semantics

- Steps run in order. Fixers repair the raw response in list order, then all
  validators must accept; the first accepted response ends the chain as
  `Accepted`. A step without validators accepts any response.
- Transport exceptions are retried `transportRetries` additional times, then
  the chain escalates. A fixer or validator that throws also escalates.
- If no step is ever accepted: `Rejected` carries the most recently received
  content; `Failed` means empty chain, `FAIL` budget exhaustion, or transport
  failure on every step.
- Budgets are checked *before* each step. A budget is exhausted once the
  accumulated cost reaches `maxCostUsd` (`<= 0` or absent = unlimited):
  - `FAIL`: abort with `Failed`.
  - `STOP_BEFORE_PAID`: skip paid steps, free steps keep running.
  - `WARN`: keep running everything, log a warning.
- Budgets are never applied retroactively: a response accepted in the current
  call is returned even if it pushed the total over the limit.

## Built-ins

| Fixers | Validators |
|---|---|
| `trim()` | `nonEmpty()` |
| `stripCodeFence()` | `contains(literal)` |
| `extractJson()` | `matches(pattern)` |
| `regexReplace(pattern, replacement)` | `maxLength(n)` |
| | `isJson()` (hand-written strict parser, no third-party code) |

## Extending

Implement the `Transport` SPI to deliver prompts; Kaskade prescribes nothing
about the wire protocol (`java.net.http` is a natural fit, but any client
works). Cost reporting (`TransportResult.costUsd`) drives the budget; free
models report `0.0`. Fixers and validators are single-method interfaces
(`String -> String`, `String -> boolean`), so any lambda works.

## Build

```bash
mvn verify
```

Requires JDK 25.
