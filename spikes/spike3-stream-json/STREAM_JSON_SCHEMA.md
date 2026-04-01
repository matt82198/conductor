# Stream-JSON Output Schema

**Captured:** 2026-04-01 | **CLI Version:** 2.1.89 | **Model:** claude-opus-4-6[1m]  
**Flag required:** `claude -p --verbose --output-format stream-json`

---

## Event Lifecycle

Every session produces events in this order:

```
system(init) → [assistant → user]* → rate_limit_event → result
```

Expanded for a tool-use session:

```
1. system        (init — session metadata)
2. assistant      (thinking block)
3. assistant      (tool_use block — e.g., Read, Bash, Grep)
4. rate_limit_event (may appear between any assistant/user pair)
5. user           (tool_result — output of the tool)
6. assistant      (thinking or text or another tool_use)
   ... repeat 3-6 for each tool call ...
7. assistant      (text — final response)
8. result         (success — cost, tokens, duration)
```

**Key insight:** Each `assistant` event carries ONE content block. A response with thinking + tool_use + text arrives as 3 separate `assistant` events, not one event with an array.

---

## Event Types (5 observed)

### 1. `system` (subtype: `init`)

First event in every session. Contains full session metadata.

| Field | Type | Description |
|-------|------|-------------|
| `type` | `"system"` | |
| `subtype` | `"init"` | |
| `session_id` | string | UUID, stable for the session |
| `cwd` | string | Working directory |
| `model` | string | e.g., `"claude-opus-4-6[1m]"` |
| `tools` | string[] | All available tool names (45 observed) |
| `mcp_servers` | object[] | MCP server connection status |
| `permissionMode` | string | e.g., `"default"` |
| `claude_code_version` | string | e.g., `"2.1.89"` |
| `agents` | string[] | Available agent types |
| `skills` | string[] | Available skills |
| `slash_commands` | string[] | Available slash commands |
| `plugins` | array | Loaded plugins |
| `apiKeySource` | string | e.g., `"none"` |
| `fast_mode_state` | string | `"off"` or `"on"` |
| `output_style` | string | e.g., `"default"` |
| `uuid` | string | Event UUID |

**Conductor use:** Register agent capabilities, detect model, validate session started.

---

### 2. `assistant`

An assistant message chunk. Each event carries exactly ONE content block.

| Field | Type | Description |
|-------|------|-------------|
| `type` | `"assistant"` | |
| `message.model` | string | Model that generated this |
| `message.id` | string | Message ID (same across content blocks of one logical message) |
| `message.role` | `"assistant"` | |
| `message.content` | object[] | **Always length 1.** One of the content types below |
| `message.stop_reason` | string\|null | `null` until final chunk, then `"end_turn"` |
| `message.usage` | object | Token usage for this API call |
| `message.usage.input_tokens` | int | Input tokens |
| `message.usage.output_tokens` | int | Output tokens |
| `message.usage.cache_creation_input_tokens` | int | Tokens written to cache |
| `message.usage.cache_read_input_tokens` | int | Tokens read from cache |
| `message.usage.service_tier` | string | `"standard"` |
| `message.context_management` | object\|null | Context window management info |
| `parent_tool_use_id` | string\|null | If this is a sub-agent response |
| `session_id` | string | |
| `uuid` | string | Event UUID |

#### Content block types inside `message.content[0]`:

**`thinking`** — Extended thinking block
```json
{
  "type": "thinking",
  "thinking": "The user wants me to...",
  "signature": "EpMCClkIDBgC..."
}
```

**`text`** — Text response
```json
{
  "type": "text",
  "text": "Here is the answer..."
}
```

**`tool_use`** — Tool invocation
```json
{
  "type": "tool_use",
  "id": "toolu_01AkhSoeqwFJ51MDYZfwU56J",
  "name": "Bash",
  "input": {
    "command": "ls /some/path",
    "description": "List files"
  },
  "caller": { "type": "direct" }
}
```

**Conductor use:** 
- `thinking` → agent is working, can show "thinking..." indicator
- `text` → final or intermediate text output, route to noise queue
- `tool_use` → agent is calling a tool, track for activity monitoring. The `name` field tells you which tool. The `id` field links to the subsequent `tool_result`.

---

### 3. `user`

Tool results flowing back to the agent. Emitted after each tool_use.

| Field | Type | Description |
|-------|------|-------------|
| `type` | `"user"` | |
| `message.role` | `"user"` | |
| `message.content[0].type` | `"tool_result"` | |
| `message.content[0].tool_use_id` | string | Links back to the `tool_use.id` |
| `message.content[0].content` | string | The tool's output text |
| `message.content[0].is_error` | boolean | Whether the tool errored |
| `parent_tool_use_id` | string\|null | |
| `session_id` | string | |
| `timestamp` | string | ISO 8601 |
| `tool_use_result` | object | Structured result (varies by tool) |
| `uuid` | string | |

#### `tool_use_result` shapes by tool:

**Bash:**
```json
{
  "stdout": "file1.txt\nfile2.txt",
  "stderr": "",
  "interrupted": false,
  "isImage": false,
  "noOutputExpected": false
}
```

**Read:**
```json
{
  "type": "text",
  "file": {
    "filePath": "C:/path/to/file.md",
    "content": "file contents...",
    "numLines": 119,
    "startLine": 1,
    "totalLines": 119
  }
}
```

**Grep:**
```json
{
  "content": "matched content...",
  "filenames": ["file1.java"],
  "mode": "content",
  "numFiles": 1,
  "numLines": 5
}
```

**Conductor use:** Monitor tool execution, detect errors (`is_error: true`), measure tool latency via timestamps.

---

### 4. `rate_limit_event`

Rate limit status check. Appears once per API call (between assistant/user pairs).

| Field | Type | Description |
|-------|------|-------------|
| `type` | `"rate_limit_event"` | |
| `rate_limit_info.status` | string | `"allowed"` |
| `rate_limit_info.resetsAt` | int | Unix timestamp when limit resets |
| `rate_limit_info.rateLimitType` | string | `"five_hour"` |
| `rate_limit_info.overageStatus` | string | `"rejected"` |
| `rate_limit_info.overageDisabledReason` | string | e.g., `"org_level_disabled"` |
| `rate_limit_info.isUsingOverage` | boolean | |
| `session_id` | string | |
| `uuid` | string | |

**Conductor use:** Track rate limit headroom. If `status != "allowed"`, the agent is throttled — queue work for later.

---

### 5. `result` (subtype: `success`)

Final event. Contains aggregate cost and usage data.

| Field | Type | Description |
|-------|------|-------------|
| `type` | `"result"` | |
| `subtype` | `"success"` or `"error"` | |
| `is_error` | boolean | |
| `result` | string | Final text output |
| `stop_reason` | string | `"end_turn"` |
| `duration_ms` | int | Total wall-clock time |
| `duration_api_ms` | int | Time spent in API calls |
| `num_turns` | int | Number of tool-use turns |
| `total_cost_usd` | float | **Total cost in USD** |
| `session_id` | string | |
| `fast_mode_state` | string | |
| `permission_denials` | array | Tools user denied |
| `usage` | object | Aggregate token usage |
| `usage.input_tokens` | int | Total input tokens |
| `usage.output_tokens` | int | Total output tokens |
| `usage.cache_read_input_tokens` | int | |
| `usage.cache_creation_input_tokens` | int | |
| `usage.server_tool_use.web_search_requests` | int | |
| `usage.server_tool_use.web_fetch_requests` | int | |
| `modelUsage` | object | Per-model breakdown |
| `modelUsage[model].inputTokens` | int | |
| `modelUsage[model].outputTokens` | int | |
| `modelUsage[model].cacheReadInputTokens` | int | |
| `modelUsage[model].cacheCreationInputTokens` | int | |
| `modelUsage[model].costUSD` | float | |
| `modelUsage[model].contextWindow` | int | e.g., `1000000` |
| `modelUsage[model].maxOutputTokens` | int | e.g., `64000` |
| `uuid` | string | |

**Conductor use:** This is the money event. Extract `total_cost_usd`, `duration_ms`, `num_turns` for cost tracking and performance dashboards.

---

## Key Findings for Conductor Architecture

### Cost data: YES
`total_cost_usd` is in the `result` event. Per-model breakdown in `modelUsage`. No need for separate Anthropic usage API.

### Token data: YES
Both per-message (`assistant.message.usage`) and aggregate (`result.usage`). Cache hits/misses tracked.

### Tool use tracking: YES
Full `tool_use` → `tool_result` cycle is visible. Tool name, inputs, outputs, errors all captured.

### Human input detection signals:
- `permission_denials` array in `result` — if non-empty, user blocked a tool
- `tool_use` with `name: "AskUserQuestion"` — agent explicitly asks user
- Long gap between `assistant` and next event — agent may be waiting for permission
- `is_error: true` in result — session failed

### Rate limiting: YES
`rate_limit_event` tells you if the agent is being throttled.

### Session resumption:
`session_id` is stable per session. Can be used with `--resume` flag.

### What's NOT in the stream:
- No partial/streaming text tokens (would need `--include-partial-messages` flag — not tested)
- No explicit "waiting for human input" event type
- No agent sub-process spawn events (sub-agents are opaque)

---

## Parsing Strategy for Conductor

```
Read line → parse JSON → switch on .type:
  "system"           → register agent, store session_id
  "assistant"        → switch on .message.content[0].type:
                         "thinking"  → update status: "thinking..."
                         "tool_use"  → update status: "using {name}..."
                         "text"      → route to noise queue
  "user"             → tool completed, update activity timestamp
  "rate_limit_event" → check .rate_limit_info.status
  "result"           → session complete, record cost/duration
```

One JSON object per line. No framing needed. Just `readLine()` + `JSON.parse()`.
