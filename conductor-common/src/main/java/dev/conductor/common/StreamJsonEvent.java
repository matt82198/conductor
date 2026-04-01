package dev.conductor.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Sealed interface representing the 5 event types emitted by
 * {@code claude --output-format stream-json}.
 *
 * <p>Event lifecycle per session:
 * <pre>
 * SystemInitEvent -> [AssistantEvent -> UserEvent]* -> RateLimitEvent -> ResultEvent
 * </pre>
 *
 * <p>Each concrete type is a record that captures the fields Conductor needs.
 * Fields not required for orchestration are omitted; the raw JSON line is
 * available via {@link #rawJson()} for full-fidelity logging.
 *
 * @see <a href="spikes/spike3-stream-json/STREAM_JSON_SCHEMA.md">Stream JSON Schema</a>
 */
public sealed interface StreamJsonEvent {

    /** The session this event belongs to. */
    String sessionId();

    /** The unique event identifier. */
    String uuid();

    /** The raw JSON line from stdout, preserved for audit logging. */
    String rawJson();


    // ─── 1. system (subtype: init) ─────────────────────────────────────

    /**
     * First event in every session. Contains session metadata, model info,
     * available tools, and CLI version.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SystemInitEvent(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("uuid") String uuid,
            @JsonProperty("model") String model,
            @JsonProperty("tools") List<String> tools,
            @JsonProperty("claude_code_version") String claudeCodeVersion,
            @JsonProperty("cwd") String cwd,
            @JsonProperty("permissionMode") String permissionMode,
            String rawJson
    ) implements StreamJsonEvent {

        /** Compact constructor that defaults rawJson to empty if not supplied. */
        public SystemInitEvent {
            if (rawJson == null) rawJson = "";
        }
    }


    // ─── 2. assistant ──────────────────────────────────────────────────

    /**
     * An assistant message carrying exactly ONE content block.
     * Content type is one of: {@code thinking}, {@code text}, {@code tool_use}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AssistantEvent(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("uuid") String uuid,
            String messageId,
            String model,
            String contentType,
            String textContent,
            String toolName,
            String toolUseId,
            Map<String, Object> toolInput,
            String stopReason,
            int inputTokens,
            int outputTokens,
            String rawJson
    ) implements StreamJsonEvent {

        public AssistantEvent {
            if (rawJson == null) rawJson = "";
        }

        /** True if this event carries a thinking block. */
        public boolean isThinking() {
            return "thinking".equals(contentType);
        }

        /** True if this event carries a text response block. */
        public boolean isText() {
            return "text".equals(contentType);
        }

        /** True if this event carries a tool_use block. */
        public boolean isToolUse() {
            return "tool_use".equals(contentType);
        }
    }


    // ─── 3. user (tool_result) ─────────────────────────────────────────

    /**
     * Tool result flowing back to the agent after a tool_use.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserEvent(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("uuid") String uuid,
            String toolUseId,
            String content,
            boolean isError,
            String timestamp,
            String rawJson
    ) implements StreamJsonEvent {

        public UserEvent {
            if (rawJson == null) rawJson = "";
        }
    }


    // ─── 4. rate_limit_event ───────────────────────────────────────────

    /**
     * Rate limit status check. Appears between assistant/user pairs.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RateLimitEvent(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("uuid") String uuid,
            String status,
            long resetsAt,
            String rateLimitType,
            boolean isUsingOverage,
            String rawJson
    ) implements StreamJsonEvent {

        public RateLimitEvent {
            if (rawJson == null) rawJson = "";
        }

        /** True if the agent is currently allowed to make API calls. */
        public boolean isAllowed() {
            return "allowed".equals(status);
        }
    }


    // ─── 5. result ─────────────────────────────────────────────────────

    /**
     * Final event in a session. Contains aggregate cost, duration, and usage.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultEvent(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("uuid") String uuid,
            @JsonProperty("subtype") String subtype,
            @JsonProperty("is_error") boolean isError,
            @JsonProperty("total_cost_usd") double totalCostUsd,
            @JsonProperty("duration_ms") long durationMs,
            @JsonProperty("duration_api_ms") long durationApiMs,
            @JsonProperty("num_turns") int numTurns,
            @JsonProperty("result") String resultText,
            @JsonProperty("stop_reason") String stopReason,
            int inputTokens,
            int outputTokens,
            String rawJson
    ) implements StreamJsonEvent {

        public ResultEvent {
            if (rawJson == null) rawJson = "";
        }

        /** True if the session completed successfully. */
        public boolean isSuccess() {
            return "success".equals(subtype) && !isError;
        }
    }


    // ─── Error fallback ────────────────────────────────────────────────

    /**
     * Fallback for lines that could not be parsed into a known event type.
     * This ensures the parser never drops data silently.
     */
    record ParseErrorEvent(
            String sessionId,
            String uuid,
            String errorMessage,
            String rawJson
    ) implements StreamJsonEvent {

        public ParseErrorEvent {
            if (sessionId == null) sessionId = "unknown";
            if (uuid == null) uuid = "unknown";
            if (rawJson == null) rawJson = "";
        }
    }
}
