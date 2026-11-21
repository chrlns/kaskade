package pub.source.kaskade.examples;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pub.source.kaskade.ModelRef;
import pub.source.kaskade.Transport;
import pub.source.kaskade.TransportResult;

/**
 * A deterministic, scripted {@link Transport} for examples and tests.
 *
 * <p>Replies are queued per <em>model id</em> (the {@link ModelRef#model()}
 * part, so model names must be unique within one chain). Each call consumes
 * the next queued reply; the last queued reply repeats forever, which keeps
 * repeated runs over the same stub deterministic. A model without scripted
 * replies fails like a broken endpoint, which is sometimes exactly what a
 * test wants.</p>
 */
public final class StubTransport implements Transport {

    private final Map<String, Deque<Reply>> replies = new HashMap<>();
    private final List<Call> calls = new ArrayList<>();

    /**
     * One scripted reply: either a response ({@code failure == null}) or an
     * exception to throw when the model is called.
     *
     * @param content the raw model response, ignored when {@code failure} is set
     * @param costUsd what the call costs in USD; {@code 0.0} for free models
     * @param failure exception to throw instead of answering, may be null
     */
    public record Reply(String content, double costUsd, RuntimeException failure) {
    }

    /**
     * One recorded call, for asserting which step reached the transport.
     *
     * @param model  the model the engine asked for
     * @param prompt the prompt the engine delivered
     */
    public record Call(ModelRef model, String prompt) {
    }

    /**
     * Scripts a free response for the given model.
     */
    public StubTransport respond(String model, String content) {
        return respond(model, content, 0.0);
    }

    /**
     * Scripts a response with a specific cost for the given model.
     */
    public StubTransport respond(String model, String content, double costUsd) {
        queue(model).addLast(new Reply(content, costUsd, null));
        return this;
    }

    /**
     * Scripts a transport-level failure for the given model.
     */
    public StubTransport fail(String model, RuntimeException failure) {
        queue(model).addLast(new Reply(null, 0.0, failure));
        return this;
    }

    @Override
    public TransportResult call(ModelRef model, String prompt) {
        calls.add(new Call(model, prompt));
        Deque<Reply> queue = replies.get(model.model());
        if (queue == null || queue.isEmpty()) {
            throw new IllegalStateException("no scripted reply for model '" + model.model() + "'");
        }
        Reply reply = queue.size() == 1 ? queue.peekFirst() : queue.pollFirst();
        if (reply.failure() != null) {
            throw reply.failure();
        }
        return new TransportResult(reply.content(), reply.costUsd());
    }

    /**
     * @return every call in order, for assertions and debugging
     */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /**
     * @return the model ids of every call in order, the cheapest way to prove
     *         which step answered
     */
    public List<String> calledModels() {
        return calls.stream().map(call -> call.model().model()).toList();
    }

    private Deque<Reply> queue(String model) {
        return replies.computeIfAbsent(model, ignored -> new ArrayDeque<>());
    }
}
