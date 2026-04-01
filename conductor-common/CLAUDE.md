# conductor-common — Shared Types Library

## Responsibility
Shared enums, records, and event contracts used by ALL other modules. **No Spring dependency.**

## Status: Complete

## Contracts Provided (other domains import these)

### AgentState — Lifecycle enum
```
LAUNCHING → ACTIVE → THINKING / USING_TOOL / BLOCKED → COMPLETED / FAILED
```
Methods: `isTerminal()`, `isAlive()`

### AgentRole — Role enum
```
FEATURE_ENGINEER, TESTER, REFACTORER, REVIEWER, EXPLORER, GENERAL
```

### StreamJsonEvent — Sealed interface (5 CLI event types + error fallback)
```
SystemInitEvent(sessionId, model, tools, version, rawJson)
AssistantEvent(contentType[THINKING|TEXT|TOOL_USE], messageId, text, toolName, toolInput, rawJson)
UserEvent(toolUseId, content, isError, rawJson)
RateLimitEvent(status, resetsAt, rateLimitType, rawJson)
ResultEvent(totalCostUsd, durationMs, numTurns, resultText, isError, inputTokens, outputTokens, rawJson)
ParseErrorEvent(rawLine, errorMessage)
```

**Key:** Each record carries `rawJson()` for full-fidelity audit logging. AssistantEvent has ONE content block per event.

## Rules
- No Spring annotations — plain Java library
- All types are records or enums — immutable
- Jackson annotations only for JSON serialization
- Adding a new event type? Add to sealed interface, update `StreamJsonParser` in process/

## Build
```bash
./mvnw package -pl conductor-common -DskipTests
```
