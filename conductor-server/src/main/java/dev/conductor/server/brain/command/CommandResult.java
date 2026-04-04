package dev.conductor.server.brain.command;

/**
 * Result of executing a {@link CommandIntent}.
 *
 * <p>Contains a human-readable message for display in the UI and optionally
 * structured data (e.g., an {@code AgentRecord}, {@code ProjectRecord}, etc.)
 * for programmatic consumption.
 *
 * @param success whether the command executed successfully
 * @param message human-readable response to show in the command bar
 * @param data    optional structured data (serialized to JSON in the REST response)
 */
public record CommandResult(
        boolean success,
        String message,
        Object data
) {

    /**
     * Convenience constructor for results without structured data.
     */
    public CommandResult(boolean success, String message) {
        this(success, message, null);
    }
}
