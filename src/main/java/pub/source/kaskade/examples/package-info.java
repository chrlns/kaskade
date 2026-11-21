/**
 * Self-contained, executable versions of the recipes documented in the
 * project's {@code docs/} pages (getting-started and usage scenarios).
 *
 * <p>Every example follows the same shape: a static {@code spec()} method
 * builds the chain exactly as documented, and a {@code main} method runs it
 * against a scripted {@link StubTransport}, so the escalation behavior can
 * be observed without any model or network. The unit test
 * {@code pub.source.kaskade.examples.ExamplesTest} pins the behavior of
 * every example; it is the fastest way to read the expected outcomes.</p>
 */
package pub.source.kaskade.examples;
