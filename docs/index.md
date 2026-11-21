# Kaskade

Kaskade is a small Java 25 library for declarative LLM escalation chains:
send the prompt to the cheap model first, repair what can be repaired,
validate strictly, and escalate to a stronger (usually paid) model only when
the task really needs it.

## Start here

- **[Getting started](getting-started.md)** — from an empty `pom.xml` to a
  running chain: concepts, a runnable first example, connecting a real
  transport, YAML configuration, the built-ins reference, budgets, logging
  and testing.
- **[Usage scenarios](scenarios.md)** — complete recipes: structured output
  with repair pipelines, cost-capped batches, strict output contracts, label
  normalization, flaky infrastructure, YAML-driven environments, cost
  measuring and testing ladders.

## The idea in one snippet

```java
ChainSpec spec = Chain.builder("extract-contact")
        .step(s -> s.model("ollama", "llama3.2")
                .fixer(Fixers.stripCodeFence())
                .fixer(Fixers.extractJson())
                .validator(Validators.isJson()))
        .step(s -> s.model("cloud", "gpt-5")
                .validator(Validators.isJson())
                .paid())
        .budget(b -> b.maxCostUsd(0.50).onExhausted(OnExhausted.STOP_BEFORE_PAID))
        .build();

ChainResult result = new ChainEngine(transport).run(spec, prompt);
```

## What you get

- Zero runtime dependencies in the core: records, sealed interfaces,
  pattern matching and `java.lang.System.Logger`. No reflection, no Lombok.
- A pure transport SPI: Kaskade prescribes nothing about the wire protocol;
  any HTTP client (or anything else) works.
- Repair pipelines (`Fixer`) and acceptance rules (`Validator`): plain
  single-method interfaces, lambdas are enough.
- Per-run cost budgets with three policies: fail fast, skip paid steps, or
  just warn and measure first.
- An optional, strict YAML frontend (SnakeYAML, `pub.source.kaskade.yaml`);
  unknown keys and unknown function names fail loudly instead of being
  silently ignored.

## Runnable examples

Every recipe also exists as executable Java in `pub.source.kaskade.examples`,
complete with a scripted `StubTransport`: each example has a `spec()` method
that builds the documented chain and a `main` method that runs it without
any model or network. The unit test `ExamplesTest` pins the escalation
behavior of all of them; `mvn test -Dtest=ExamplesTest` runs only that
suite.

```bash
mvn verify                     # compile and all tests
mvn test -Dtest=ExamplesTest   # just the example suite
```

## Building the docs

```bash
mvn -Pdocs generate-resources   # browsable HTML, written to target/docs-html
open target/docs-html/index.html
```

The generated pages carry syntax-highlighted code blocks (Java, YAML, XML
and shell) when opened with an internet connection; offline they render as
plain, still readable code.
