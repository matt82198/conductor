# Conductor WebSocket Event Protocol

**Version:** 1.0.0-draft
**Last updated:** 2026-03-31
**Status:** Contract specification (not yet implemented)

---

## Overview

The Conductor server and Electron UI communicate over a persistent WebSocket connection. The server pushes real-time events about agent activity, and the UI sends commands to control agents.

- **Endpoint:** `ws://localhost:8090/ws/events`
- **Protocol:** JSON text frames. One JSON object per WebSocket message.
- **Encoding:** UTF-8
- **Max message size:** 64 KB (messages exceeding this are truncated with a `truncated: true` flag)

---

## Table of Contents

1. [Message Envelope](#1-message-envelope)
2. [Server to Client Events](#2-server---client-events)
   - [AGENT_SPAWNED](#21-agent_spawned)
   - [AGENT_STATE_CHANGE](#22-agent_state_change)
   - [AGENT_OUTPUT](#23-agent_output)
   - [HUMAN_INPUT_NEEDED](#24-human_input_needed)
   - [COST_UPDATE](#25-cost_update)
   - [SYSTEM_STATUS](#26-system_status)
   - [ERROR](#27-error)
3. [Client to Server Commands](#3-client---server-commands)
   - [SPAWN_AGENT](#31-spawn_agent)
   - [KILL_AGENT](#32-kill_agent)
   - [SEND_MESSAGE](#33-send_message)
   - [MUTE_AGENT](#34-mute_agent)
   - [REQUEST_STATUS](#35-request_status)
4. [Message Ordering Guarantees](#4-message-ordering-guarantees)
5. [Reconnection Protocol](#5-reconnection-protocol)
6. [Agent States Reference](#6-agent-states-reference)
7. [Error Severity Levels](#7-error-severity-levels)
8. [Implementation Notes](#8-implementation-notes)

---

## 1. Message Envelope

Every message (both directions) shares a common envelope structure.

### Server -> Client envelope

```json
{
  "eventType": "<EVENT_TYPE>",
  "timestamp": "2026-04-01T12:00:00.000Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "/home/user/projects/my-app",
  "payload": { }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `eventType` | string | yes | One of the event types defined in Section 2 |
| `timestamp` | string (ISO 8601) | yes | Server wall-clock time when the event was generated |
| `agentId` | string (UUID) or `null` | yes | The agent this event pertains to. `null` for system-wide events (`SYSTEM_STATUS`, `COST_UPDATE`, system-level `ERROR`) |
| `projectPath` | string or `null` | yes | Absolute path to the project the agent is working in. `null` for system-wide events |
| `payload` | object | yes | Event-type-specific data. Structure defined per event type below |

### Client -> Server envelope

```json
{
  "commandType": "<COMMAND_TYPE>",
  "requestId": "client-generated-uuid",
  "payload": { }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `commandType` | string | yes | One of the command types defined in Section 3 |
| `requestId` | string (UUID) | yes | Client-generated ID for correlating acknowledgements. The server echoes this in its response |
| `payload` | object | yes | Command-type-specific data. Structure defined per command type below |

---

## 2. Server -> Client Events

### 2.1 AGENT_SPAWNED

Emitted when a new agent process has been created and registered in the agent registry. This fires once the CLI process is launched, before the agent produces any output.

```json
{
  "eventType": "AGENT_SPAWNED",
  "timestamp": "2026-04-01T12:00:00.123Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "name": "feature-auth-module",
    "role": "FEATURE_ENGINEER",
    "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "prompt": "Implement OAuth2 login flow with Google provider",
    "model": "claude-opus-4-6[1m]",
    "maxBudgetUsd": 5.00,
    "spawnedAt": "2026-04-01T12:00:00.123Z"
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `name` | string | Human-readable agent name (set by user or auto-generated) |
| `role` | string | Agent role. One of: `FEATURE_ENGINEER`, `REFACTORER`, `TESTER`, `REVIEWER`, `DEBUGGER`, `META_AGENT`, `GENERAL` |
| `sessionId` | string (UUID) | Claude CLI session ID. Used for resume on crash |
| `prompt` | string | The initial prompt sent to the agent |
| `model` | string | The Claude model being used |
| `maxBudgetUsd` | number or `null` | Cost cap for this agent. `null` means no cap |
| `spawnedAt` | string (ISO 8601) | Timestamp when the agent was created |

**UI should:** Add the agent to the agent list. Show it in `LAUNCHING` state. Display the name, role, and project.

---

### 2.2 AGENT_STATE_CHANGE

Emitted whenever an agent transitions between lifecycle states.

```json
{
  "eventType": "AGENT_STATE_CHANGE",
  "timestamp": "2026-04-01T12:00:05.456Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "previousState": "LAUNCHING",
    "newState": "ACTIVE",
    "reason": "CLI process started and system init event received"
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `previousState` | string | The state the agent was in. See [Section 6](#6-agent-states-reference) |
| `newState` | string | The state the agent is now in. See [Section 6](#6-agent-states-reference) |
| `reason` | string | Human-readable explanation of why the transition occurred |

**Valid state transitions:**

```
LAUNCHING -> ACTIVE     (CLI process started successfully)
ACTIVE    -> BLOCKED    (human input detected)
BLOCKED   -> ACTIVE     (human responded)
ACTIVE    -> COMPLETED  (agent finished task, result event received)
ACTIVE    -> FAILED     (process crashed or unrecoverable error)
BLOCKED   -> FAILED     (process crashed while waiting for input)
LAUNCHING -> FAILED     (CLI process failed to start)
*         -> KILLED     (user killed the agent via KILL_AGENT command)
```

**UI should:** Update the agent's state indicator. Change icon/color per state. If the new state is `BLOCKED`, highlight the agent to draw attention.

---

### 2.3 AGENT_OUTPUT

Emitted when the Conductor server parses structured content from an agent's `stream-json` output. This is the highest-volume event type. Each event carries one logical output unit.

There are five subtypes, each with a different payload shape.

#### 2.3.1 Subtype: `thinking`

The agent is reasoning through the problem. Extended thinking content.

```json
{
  "eventType": "AGENT_OUTPUT",
  "timestamp": "2026-04-01T12:00:06.100Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "subtype": "thinking",
    "messageId": "msg_01XFDUDYJgAACzvnptvVoYEL",
    "content": "The user wants OAuth2 with Google. I need to check if there's already a security config...",
    "truncated": false
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `subtype` | `"thinking"` | |
| `messageId` | string | Claude API message ID. Same across content blocks of one logical response |
| `content` | string | The thinking text. May be long |
| `truncated` | boolean | `true` if the content was cut to fit the 64 KB message limit |

**UI should:** Show a "thinking..." indicator on the agent. Optionally display the thinking content in an expandable panel. This is low-priority output -- do not surface prominently.

---

#### 2.3.2 Subtype: `text`

The agent produced text output (its actual response to the user/task).

```json
{
  "eventType": "AGENT_OUTPUT",
  "timestamp": "2026-04-01T12:00:10.200Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "subtype": "text",
    "messageId": "msg_01XFDUDYJgAACzvnptvVoYEL",
    "content": "I've created the OAuth2 configuration. Here are the files I created:\n\n1. `src/main/java/com/app/config/OAuth2Config.java` - Spring Security OAuth2 setup\n2. `src/main/java/com/app/controller/AuthController.java` - Login/callback endpoints\n\nThe implementation uses...",
    "truncated": false,
    "isFinalResponse": true
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `subtype` | `"text"` | |
| `messageId` | string | Claude API message ID |
| `content` | string | The text content (may contain markdown) |
| `truncated` | boolean | `true` if content was cut to fit message limit |
| `isFinalResponse` | boolean | `true` if the associated `stop_reason` was `"end_turn"`. Indicates this is the agent's final output for this turn |

**UI should:** Display this in the agent's output panel. If `isFinalResponse` is true, this is the key output to surface. Render markdown.

---

#### 2.3.3 Subtype: `tool_use`

The agent is invoking a tool. Emitted when the agent starts a tool call.

```json
{
  "eventType": "AGENT_OUTPUT",
  "timestamp": "2026-04-01T12:00:07.300Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "subtype": "tool_use",
    "messageId": "msg_01XFDUDYJgAACzvnptvVoYEL",
    "toolUseId": "toolu_01AkhSoeqwFJ51MDYZfwU56J",
    "toolName": "Bash",
    "inputSummary": "ls src/main/java/com/app/config/",
    "inputFull": {
      "command": "ls src/main/java/com/app/config/",
      "description": "List existing config files"
    }
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `subtype` | `"tool_use"` | |
| `messageId` | string | Claude API message ID |
| `toolUseId` | string | Unique ID for this tool invocation. Links to the subsequent `tool_result` |
| `toolName` | string | Name of the tool being called (e.g., `Bash`, `Read`, `Edit`, `Write`, `Grep`, `Glob`) |
| `inputSummary` | string | One-line human-readable summary of what the tool is doing. Constructed by the server from the tool inputs |
| `inputFull` | object | The raw tool input parameters. Shape varies by tool |

**UI should:** Show "Using [toolName]..." status on the agent. Display the `inputSummary` in the activity feed. `inputFull` is available for drill-down.

---

#### 2.3.4 Subtype: `tool_result`

A tool has returned its result. Emitted after the tool completes execution.

```json
{
  "eventType": "AGENT_OUTPUT",
  "timestamp": "2026-04-01T12:00:08.500Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "subtype": "tool_result",
    "toolUseId": "toolu_01AkhSoeqwFJ51MDYZfwU56J",
    "toolName": "Bash",
    "success": true,
    "outputSummary": "Listed 3 files: HttpClientConfig.java, SecurityConfig.java, WebConfig.java",
    "outputFull": "HttpClientConfig.java\nSecurityConfig.java\nWebConfig.java",
    "truncated": false,
    "durationMs": 1200
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `subtype` | `"tool_result"` | |
| `toolUseId` | string | Links back to the `tool_use` event that initiated this call |
| `toolName` | string | Name of the tool (echoed for convenience so the UI doesn't need to look up the tool_use) |
| `success` | boolean | `true` if the tool executed without error. `false` if `is_error` was set in the stream-json |
| `outputSummary` | string | One-line human-readable summary of the result. Constructed by the server |
| `outputFull` | string | The full tool output text. May be large for Bash/Read results |
| `truncated` | boolean | `true` if `outputFull` was cut to fit message limit |
| `durationMs` | number | How long the tool took to execute in milliseconds. Computed from timestamps |

**UI should:** Update the tool invocation entry with the result. Show a green checkmark for `success: true`, red X for `success: false`. Display `outputSummary` inline. `outputFull` in expandable detail.

---

#### 2.3.5 Subtype: `result`

The agent has completed its current task. Contains aggregate cost and performance data. This is always the final AGENT_OUTPUT event for a given agent run.

```json
{
  "eventType": "AGENT_OUTPUT",
  "timestamp": "2026-04-01T12:05:30.789Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "subtype": "result",
    "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "success": true,
    "resultText": "I've implemented the OAuth2 login flow with Google provider...",
    "durationMs": 330666,
    "durationApiMs": 28450,
    "numTurns": 12,
    "totalCostUsd": 1.47,
    "usage": {
      "inputTokens": 245000,
      "outputTokens": 18200,
      "cacheReadInputTokens": 180000,
      "cacheCreationInputTokens": 65000
    }
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `subtype` | `"result"` | |
| `sessionId` | string | Claude CLI session ID |
| `success` | boolean | `true` for normal completion, `false` for error exit |
| `resultText` | string | The agent's final text output (same as the last `text` subtype content) |
| `durationMs` | number | Total wall-clock duration in milliseconds |
| `durationApiMs` | number | Time spent in Claude API calls (excludes tool execution time) |
| `numTurns` | number | Number of tool-use turns the agent took |
| `totalCostUsd` | number | Total API cost in USD for this agent run |
| `usage` | object | Aggregate token usage breakdown |
| `usage.inputTokens` | number | Total input tokens consumed |
| `usage.outputTokens` | number | Total output tokens generated |
| `usage.cacheReadInputTokens` | number | Tokens read from prompt cache |
| `usage.cacheCreationInputTokens` | number | Tokens written to prompt cache |

**UI should:** Mark the agent as completed. Display cost and duration in the agent summary. Update the total cost tracker. This event will be followed by an `AGENT_STATE_CHANGE` to `COMPLETED` (or `FAILED` if `success: false`).

---

### 2.4 HUMAN_INPUT_NEEDED

Emitted when the Conductor's human-input detection engine determines that an agent is blocked and waiting for a human decision.

```json
{
  "eventType": "HUMAN_INPUT_NEEDED",
  "timestamp": "2026-04-01T12:02:15.000Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "requestId": "hir-7890abcd-ef12-3456-7890-abcdef123456",
    "question": "The tests are failing because the DB schema is out of date. Should I: (A) run the migration, (B) update the test fixtures, or (C) skip these tests?",
    "suggestedOptions": [
      { "label": "A", "description": "Run the migration" },
      { "label": "B", "description": "Update the test fixtures" },
      { "label": "C", "description": "Skip these tests" }
    ],
    "context": "Running test suite for user-service module. 3 of 47 tests failing with 'relation users does not exist'. The migration file exists at db/migrations/V3__add_users_table.sql but has not been applied to the test database.",
    "urgency": "HIGH",
    "blockedSince": "2026-04-01T12:02:14.800Z",
    "detectionMethod": "explicit_question",
    "confidenceScore": 0.95
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `requestId` | string (UUID) | Unique ID for this input request. Used when responding via `SEND_MESSAGE` or the REST API |
| `question` | string | The question the agent is asking, extracted from its output |
| `suggestedOptions` | array or `null` | Parsed options if the agent presented choices. Each entry has `label` (string) and `description` (string). `null` if the question is open-ended |
| `context` | string | Surrounding context about what the agent was doing when it got blocked |
| `urgency` | string | One of: `CRITICAL`, `HIGH`, `NORMAL`. See [Section 7](#7-error-severity-levels) for urgency semantics |
| `blockedSince` | string (ISO 8601) | When the agent first became blocked. May be slightly before the detection timestamp |
| `detectionMethod` | string | How the block was detected. One of: `explicit_question` (agent used SendUserMessage or asked a direct question), `activity_stall` (no tool use for 30+ seconds after output), `permission_denial` (tool was denied), `error_recovery` (agent hit an error and is stuck) |
| `confidenceScore` | number (0.0-1.0) | How confident the detection engine is that human input is truly needed. Below 0.7, the server does not emit this event |

**UI should:** This is the highest-priority event. Surface it immediately. Show the question prominently with action buttons for each `suggestedOptions` entry. Show a free-text input for open-ended questions. Display the `context` to help the user make a decision. Start a visible timer from `blockedSince` to show how long the agent has been waiting. Trigger a desktop notification if the dashboard is not focused.

---

### 2.5 COST_UPDATE

Periodic summary of cost across all agents. Emitted every 30 seconds while any agent is active, and once when the last agent completes.

```json
{
  "eventType": "COST_UPDATE",
  "timestamp": "2026-04-01T12:05:00.000Z",
  "agentId": null,
  "projectPath": null,
  "payload": {
    "totalCostToday": 14.82,
    "totalCostSession": 8.37,
    "perAgentCosts": [
      {
        "agentId": "550e8400-e29b-41d4-a716-446655440000",
        "name": "feature-auth-module",
        "costUsd": 1.47,
        "state": "COMPLETED"
      },
      {
        "agentId": "660f9511-f30c-52e5-b827-557766551111",
        "name": "refactor-db-layer",
        "costUsd": 3.21,
        "state": "ACTIVE"
      },
      {
        "agentId": "770a0622-a41d-63f6-c938-668877662222",
        "name": "test-coverage-boost",
        "costUsd": 3.69,
        "state": "ACTIVE"
      }
    ],
    "rateLimitStatus": "allowed",
    "rateLimitResetsAt": "2026-04-01T17:00:00.000Z"
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `totalCostToday` | number | Cumulative cost since midnight UTC across all agents |
| `totalCostSession` | number | Cumulative cost since the Conductor server started |
| `perAgentCosts` | array | Cost breakdown per agent. Each entry has `agentId`, `name`, `costUsd`, and `state` |
| `rateLimitStatus` | string | Current rate limit status: `"allowed"` or `"throttled"` |
| `rateLimitResetsAt` | string (ISO 8601) or `null` | When the current rate limit window resets. `null` if not throttled |

**UI should:** Update the cost ticker in the dashboard header. Color-code if approaching a budget threshold. Show per-agent cost in the agent list.

---

### 2.6 SYSTEM_STATUS

Server health and summary information. Emitted on connection, in response to `REQUEST_STATUS`, and every 60 seconds as a heartbeat.

```json
{
  "eventType": "SYSTEM_STATUS",
  "timestamp": "2026-04-01T12:05:00.000Z",
  "agentId": null,
  "projectPath": null,
  "payload": {
    "activeAgents": 3,
    "blockedAgents": 1,
    "completedAgents": 7,
    "failedAgents": 0,
    "totalAgentsSpawned": 11,
    "pendingInputRequests": 1,
    "memoryUsageMb": 512,
    "uptimeSeconds": 7200,
    "serverVersion": "0.1.0",
    "projects": [
      {
        "projectPath": "C:/Users/matt8/projects/my-app",
        "projectName": "my-app",
        "activeAgentCount": 2
      },
      {
        "projectPath": "C:/Users/matt8/projects/data-pipeline",
        "projectName": "data-pipeline",
        "activeAgentCount": 1
      }
    ]
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `activeAgents` | number | Agents currently in `ACTIVE` state |
| `blockedAgents` | number | Agents currently in `BLOCKED` state |
| `completedAgents` | number | Agents that finished successfully this session |
| `failedAgents` | number | Agents that failed this session |
| `totalAgentsSpawned` | number | Total agents created since server start |
| `pendingInputRequests` | number | `HUMAN_INPUT_NEEDED` events with no response yet |
| `memoryUsageMb` | number | Server JVM heap usage in megabytes |
| `uptimeSeconds` | number | Seconds since the Conductor server started |
| `serverVersion` | string | Conductor server version |
| `projects` | array | Registered projects with their active agent counts |

**UI should:** Populate the dashboard status bar. Show a connection health indicator. If `pendingInputRequests > 0`, show a badge on the human input queue panel.

---

### 2.7 ERROR

Something went wrong. Can be agent-scoped or system-wide.

```json
{
  "eventType": "ERROR",
  "timestamp": "2026-04-01T12:03:45.000Z",
  "agentId": "550e8400-e29b-41d4-a716-446655440000",
  "projectPath": "C:/Users/matt8/projects/my-app",
  "payload": {
    "errorCode": "AGENT_PROCESS_CRASHED",
    "errorMessage": "Claude CLI process exited with code 1: SIGTERM",
    "severity": "HIGH",
    "recoverable": true,
    "details": {
      "exitCode": 1,
      "lastOutput": "Writing file src/main/java/...",
      "sessionId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    }
  }
}
```

System-wide error example (no `agentId`):

```json
{
  "eventType": "ERROR",
  "timestamp": "2026-04-01T12:10:00.000Z",
  "agentId": null,
  "projectPath": null,
  "payload": {
    "errorCode": "RATE_LIMIT_EXCEEDED",
    "errorMessage": "Anthropic API rate limit reached. All agents throttled until 17:00 UTC.",
    "severity": "CRITICAL",
    "recoverable": true,
    "details": {
      "rateLimitResetsAt": "2026-04-01T17:00:00.000Z",
      "affectedAgents": 3
    }
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `errorCode` | string | Machine-readable error code. Known codes listed below |
| `errorMessage` | string | Human-readable error description |
| `severity` | string | One of: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. See [Section 7](#7-error-severity-levels) |
| `recoverable` | boolean | `true` if the system can potentially recover (e.g., restart the agent). `false` if manual intervention is required |
| `details` | object or `null` | Additional context. Structure varies by error code |

**Known error codes:**

| Code | Severity | Description |
|------|----------|-------------|
| `AGENT_PROCESS_CRASHED` | HIGH | CLI process exited unexpectedly |
| `AGENT_PROCESS_START_FAILED` | HIGH | Could not launch CLI process |
| `AGENT_BUDGET_EXCEEDED` | MEDIUM | Agent hit its `maxBudgetUsd` cap |
| `AGENT_TIMEOUT` | MEDIUM | Agent exceeded maximum allowed runtime |
| `RATE_LIMIT_EXCEEDED` | CRITICAL | Anthropic API rate limit hit |
| `RATE_LIMIT_WARNING` | MEDIUM | Approaching rate limit threshold |
| `WEBSOCKET_BACKPRESSURE` | LOW | Server is buffering events because the client is slow |
| `PARSE_ERROR` | LOW | Failed to parse a stream-json line from the CLI |
| `INTERNAL_SERVER_ERROR` | CRITICAL | Unhandled exception in the Conductor server |

**UI should:** Display errors in a notification panel. CRITICAL errors should trigger a desktop notification and an audible alert. Show `recoverable` status to indicate whether the user needs to act. Link to the affected agent if `agentId` is present.

---

## 3. Client -> Server Commands

### 3.1 SPAWN_AGENT

Request the server to create a new agent.

```json
{
  "commandType": "SPAWN_AGENT",
  "requestId": "req-aabbccdd-1122-3344-5566-778899001122",
  "payload": {
    "name": "implement-caching",
    "role": "FEATURE_ENGINEER",
    "projectPath": "C:/Users/matt8/projects/my-app",
    "prompt": "Add Redis caching to the user profile endpoint. Use Spring Cache abstraction.",
    "model": "claude-opus-4-6[1m]",
    "maxBudgetUsd": 3.00
  }
}
```

| Payload field | Type | Required | Description |
|---------------|------|----------|-------------|
| `name` | string | yes | Human-readable name for the agent |
| `role` | string | yes | Agent role. One of: `FEATURE_ENGINEER`, `REFACTORER`, `TESTER`, `REVIEWER`, `DEBUGGER`, `META_AGENT`, `GENERAL` |
| `projectPath` | string | yes | Absolute path to the project directory |
| `prompt` | string | yes | The initial task prompt for the agent |
| `model` | string | no | Model to use. Defaults to server configuration |
| `maxBudgetUsd` | number | no | Cost cap. Defaults to server configuration |

**Server responds with:** An `AGENT_SPAWNED` event (on success) or an `ERROR` event (on failure), both carrying the `requestId` is not echoed in the event -- the client correlates by matching the `name` in the spawned event, or by receiving an error.

**Acknowledgement:** The server sends back an immediate ACK frame:

```json
{
  "eventType": "COMMAND_ACK",
  "timestamp": "2026-04-01T12:00:00.050Z",
  "agentId": null,
  "projectPath": null,
  "payload": {
    "requestId": "req-aabbccdd-1122-3344-5566-778899001122",
    "accepted": true,
    "message": "Spawning agent 'implement-caching'"
  }
}
```

---

### 3.2 KILL_AGENT

Request the server to terminate an agent's CLI process.

```json
{
  "commandType": "KILL_AGENT",
  "requestId": "req-eeff0011-2233-4455-6677-889900aabbcc",
  "payload": {
    "agentId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

| Payload field | Type | Required | Description |
|---------------|------|----------|-------------|
| `agentId` | string (UUID) | yes | The agent to kill |

**Server responds with:** `COMMAND_ACK`, then `AGENT_STATE_CHANGE` (to `KILLED`). The server sends SIGTERM first, then SIGKILL after 5 seconds if the process does not exit.

---

### 3.3 SEND_MESSAGE

Send a human message to an agent. Used to respond to `HUMAN_INPUT_NEEDED` events, or to send follow-up instructions to any active agent.

```json
{
  "commandType": "SEND_MESSAGE",
  "requestId": "req-11223344-5566-7788-99aa-bbccddeeff00",
  "payload": {
    "agentId": "550e8400-e29b-41d4-a716-446655440000",
    "text": "Use approach A - run the migration. The test database schema should always match production.",
    "inputRequestId": "hir-7890abcd-ef12-3456-7890-abcdef123456"
  }
}
```

| Payload field | Type | Required | Description |
|---------------|------|----------|-------------|
| `agentId` | string (UUID) | yes | The agent to send the message to |
| `text` | string | yes | The message content. This gets written to the agent's stdin via the stream-json input protocol |
| `inputRequestId` | string (UUID) | no | If this is a response to a specific `HUMAN_INPUT_NEEDED` event, include the `requestId` from that event. The server will mark that input request as resolved |

**Server responds with:** `COMMAND_ACK`. If the agent was `BLOCKED`, an `AGENT_STATE_CHANGE` back to `ACTIVE` will follow. The agent will then produce new `AGENT_OUTPUT` events as it processes the response.

---

### 3.4 MUTE_AGENT

Toggle output suppression for an agent. Muted agents still run and are tracked, but their `AGENT_OUTPUT` events (subtypes `thinking`, `text`, `tool_use`, `tool_result`) are not sent over the WebSocket. The `result` subtype is always sent. State changes and errors are never muted.

```json
{
  "commandType": "MUTE_AGENT",
  "requestId": "req-aabb1122-ccdd-3344-eeff-556677889900",
  "payload": {
    "agentId": "660f9511-f30c-52e5-b827-557766551111",
    "muted": true
  }
}
```

| Payload field | Type | Required | Description |
|---------------|------|----------|-------------|
| `agentId` | string (UUID) | yes | The agent to mute/unmute |
| `muted` | boolean | yes | `true` to suppress output events, `false` to resume |

**Server responds with:** `COMMAND_ACK`.

**Important:** Muting does NOT suppress `HUMAN_INPUT_NEEDED` events. A muted agent that needs input will still surface the request.

---

### 3.5 REQUEST_STATUS

Request the server to emit a fresh `SYSTEM_STATUS` event. No payload required.

```json
{
  "commandType": "REQUEST_STATUS",
  "requestId": "req-ffeeddcc-bbaa-9988-7766-554433221100",
  "payload": {}
}
```

**Server responds with:** `COMMAND_ACK`, then a `SYSTEM_STATUS` event.

---

## 4. Message Ordering Guarantees

1. **Within a single agent:** Events are delivered in the order they were generated. If `AGENT_OUTPUT(tool_use)` for tool X arrives before `AGENT_OUTPUT(tool_result)` for tool X, this ordering is guaranteed.

2. **Across agents:** No ordering guarantee. Events from Agent A and Agent B may interleave in any order. The server does NOT hold back events from one agent to maintain cross-agent ordering.

3. **State consistency:** An `AGENT_STATE_CHANGE` event always arrives after the event that caused the transition. For example, the `AGENT_OUTPUT(result)` event arrives before the `AGENT_STATE_CHANGE` to `COMPLETED`.

4. **Timestamp as tiebreaker:** When the UI needs to display events from multiple agents in a unified timeline, sort by `timestamp`. For events with identical timestamps, sort by `agentId` for stability.

5. **COMMAND_ACK ordering:** A `COMMAND_ACK` for a given `requestId` is always delivered before any side-effect events caused by that command. For example, the ACK for `SPAWN_AGENT` arrives before the `AGENT_SPAWNED` event.

---

## 5. Reconnection Protocol

WebSocket connections can drop due to network issues, server restarts, or client sleeps. The protocol is designed so the UI can recover without data loss.

### Disconnect detection

- The client should set a **ping interval of 30 seconds**. If 2 consecutive pings fail (no pong within 5 seconds), treat the connection as dead.
- The server sends `SYSTEM_STATUS` every 60 seconds. If the client receives no messages for 90 seconds, treat the connection as dead.

### Reconnection sequence

```
1. Client detects disconnect
2. Wait: exponential backoff starting at 1 second
   Attempts:  1s -> 2s -> 4s -> 8s -> 16s -> 30s (cap)
3. Reconnect to ws://localhost:8090/ws/events
4. On successful connection:
   a. Client sends REQUEST_STATUS command
   b. Server responds with SYSTEM_STATUS (current state snapshot)
   c. Client calls REST API: GET /api/v1/conductor/agents
      to rebuild the full agent list with current states
5. Client resumes normal operation
```

### What the server does NOT do on reconnect

- The server does **NOT** replay missed events. Events emitted while the client was disconnected are lost from the WebSocket perspective.
- The server does **NOT** buffer events per client. This is a single-user system; if the one client is disconnected, events are simply not delivered.

### State recovery via REST

After reconnecting, the client should call these REST endpoints to rebuild its state:

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/conductor/agents` | Full list of agents with current states |
| `GET /api/v1/conductor/agents/{id}/output?since={timestamp}` | Recent output for a specific agent (if the UI wants to backfill) |
| `GET /api/v1/conductor/input-requests` | Pending human input requests |
| `GET /api/v1/conductor/queue/digest` | Current message queue digest |

The `since` parameter on the output endpoint accepts an ISO 8601 timestamp. Pass the timestamp of the last event the client received before disconnecting.

---

## 6. Agent States Reference

| State | Description | Entered when | UI indicator |
|-------|-------------|-------------|--------------|
| `LAUNCHING` | Agent is being initialized. CLI process is starting | `SPAWN_AGENT` command received | Gray spinner |
| `ACTIVE` | Agent is running and producing output | CLI process started, or human responded to blocked agent | Green dot |
| `BLOCKED` | Agent is waiting for human input | Human input detection triggered | Amber pulsing dot |
| `COMPLETED` | Agent finished its task successfully | `result` event with `success: true` received | Blue checkmark |
| `FAILED` | Agent crashed or hit an unrecoverable error | Process exited abnormally, or `result` with `success: false` | Red X |
| `KILLED` | Agent was manually terminated by the user | `KILL_AGENT` command executed | Gray dash |

**Terminal states:** `COMPLETED`, `FAILED`, `KILLED`. Agents in terminal states will never produce new events. They remain in the agent list for the session for reference.

---

## 7. Error Severity Levels

Used in `ERROR` events and `HUMAN_INPUT_NEEDED` urgency.

| Severity | Meaning | UI behavior |
|----------|---------|-------------|
| `CRITICAL` | System-wide impact. Multiple agents affected, or data loss risk | Desktop notification + audible alert + red banner in dashboard |
| `HIGH` | Single agent impacted. Agent is down or blocked | Desktop notification + amber highlight in dashboard |
| `MEDIUM` | Degraded but functional. Budget warning, rate limit approaching | Dashboard notification only |
| `LOW` | Informational. Parse error, backpressure warning | Log only, visible in detail view |

---

## 8. Implementation Notes

### For the backend engineer

1. **Event construction:** Build the common envelope in a shared `WebSocketEventBuilder` utility. Each domain service (agent lifecycle, cost tracker, health monitor) constructs only the `payload` and passes it to the builder with the event type.

2. **Agent output routing:** The stream-json parser (from spike3) produces raw events. The `AgentOutputRouter` service translates these into the `AGENT_OUTPUT` subtypes defined here. Key mapping:
   - `stream-json type: "system", subtype: "init"` -> triggers `AGENT_STATE_CHANGE` (LAUNCHING -> ACTIVE)
   - `stream-json type: "assistant", content[0].type: "thinking"` -> `AGENT_OUTPUT(thinking)`
   - `stream-json type: "assistant", content[0].type: "text"` -> `AGENT_OUTPUT(text)`
   - `stream-json type: "assistant", content[0].type: "tool_use"` -> `AGENT_OUTPUT(tool_use)`
   - `stream-json type: "user", content[0].type: "tool_result"` -> `AGENT_OUTPUT(tool_result)`
   - `stream-json type: "result"` -> `AGENT_OUTPUT(result)` + `AGENT_STATE_CHANGE` (ACTIVE -> COMPLETED/FAILED)

3. **Summary generation:** `inputSummary` and `outputSummary` fields require the server to build human-readable one-liners from tool inputs/outputs. Keep these under 120 characters. For `Bash`, use the command string. For `Read`, use the file path. For `Edit`, use "Editing {file} ({n} chars changed)". For `Write`, use "Writing {file}".

4. **Cost tracking:** Extract `total_cost_usd` from stream-json `result` events. Aggregate in-memory, persist to database on 30-second COST_UPDATE interval.

5. **Thread safety:** WebSocket send operations must be synchronized per session. Spring's `SimpMessagingTemplate` or `WebSocketSession.sendMessage()` from a single-threaded event loop is the recommended pattern.

### For the frontend engineer

1. **Single connection:** Open exactly one WebSocket to the server. All event types arrive on this connection. Route events by `eventType` to the appropriate handler/reducer.

2. **State management:** Maintain an agent map keyed by `agentId`. Apply state changes immutably. The `AGENT_STATE_CHANGE` event is authoritative -- always trust it over inferred state.

3. **Tool use pairing:** `tool_use` and `tool_result` events are linked by `toolUseId`. When a `tool_use` arrives, create a pending entry. When the matching `tool_result` arrives, resolve it. If a `tool_result` arrives without a matching `tool_use` (can happen on reconnect), display it standalone.

4. **Mute state is client-side first:** Send `MUTE_AGENT` to the server, but also immediately suppress rendering locally. Don't wait for acknowledgement.

5. **Timestamp display:** All timestamps are UTC. Convert to local time for display. Show relative times ("2m ago") for recent events, absolute times for older events.

6. **Message buffer:** Keep the last 500 `AGENT_OUTPUT` events per agent in memory. Older events can be fetched on demand via the REST API.

---

## Appendix: COMMAND_ACK Event

Sent by the server in response to every client command. Allows the client to confirm the command was received and whether it will be acted on.

```json
{
  "eventType": "COMMAND_ACK",
  "timestamp": "2026-04-01T12:00:00.050Z",
  "agentId": null,
  "projectPath": null,
  "payload": {
    "requestId": "req-aabbccdd-1122-3344-5566-778899001122",
    "accepted": true,
    "message": "Spawning agent 'implement-caching'"
  }
}
```

| Payload field | Type | Description |
|---------------|------|-------------|
| `requestId` | string | Echoes the client's `requestId` from the command |
| `accepted` | boolean | `true` if the command was accepted. `false` if rejected (e.g., invalid agentId, agent already dead) |
| `message` | string | Human-readable status message. On rejection, explains why |

**Rejection example:**

```json
{
  "eventType": "COMMAND_ACK",
  "timestamp": "2026-04-01T12:00:00.050Z",
  "agentId": null,
  "projectPath": null,
  "payload": {
    "requestId": "req-eeff0011-2233-4455-6677-889900aabbcc",
    "accepted": false,
    "message": "Agent 550e8400-e29b-41d4-a716-446655440000 is in COMPLETED state and cannot be killed"
  }
}
```
