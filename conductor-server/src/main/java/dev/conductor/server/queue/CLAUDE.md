# queue/ — Message Queue Engine (Phase 1)

## Responsibility
Classify, deduplicate, batch, and filter agent output so the UI shows signal, not noise. This is the core noise-reduction layer.

## Status: Phase 1 Complete

## Contracts

### Consumes
- `ClaudeProcessManager.AgentStreamEvent(UUID agentId, StreamJsonEvent event)` (from process/) — raw parsed agent output via `@EventListener`
- `AgentRecord` (from agent/) — agent name resolved via `AgentRegistry.get(agentId)`

### Provides
- `QueuedMessage(agentId, agentName, text, urgency, category, timestamp, dedupHash, batchId)` — classified, filtered message ready for notification/UI
- `QueueManager.getMessages(Instant since, int limit)` → `List<QueuedMessage>` — chronological, bounded in-memory history (max 1000)
- `QueueManager.historySize()` → `int`
- `MuteRegistry.mute(UUID)` / `unmute(UUID)` / `isMuted(UUID)` / `toggle(UUID)` / `getMutedAgents()` / `mutedCount()`
- `NoiseFilter.setVerbose(boolean)` / `isVerbose()`
- Publishes `QueuedMessageEvent(QueuedMessage)` via Spring `ApplicationEventPublisher` — consumed by notification/ and api/

### Events Published
```java
record QueuedMessageEvent(QueuedMessage message) {}
```

## Implemented Files
| File | Type | Purpose |
|------|------|---------|
| `Urgency.java` | enum | CRITICAL, HIGH, MEDIUM, LOW, NOISE |
| `QueuedMessage.java` | record | agentId, agentName, text, urgency, category, timestamp, dedupHash, batchId |
| `QueuedMessageEvent.java` | record | Spring event wrapper for downstream consumers |
| `MessageClassifier.java` | @Service | Assigns urgency + category from StreamJsonEvent; stateless |
| `MessageDeduplicator.java` | @Service | SHA-256 content-hash dedup within 60s sliding window; @Scheduled cleanup every 30s |
| `MessageBatcher.java` | @Service | Groups by (agentId, urgency) into 30s windows; digest if >3 msgs; CRITICAL bypasses; @Scheduled flush every 5s |
| `NoiseFilter.java` | @Service | Drops NOISE unless verbose=true; volatile boolean toggle |
| `QueueManager.java` | @Service | Pipeline orchestrator: @EventListener → mute → classify → dedup → batch → filter → publish |
| `MuteRegistry.java` | @Service | Per-agent mute state in ConcurrentHashMap |
| `QueueSchedulingConfig.java` | @Configuration | @EnableScheduling for dedup cleanup + batch flush |

## Test Files
| File | Coverage |
|------|----------|
| `MessageClassifierTest.java` | 12 tests: all urgency levels, edge cases (truncation, null text) |
| `MessageDeduplicatorTest.java` | 11 tests: dedup logic, hash consistency, cleanup, null safety |
| `MessageBatcherTest.java` | 7 tests: CRITICAL bypass, batching, digest emission, multi-agent |
| `NoiseFilterTest.java` | 7 tests: noise drop, verbose toggle, all urgency levels pass |

## Urgency Levels
| Level | Examples | UI Behavior |
|-------|----------|-------------|
| CRITICAL | Agent blocked, needs human input (AskUserQuestion) | Desktop notification + sound |
| HIGH | Agent error, task complete (ResultEvent) | Badge + feed highlight |
| MEDIUM | Tool use, text output, progress update | Feed only |
| LOW | Thinking, parse errors, tool results | Collapsed in feed |
| NOISE | Rate limit checks, system init | Dropped unless verbose mode |

## Classification Rules (Pattern-Based)
- AssistantEvent with toolName="AskUserQuestion" → CRITICAL / "ask_user"
- ResultEvent with isError=true → HIGH / "error"
- ResultEvent success → HIGH / "task_complete"
- AssistantEvent TOOL_USE → MEDIUM / "tool_use"
- AssistantEvent TEXT → MEDIUM / "text"
- AssistantEvent THINKING → LOW / "thinking"
- UserEvent (tool result) → LOW / "tool_result"
- ParseErrorEvent → LOW / "parse_error"
- RateLimitEvent → NOISE / "rate_limit"
- SystemInitEvent → NOISE / "system_init"
- Unknown assistant content type → MEDIUM / "assistant_unknown"

## Key Design
- Pipeline is synchronous on the Spring event thread: mute check → classify → dedup → batch → filter → publish
- Dedup uses SHA-256 of (agentId + category + first 200 chars of text) within 60-second window
- Batcher groups by (agentId, urgency); holds for 30s; emits digest if >3 messages accumulated
- CRITICAL messages bypass the batcher entirely (immediate emit)
- Digest format: "agent-name: N URGENCY events (category xN, ...)"
- MuteRegistry persists mute state in-memory (future: database)
- QueueManager maintains bounded in-memory history (max 1000 messages, newest-first deque)
