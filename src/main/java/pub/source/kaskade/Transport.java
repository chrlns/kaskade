package pub.source.kaskade;

/**
 * SPI for delivering a prompt to a model. Implemented by the library user;
 * Kaskade prescribes nothing about the wire protocol.
 *
 * <p>Implementations may throw any exception to signal a transport-level
 * failure. The engine retries such failures per
 * {@link StepSpec#transportRetries()} and then escalates to the next step.
 * Task-level failures are reported differently: return a response that the
 * step's validators reject.</p>
 */
public interface Transport {

    /**
     * @param model  the model to call
     * @param prompt the user prompt to deliver
     * @return the raw model response plus its cost in USD (0.0 for free models)
     * @throws Exception on transport-level failure
     */
    TransportResult call(ModelRef model, String prompt) throws Exception;
}
