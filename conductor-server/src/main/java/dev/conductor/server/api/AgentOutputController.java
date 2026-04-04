package dev.conductor.server.api;

import dev.conductor.server.agent.AgentOutputStore;
import dev.conductor.server.agent.AgentOutputStore.OutputEntry;
import dev.conductor.server.agent.AgentRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for retrieving full agent output history.
 *
 * <p>Provides paginated access to the complete output of each agent,
 * including thinking blocks, text responses, tool uses, tool results,
 * and system events. This complements the real-time WebSocket event feed
 * by allowing the UI to fetch full conversation history on demand.
 *
 * <p>Mounts under {@code /api/agents} alongside {@link AgentController}
 * (Spring merges the mappings from multiple controllers).
 */
@RestController
@RequestMapping("/api/agents")
public class AgentOutputController {

    private final AgentRegistry registry;
    private final AgentOutputStore outputStore;

    public AgentOutputController(AgentRegistry registry, AgentOutputStore outputStore) {
        this.registry = registry;
        this.outputStore = outputStore;
    }

    /**
     * Returns the full output history for an agent, paginated.
     *
     * <p>Response is a JSON array of {@link OutputEntry} records:
     * <pre>
     * [
     *   {
     *     "timestamp": "2026-04-03T12:00:00Z",
     *     "type": "text",
     *     "content": "I'll implement the auth module...",
     *     "toolName": null,
     *     "isError": false
     *   },
     *   ...
     * ]
     * </pre>
     *
     * @param id     the agent UUID
     * @param offset pagination offset (default 0)
     * @param limit  maximum entries to return (default 100)
     * @return paginated output entries, or 404 if agent not found
     */
    @GetMapping("/{id}/output")
    public ResponseEntity<List<OutputEntry>> getOutput(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        if (registry.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(outputStore.getOutput(id, offset, limit));
    }

    /**
     * Returns the total number of output entries stored for an agent.
     *
     * @param id the agent UUID
     * @return count of output entries, or 404 if agent not found
     */
    @GetMapping("/{id}/output/count")
    public ResponseEntity<?> getOutputCount(@PathVariable UUID id) {
        if (registry.get(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(java.util.Map.of("count", outputStore.size(id)));
    }
}
