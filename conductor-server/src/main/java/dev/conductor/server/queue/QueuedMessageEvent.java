package dev.conductor.server.queue;

/**
 * Spring application event published when a {@link QueuedMessage} passes
 * through the full queue pipeline (classify, dedup, batch, filter).
 *
 * <p>Consumed by:
 * <ul>
 *   <li>{@code notification/} — routes alerts by urgency to UI/desktop</li>
 *   <li>{@code api/} — broadcasts to WebSocket clients</li>
 * </ul>
 *
 * @param message the classified, filtered message
 */
public record QueuedMessageEvent(QueuedMessage message) {}
