package dev.conductor.server.process;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Holds the OS process handle and I/O streams for a single Claude CLI agent.
 *
 * <p>The stdout reader is consumed by a dedicated virtual thread in
 * {@link ClaudeProcessManager}. The stdin stream is used by
 * {@link ClaudeProcessManager#sendMessage} to pipe follow-up messages.
 *
 * @param agentId  the agent's UUID in the registry
 * @param process  the OS process handle
 * @param stdin    output stream connected to the process's stdin
 * @param stdout   buffered reader connected to the process's stdout
 */
public record ManagedProcess(
        UUID agentId,
        Process process,
        OutputStream stdin,
        BufferedReader stdout
) {

    /**
     * Returns true if the underlying OS process is still running.
     */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Forcibly destroys the underlying OS process.
     */
    public void destroyForcibly() {
        process.destroyForcibly();
    }
}
