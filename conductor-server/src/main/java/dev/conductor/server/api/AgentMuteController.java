package dev.conductor.server.api;

import dev.conductor.server.brain.behavior.BehaviorLogger;
import dev.conductor.server.queue.MuteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for per-agent mute operations.
 *
 * <p>Provides endpoints for muting and unmuting agents, which controls
 * whether their output flows through the queue pipeline. Muted agents'
 * messages are dropped before classification in the {@link dev.conductor.server.queue.QueueManager}.
 *
 * <p>Mounted under {@code /api/agents} to colocate with the main
 * {@link AgentController} endpoints. Spring merges mappings from
 * multiple controllers at the same prefix without conflict as long
 * as individual paths are unique.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentMuteController {

    private static final Logger log = LoggerFactory.getLogger(AgentMuteController.class);

    private final MuteRegistry muteRegistry;
    private final BehaviorLogger behaviorLogger; // nullable — brain module may not be enabled

    public AgentMuteController(MuteRegistry muteRegistry,
                               @Autowired(required = false) BehaviorLogger behaviorLogger) {
        this.muteRegistry = muteRegistry;
        this.behaviorLogger = behaviorLogger;
    }

    // ─── Mute / Unmute ────────────────────────────────────────────────

    /**
     * Sets the mute state for an agent.
     *
     * <p>Request body:
     * <pre>
     * { "muted": true }
     * </pre>
     *
     * <p>When muted, all messages from this agent are dropped at the
     * head of the queue pipeline. When unmuted, messages flow normally.
     *
     * @param id      the agent UUID
     * @param request body containing the desired mute state
     * @return the agent ID and resulting mute state
     */
    @PostMapping("/{id}/mute")
    public ResponseEntity<?> setMuteState(@PathVariable UUID id, @RequestBody MuteRequest request) {
        if (request.muted()) {
            muteRegistry.mute(id);
            log.info("Agent {} muted via REST", id);
        } else {
            muteRegistry.unmute(id);
            log.info("Agent {} unmuted via REST", id);
        }
        boolean muted = muteRegistry.isMuted(id);
        if (behaviorLogger != null) {
            behaviorLogger.logMute(id, muted);
        }
        return ResponseEntity.ok(Map.of(
                "agentId", id.toString(),
                "muted", muted
        ));
    }

    /**
     * Returns the current mute state for an agent.
     *
     * @param id the agent UUID
     * @return JSON with a single {@code muted} boolean field
     */
    @GetMapping("/{id}/muted")
    public ResponseEntity<?> getMuteState(@PathVariable UUID id) {
        return ResponseEntity.ok(Map.of("muted", muteRegistry.isMuted(id)));
    }

    record MuteRequest(boolean muted) {}
}
