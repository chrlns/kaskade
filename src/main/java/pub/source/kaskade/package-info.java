/**
 * Kaskade core: declarative LLM escalation chains.
 *
 * <p>A {@code ChainSpec} is the single source of truth shared by both
 * frontends, the fluent API ({@code Chain}) and the YAML frontend
 * ({@code pub.source.kaskade.yaml.ChainYaml}). A {@code ChainEngine}
 * executes a spec against a user-supplied {@code Transport}; the library
 * prescribes nothing about the wire protocol.</p>
 *
 * <p>This package has zero required runtime dependencies beyond the JDK.</p>
 */
package pub.source.kaskade;
