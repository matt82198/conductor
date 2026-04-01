package dev.conductor.server.humaninput;

/**
 * Spring application event published when an agent is detected as needing human input.
 *
 * <p>Consumed by:
 * <ul>
 *   <li>notification/ — routes as CRITICAL urgency to the UI</li>
 *   <li>api/ — pushes to the WebSocket for real-time display</li>
 * </ul>
 *
 * @param request the detected human input request with full context
 */
public record HumanInputNeededEvent(HumanInputRequest request) {

    public HumanInputNeededEvent {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
    }
}
