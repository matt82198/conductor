package dev.conductor.server.queue;

import dev.conductor.common.StreamJsonEvent;
import dev.conductor.server.agent.AgentRecord;
import dev.conductor.server.agent.AgentRegistry;
import dev.conductor.server.process.ClaudeProcessManager.AgentStreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Orchestrates the message queue pipeline: classify -> dedup -> batch -> filter -> publish.
 *
 * <p>Listens for {@link AgentStreamEvent}s from the process layer and runs each
 * event through the pipeline stages. Messages that survive all stages are published
 * as {@link QueuedMessageEvent}s via Spring's ApplicationEventPublisher.
 *
 * <p>Also maintains a bounded in-memory history of recent messages for
 * {@link #getMessages(Instant, int)} queries.
 *
 * <p>Thread-safe: runs on the Spring event thread. Internal state uses concurrent
 * data structures.
 */
@Service
public class QueueManager {

    private static final Logger log = LoggerFactory.getLogger(QueueManager.class);

    /** Maximum number of messages retained in the in-memory history. */
    private static final int MAX_HISTORY = 1000;

    private final MessageClassifier classifier;
    private final MessageDeduplicator deduplicator;
    private final MessageBatcher batcher;
    private final NoiseFilter noiseFilter;
    private final MuteRegistry muteRegistry;
    private final AgentRegistry agentRegistry;
    private final ApplicationEventPublisher eventPublisher;

    /** Bounded in-memory history of recent messages. Most recent at the front. */
    private final ConcurrentLinkedDeque<QueuedMessage> messageHistory = new ConcurrentLinkedDeque<>();

    public QueueManager(
            MessageClassifier classifier,
            MessageDeduplicator deduplicator,
            MessageBatcher batcher,
            NoiseFilter noiseFilter,
            MuteRegistry muteRegistry,
            AgentRegistry agentRegistry,
            ApplicationEventPublisher eventPublisher
    ) {
        this.classifier = classifier;
        this.deduplicator = deduplicator;
        this.batcher = batcher;
        this.noiseFilter = noiseFilter;
        this.muteRegistry = muteRegistry;
        this.agentRegistry = agentRegistry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Wires the batcher's emit callback to the post-batch pipeline stages.
     * Runs after dependency injection is complete.
     */
    @PostConstruct
    void init() {
        batcher.setEmitCallback(this::onBatchEmit);
        log.info("QueueManager initialized: classify -> dedup -> batch -> filter -> publish");
    }

    /**
     * Receives raw agent events from the process layer and runs them through
     * the full pipeline.
     *
     * <p>Pipeline stages:
     * <ol>
     *   <li>Mute check -- drop if agent is muted</li>
     *   <li>Classify -- assign urgency + category</li>
     *   <li>Dedup -- drop if duplicate within 60s window</li>
     *   <li>Batch -- accumulate into 30s windows</li>
     * </ol>
     *
     * <p>After the batcher emits (either immediately for CRITICAL, or after
     * the batch window), the message continues through:
     * <ol start="5">
     *   <li>Noise filter -- drop NOISE unless verbose</li>
     *   <li>Publish -- emit QueuedMessageEvent + add to history</li>
     * </ol>
     *
     * @param agentEvent the raw event from the Claude CLI stream
     */
    @EventListener
    public void onAgentStreamEvent(AgentStreamEvent agentEvent) {
        UUID agentId = agentEvent.agentId();
        StreamJsonEvent rawEvent = agentEvent.event();

        // Stage 1: Mute check
        if (muteRegistry.isMuted(agentId)) {
            log.trace("Dropped muted agent message: agent={}", agentId);
            return;
        }

        // Stage 2: Classify
        MessageClassifier.Classification classification = classifier.classify(rawEvent);

        // Resolve agent name from registry
        String agentName = agentRegistry.get(agentId)
                .map(AgentRecord::name)
                .orElse("agent-" + agentId.toString().substring(0, 8));

        // Stage 3: Dedup
        String dedupHash = deduplicator.computeHash(agentId, classification.category(), classification.text());
        if (deduplicator.isDuplicate(agentId, classification.category(), classification.text())) {
            log.trace("Dropped duplicate: agent={} category={}", agentId, classification.category());
            return;
        }

        // Build the QueuedMessage
        QueuedMessage message = new QueuedMessage(
                agentId,
                agentName,
                classification.text(),
                classification.urgency(),
                classification.category(),
                Instant.now(),
                dedupHash,
                null  // batchId is null; set by batcher if digested
        );

        // Stage 4: Submit to batcher (may emit immediately for CRITICAL, or hold for batch window)
        batcher.submit(message);
    }

    /**
     * Returns recent messages since the given timestamp, up to the limit.
     * Messages are returned in chronological order (oldest first).
     *
     * @param since only return messages after this timestamp (inclusive); null for all
     * @param limit maximum number of messages to return; 0 or negative for unlimited
     * @return a list of recent messages, chronologically ordered
     */
    public List<QueuedMessage> getMessages(Instant since, int limit) {
        List<QueuedMessage> result = new ArrayList<>();

        for (QueuedMessage msg : messageHistory) {
            if (since != null && msg.timestamp().isBefore(since)) {
                continue;
            }
            result.add(msg);
        }

        // messageHistory is newest-first; reverse to chronological
        result.sort(Comparator.comparing(QueuedMessage::timestamp));

        if (limit > 0 && result.size() > limit) {
            return result.subList(result.size() - limit, result.size());
        }
        return result;
    }

    /**
     * Returns the total number of messages in the in-memory history.
     */
    public int historySize() {
        return messageHistory.size();
    }

    // ─── Internal: post-batch pipeline ─────────────────────────────────

    /**
     * Called by the batcher when a message is emitted (either immediately for
     * CRITICAL messages, or after a batch window flush).
     * Runs the noise filter and then publishes.
     */
    private void onBatchEmit(QueuedMessage message) {
        // Stage 5: Noise filter
        if (!noiseFilter.shouldKeep(message)) {
            return;
        }

        // Stage 6: Add to history and publish
        addToHistory(message);
        eventPublisher.publishEvent(new QueuedMessageEvent(message));
        log.debug("Published QueuedMessageEvent: agent={} urgency={} category={}",
                message.agentName(), message.urgency(), message.category());
    }

    /**
     * Adds a message to the bounded in-memory history. Evicts the oldest
     * message if the history exceeds MAX_HISTORY.
     */
    private void addToHistory(QueuedMessage message) {
        messageHistory.addFirst(message);
        while (messageHistory.size() > MAX_HISTORY) {
            messageHistory.pollLast();
        }
    }
}
