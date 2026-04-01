package dev.conductor.server.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Groups similar messages into 30-second digest windows.
 *
 * <p>Messages with the same {@code (agentId, urgency)} arriving within a 30-second
 * window are accumulated. When the window expires (via a scheduled flush), if more
 * than 3 messages accumulated, a single digest message is emitted instead of the
 * individual messages.
 *
 * <p>If 3 or fewer messages arrive, each is emitted individually when the batch
 * flushes. CRITICAL messages are never batched -- they bypass immediately.
 *
 * <p>Thread-safe: all state is in {@link ConcurrentHashMap} with synchronized
 * access per batch key.
 */
@Service
public class MessageBatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageBatcher.class);

    /** Duration in milliseconds for a batch window. */
    private static final long BATCH_WINDOW_MS = 30_000L;

    /** Minimum number of messages before we emit a digest instead of individuals. */
    private static final int DIGEST_THRESHOLD = 3;

    /**
     * Active batches, keyed by (agentId + urgency). Each batch holds messages
     * that arrived during the current window.
     */
    private final ConcurrentHashMap<String, Batch> activeBatches = new ConcurrentHashMap<>();

    /**
     * Callback for emitting messages that pass through the batcher.
     * Set by QueueManager during initialization.
     */
    private volatile Consumer<QueuedMessage> emitCallback;

    /**
     * Sets the callback invoked when a message (individual or digest) is emitted
     * from the batcher.
     *
     * @param callback the consumer that receives emitted messages
     */
    public void setEmitCallback(Consumer<QueuedMessage> callback) {
        this.emitCallback = callback;
    }

    /**
     * Submits a message to the batcher.
     *
     * <p>CRITICAL messages bypass batching entirely and are emitted immediately.
     * All other messages are accumulated in a batch keyed by (agentId, urgency).
     *
     * @param message the message to batch or pass through
     */
    public void submit(QueuedMessage message) {
        // CRITICAL messages bypass batching -- emit immediately
        if (message.urgency() == Urgency.CRITICAL) {
            emit(message);
            return;
        }

        String batchKey = batchKey(message.agentId(), message.urgency());

        activeBatches.compute(batchKey, (key, existing) -> {
            if (existing == null) {
                Batch batch = new Batch(message.agentId(), message.agentName(), message.urgency());
                batch.add(message);
                return batch;
            }
            existing.add(message);
            return existing;
        });
    }

    /**
     * Periodically flushes all batches whose window has expired.
     * Runs every 5 seconds to check for expired batches.
     */
    @Scheduled(fixedRate = 5_000)
    public void flush() {
        Instant now = Instant.now();

        activeBatches.entrySet().removeIf(entry -> {
            Batch batch = entry.getValue();
            long ageMs = now.toEpochMilli() - batch.createdAt.toEpochMilli();

            if (ageMs < BATCH_WINDOW_MS) {
                return false; // Not yet expired
            }

            // Batch window expired -- emit results
            emitBatch(batch);
            return true; // Remove from map
        });
    }

    /**
     * Forces an immediate flush of all active batches regardless of window age.
     * Visible for testing.
     */
    public void flushAll() {
        activeBatches.entrySet().removeIf(entry -> {
            emitBatch(entry.getValue());
            return true;
        });
    }

    /**
     * Returns the number of currently active batches.
     * Visible for testing.
     */
    public int activeBatchCount() {
        return activeBatches.size();
    }

    // ─── Internal ──────────────────────────────────────────────────────

    /**
     * Emits the contents of a batch. If the batch has more than DIGEST_THRESHOLD
     * messages, a single digest message is emitted. Otherwise, individual messages
     * are emitted.
     */
    private void emitBatch(Batch batch) {
        List<QueuedMessage> messages = batch.drain();

        if (messages.isEmpty()) {
            return;
        }

        if (messages.size() > DIGEST_THRESHOLD) {
            // Emit a single digest
            QueuedMessage digest = createDigest(batch, messages);
            emit(digest);
            log.debug("Emitted digest: agent={} urgency={} count={}",
                    batch.agentName, batch.urgency, messages.size());
        } else {
            // Emit each individually
            for (QueuedMessage message : messages) {
                emit(message);
            }
            log.debug("Emitted {} individual messages: agent={} urgency={}",
                    messages.size(), batch.agentName, batch.urgency);
        }
    }

    /**
     * Creates a digest message summarizing a batch of similar messages.
     */
    private QueuedMessage createDigest(Batch batch, List<QueuedMessage> messages) {
        // Count messages by category
        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (QueuedMessage msg : messages) {
            categoryCounts.merge(msg.category(), 1, Integer::sum);
        }

        // Build summary text: "agent-name: 5 MEDIUM events (tool_use x3, text x2)"
        StringBuilder summary = new StringBuilder();
        summary.append(batch.agentName).append(": ");
        summary.append(messages.size()).append(" ").append(batch.urgency).append(" events (");

        StringJoiner joiner = new StringJoiner(", ");
        categoryCounts.forEach((cat, count) ->
                joiner.add(cat + " x" + count));
        summary.append(joiner);
        summary.append(")");

        String batchId = UUID.randomUUID().toString();

        return new QueuedMessage(
                batch.agentId,
                batch.agentName,
                summary.toString(),
                batch.urgency,
                "digest",
                Instant.now(),
                null, // no dedup hash for digests
                batchId
        );
    }

    private void emit(QueuedMessage message) {
        Consumer<QueuedMessage> callback = this.emitCallback;
        if (callback != null) {
            callback.accept(message);
        } else {
            log.warn("MessageBatcher has no emit callback set -- dropping message: {}", message.category());
        }
    }

    private static String batchKey(UUID agentId, Urgency urgency) {
        return agentId.toString() + "|" + urgency.name();
    }

    // ─── Inner batch container ─────────────────────────────────────────

    /**
     * Holds accumulated messages for a single (agentId, urgency) batch window.
     * Access is synchronized via ConcurrentHashMap.compute().
     */
    private static class Batch {
        final UUID agentId;
        final String agentName;
        final Urgency urgency;
        final Instant createdAt;
        final List<QueuedMessage> messages;

        Batch(UUID agentId, String agentName, Urgency urgency) {
            this.agentId = agentId;
            this.agentName = agentName;
            this.urgency = urgency;
            this.createdAt = Instant.now();
            this.messages = new ArrayList<>();
        }

        void add(QueuedMessage message) {
            messages.add(message);
        }

        List<QueuedMessage> drain() {
            List<QueuedMessage> copy = new ArrayList<>(messages);
            messages.clear();
            return copy;
        }
    }
}
