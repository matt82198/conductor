# humaninput/ — Human Input Detection (Phase 2)

## Responsibility
Detect when agents are blocked waiting for human input. Queue requests, prompt the user, pipe responses back to the agent's stdin.

## Status: Phase 2 Complete

## Contracts

### Consumes
- `AgentStreamEvent` (from process/) — watch for AskUserQuestion tool, stalls, questions
- `AgentRecord` (from agent/) — agent context for the UI prompt
- `ClaudeProcessManager.sendMessage()` (from process/) — to pipe human response to agent

### Provides
- `HumanInputDetector.evaluate(AgentStreamEvent)` → Optional<HumanInputRequest>
- `HumanInputQueue.getPending()` → List<HumanInputRequest> (priority-ordered)
- `HumanInputResponder.respond(requestId, text)` → sends to agent stdin
- Publishes `HumanInputNeededEvent(HumanInputRequest)` via Spring events

### Events Published
```java
record HumanInputNeededEvent(HumanInputRequest request) {}
```
Consumed by notification/ (routed as CRITICAL) and api/ (pushed to UI).

## Planned Files
| File | Purpose |
|------|---------|
| `HumanInputRequest.java` | Record: requestId, agentId, question, suggestedOptions, context, urgency, detectedAt |
| `HumanInputDetector.java` | @Service — 5-layer detection pipeline |
| `PatternMatcher.java` | Utility — regex detection of questions in agent text |
| `StallDetector.java` | @Service — monitors agent activity timestamps, flags stalls >30s |
| `ConfidenceScorer.java` | Utility — combines detection signals into confidence score |
| `HumanInputQueue.java` | @Service — priority queue of pending requests |
| `HumanInputResponder.java` | @Service — pipes user response to agent stdin via ClaudeProcessManager |

## Detection Pipeline (5 Layers)
1. **Explicit tool use** — AssistantEvent with toolName=`AskUserQuestion` → confidence 1.0
2. **Pattern matching** — text contains "should I", "which approach", "A or B" → confidence 0.7-0.9
3. **Activity stall** — no events from agent for >30s when state is ACTIVE → confidence 0.5-0.7
4. **Permission denial** — ResultEvent.permissionDenials non-empty → confidence 0.9
5. **Process state** — agent process alive but no stdout for >60s → confidence 0.6

## Key Design
- Detection runs on every AgentStreamEvent (lightweight, no blocking)
- StallDetector runs on a 10-second scheduled check across all active agents
- Confidence threshold for surfacing to user: 0.6 (configurable)
- Responses pipe through ClaudeProcessManager.sendMessage() which writes JSON to stdin
- Queue is priority-ordered: explicit tool use > pattern match > stall detection
