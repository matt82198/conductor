package dev.conductor.server.agent;

import dev.conductor.common.StreamJsonEvent;
import dev.conductor.common.StreamJsonEvent.*;
import dev.conductor.server.agent.AgentOutputStore.OutputEntry;
import dev.conductor.server.process.ClaudeProcessManager.AgentStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AgentOutputStore}.
 * Validates storage, retrieval, pagination, capping, and clearing of agent output.
 */
class AgentOutputStoreTest {

    private AgentOutputStore store;

    @BeforeEach
    void setUp() {
        store = new AgentOutputStore();
    }

    // ─── Basic storage and retrieval ─────────────────────────────────

    @Test
    void storesOutput_retrievable() {
        UUID agentId = UUID.randomUUID();

        store.onAgentStreamEvent(new AgentStreamEvent(agentId, createTextEvent("Hello world")));
        store.onAgentStreamEvent(new AgentStreamEvent(agentId, createThinkingEvent("Let me think...")));

        List<OutputEntry> output = store.getOutput(agentId);
        assertEquals(2, output.size());
        assertEquals("text", output.get(0).type());
        assertEquals("Hello world", output.get(0).content());
        assertEquals("thinking", output.get(1).type());
        assertEquals("Let me think...", output.get(1).content());
    }

    @Test
    void storesToolUseEvent_withToolName() {
        UUID agentId = UUID.randomUUID();

        store.onAgentStreamEvent(new AgentStreamEvent(agentId,
                createToolUseEvent("Read", Map.of("file", "test.java"))));

        List<OutputEntry> output = store.getOutput(agentId);
        assertEquals(1, output.size());
        assertEquals("tool_use", output.get(0).type());
        assertEquals("Read", output.get(0).toolName());
        assertFalse(output.get(0).isError());
    }

    @Test
    void storesToolResultEvent() {
        UUID agentId = UUID.randomUUID();

        UserEvent toolResult = new UserEvent("sess-1", "uuid-1", "tool-use-1",
                "File contents here", false, null, null);
        store.onAgentStreamEvent(new AgentStreamEvent(agentId, toolResult));

        List<OutputEntry> output = store.getOutput(agentId);
        assertEquals(1, output.size());
        assertEquals("tool_result", output.get(0).type());
        assertEquals("File contents here", output.get(0).content());
        assertFalse(output.get(0).isError());
    }

    @Test
    void storesToolResultError() {
        UUID agentId = UUID.randomUUID();

        UserEvent toolError = new UserEvent("sess-1", "uuid-1", "tool-use-1",
                "Permission denied", true, null, null);
        store.onAgentStreamEvent(new AgentStreamEvent(agentId, toolError));

        List<OutputEntry> output = store.getOutput(agentId);
        assertEquals(1, output.size());
        assertTrue(output.get(0).isError());
    }

    @Test
    void storesSystemInitEvent() {
        UUID agentId = UUID.randomUUID();

        SystemInitEvent init = new SystemInitEvent("sess-1", "uuid-1", "claude-sonnet-4-6",
                List.of("Read", "Write", "Bash"), "1.0.0", "/home/project", "default", null);
        store.onAgentStreamEvent(new AgentStreamEvent(agentId, init));

        List<OutputEntry> output = store.getOutput(agentId);
        assertEquals(1, output.size());
        assertEquals("system", output.get(0).type());
        assertTrue(output.get(0).content().contains("claude-sonnet-4-6"));
        assertTrue(output.get(0).content().contains("tools=3"));
    }

    @Test
    void storesResultEvent() {
        UUID agentId = UUID.randomUUID();

        ResultEvent result = new ResultEvent("sess-1", "uuid-1", "success", false,
                0.05, 30000L, 25000L, 5, "Task completed", "end_turn",
                1000, 500, null);
        store.onAgentStreamEvent(new AgentStreamEvent(agentId, result));

        List<OutputEntry> output = store.getOutput(agentId);
        assertEquals(1, output.size());
        assertEquals("result", output.get(0).type());
        assertTrue(output.get(0).content().contains("Task completed"));
        assertFalse(output.get(0).isError());
    }

    @Test
    void storesParseErrorEvent() {
        UUID agentId = UUID.randomUUID();

        ParseErrorEvent error = new ParseErrorEvent("sess-1", "uuid-1",
                "Invalid JSON", "{bad json}");
        store.onAgentStreamEvent(new AgentStreamEvent(agentId, error));

        List<OutputEntry> output = store.getOutput(agentId);
        assertEquals(1, output.size());
        assertEquals("error", output.get(0).type());
        assertTrue(output.get(0).isError());
    }

    // ─── Capping ─────────────────────────────────────────────────────

    @Test
    void capsAt500PerAgent() {
        UUID agentId = UUID.randomUUID();

        // Add 510 entries
        for (int i = 0; i < 510; i++) {
            store.onAgentStreamEvent(new AgentStreamEvent(agentId,
                    createTextEvent("Message " + i)));
        }

        List<OutputEntry> output = store.getOutput(agentId);
        assertEquals(AgentOutputStore.MAX_ENTRIES_PER_AGENT, output.size());

        // Verify oldest entries were evicted (first entry should be "Message 10")
        assertEquals("Message 10", output.get(0).content());
        assertEquals("Message 509", output.get(output.size() - 1).content());
    }

    @Test
    void differentAgents_haveIndependentCaps() {
        UUID agent1 = UUID.randomUUID();
        UUID agent2 = UUID.randomUUID();

        for (int i = 0; i < 10; i++) {
            store.onAgentStreamEvent(new AgentStreamEvent(agent1, createTextEvent("A1-" + i)));
        }
        for (int i = 0; i < 5; i++) {
            store.onAgentStreamEvent(new AgentStreamEvent(agent2, createTextEvent("A2-" + i)));
        }

        assertEquals(10, store.getOutput(agent1).size());
        assertEquals(5, store.getOutput(agent2).size());
    }

    // ─── Clear ───────────────────────────────────────────────────────

    @Test
    void clear_removesAllForAgent() {
        UUID agentId = UUID.randomUUID();

        store.onAgentStreamEvent(new AgentStreamEvent(agentId, createTextEvent("Hello")));
        store.onAgentStreamEvent(new AgentStreamEvent(agentId, createTextEvent("World")));
        assertEquals(2, store.getOutput(agentId).size());

        store.clear(agentId);
        assertTrue(store.getOutput(agentId).isEmpty());
    }

    @Test
    void clear_doesNotAffectOtherAgents() {
        UUID agent1 = UUID.randomUUID();
        UUID agent2 = UUID.randomUUID();

        store.onAgentStreamEvent(new AgentStreamEvent(agent1, createTextEvent("A1")));
        store.onAgentStreamEvent(new AgentStreamEvent(agent2, createTextEvent("A2")));

        store.clear(agent1);
        assertTrue(store.getOutput(agent1).isEmpty());
        assertEquals(1, store.getOutput(agent2).size());
    }

    // ─── Pagination ──────────────────────────────────────────────────

    @Test
    void getOutput_pagination() {
        UUID agentId = UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            store.onAgentStreamEvent(new AgentStreamEvent(agentId, createTextEvent("Msg " + i)));
        }

        // First page
        List<OutputEntry> page1 = store.getOutput(agentId, 0, 5);
        assertEquals(5, page1.size());
        assertEquals("Msg 0", page1.get(0).content());
        assertEquals("Msg 4", page1.get(4).content());

        // Second page
        List<OutputEntry> page2 = store.getOutput(agentId, 5, 5);
        assertEquals(5, page2.size());
        assertEquals("Msg 5", page2.get(0).content());

        // Last partial page
        List<OutputEntry> lastPage = store.getOutput(agentId, 18, 5);
        assertEquals(2, lastPage.size());
        assertEquals("Msg 18", lastPage.get(0).content());
        assertEquals("Msg 19", lastPage.get(1).content());
    }

    @Test
    void getOutput_offsetBeyondSize_returnsEmpty() {
        UUID agentId = UUID.randomUUID();

        store.onAgentStreamEvent(new AgentStreamEvent(agentId, createTextEvent("Hello")));

        List<OutputEntry> result = store.getOutput(agentId, 100, 10);
        assertTrue(result.isEmpty());
    }

    // ─── Unknown agent ───────────────────────────────────────────────

    @Test
    void getOutput_unknownAgent_returnsEmpty() {
        UUID unknownId = UUID.randomUUID();
        assertTrue(store.getOutput(unknownId).isEmpty());
        assertTrue(store.getOutput(unknownId, 0, 10).isEmpty());
        assertEquals(0, store.size(unknownId));
    }

    // ─── Size ────────────────────────────────────────────────────────

    @Test
    void size_tracksCorrectly() {
        UUID agentId = UUID.randomUUID();

        assertEquals(0, store.size(agentId));

        store.onAgentStreamEvent(new AgentStreamEvent(agentId, createTextEvent("A")));
        assertEquals(1, store.size(agentId));

        store.onAgentStreamEvent(new AgentStreamEvent(agentId, createTextEvent("B")));
        assertEquals(2, store.size(agentId));

        store.clear(agentId);
        assertEquals(0, store.size(agentId));
    }

    // ─── Returned list is immutable ──────────────────────────────────

    @Test
    void getOutput_returnsImmutableCopy() {
        UUID agentId = UUID.randomUUID();

        store.onAgentStreamEvent(new AgentStreamEvent(agentId, createTextEvent("Hello")));

        List<OutputEntry> output = store.getOutput(agentId);
        assertThrows(UnsupportedOperationException.class, () -> output.add(
                new OutputEntry(null, "text", "injected", null, false)));
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private AssistantEvent createTextEvent(String text) {
        return new AssistantEvent("sess-1", "uuid-1", "msg-1", "claude-sonnet-4-6",
                "text", text, null, null, null, null, 0, 0, null);
    }

    private AssistantEvent createThinkingEvent(String text) {
        return new AssistantEvent("sess-1", "uuid-1", "msg-1", "claude-sonnet-4-6",
                "thinking", text, null, null, null, null, 0, 0, null);
    }

    private AssistantEvent createToolUseEvent(String toolName, Map<String, Object> input) {
        return new AssistantEvent("sess-1", "uuid-1", "msg-1", "claude-sonnet-4-6",
                "tool_use", null, toolName, "tu-1", input, null, 0, 0, null);
    }
}
