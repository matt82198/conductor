# Conductor Brain — Architecture Design

**Author:** Matthew Culliton + Claude Code  
**Status:** Design — Not yet built  
**Date:** 2026-04-03

---

## 1. What the Brain Is

The Conductor Brain is a **visible leader agent** that acts as a product owner between the human and the child agents. It has its own Claude API connection, reads all project context, learns how the user works, and coordinates child agents at machine speed.

It never writes code. It only orchestrates.

### The Brain is a first-class agent, not a hidden daemon

The Brain appears in the agent list alongside child agents. Its state (GATHERING, THINKING, DECIDING, ACTIVE) is visible. Its decisions, reasoning, and context gathering all flow through the event feed. The user can see what the Brain is doing at all times — same transparency principle as the child agents.

### Startup UX

When Conductor launches, the user sees a loading screen showing the Brain agent spinning up:

```
[Conductor]  Scanning projects...
[Conductor]  Found 3 projects (conductor, myapp, shared-lib)
[Conductor]  Reading 14 CLAUDE.md files...
[Conductor]  Loading memories from ~/.claude/projects/...
[Conductor]  Indexing 8 skills, 3 agent configs...
[Conductor]  Loading behavior model (247 past interactions)...
[Conductor]  Ready. Monitoring 0 agents.
```

This isn't a splash screen — it's real agent output. The Brain is registered as an AgentRecord in the registry, and its startup events flow through the same WebSocket/event feed as everything else.

### Product Owner Role

The Brain is the primary interface between the human and the system. It:
- **Talks to the user** in the event feed and via a chat interface
- **Makes small decisions autonomously** (approve tool use, answer routine questions, share context between agents)
- **Defers big decisions to the human** (architecture choices, destructive operations, ambiguous situations)
- **Learns what "big" means** through the behavior model — this boundary shifts over time as it observes the user's patterns

```
                    ┌─────────────────────────┐
                    │     Conductor Brain      │
                    │  ┌───────────────────┐  │
                    │  │  Claude API (SDK)  │  │
                    │  └─────────┬─────────┘  │
                    │            │             │
                    │  ┌─────────▼─────────┐  │
                    │  │  Context Engine    │  │
                    │  │  (CLAUDE.md, memory│  │
                    │  │   skills, config)  │  │
                    │  └─────────┬─────────┘  │
                    │            │             │
                    │  ┌─────────▼─────────┐  │
                    │  │  Behavior Model    │  │
                    │  │  (learned from     │  │
                    │  │   user actions)    │  │
                    │  └─────────┬─────────┘  │
                    │            │             │
                    │  ┌─────────▼─────────┐  │
                    │  │  Decision Engine   │  │
                    │  │  (respond, route,  │  │
                    │  │   escalate)        │  │
                    │  └───────────────────┘  │
                    └────────────┬────────────┘
                                │
           ┌────────────────────┼────────────────────┐
           │                    │                    │
    ┌──────▼──────┐     ┌──────▼──────┐     ┌──────▼──────┐
    │  Agent A    │     │  Agent B    │     │  Agent C    │
    │  (CLI)      │     │  (CLI)      │     │  (CLI)      │
    └─────────────┘     └─────────────┘     └─────────────┘
```

---

## 2. Where It Lives

New domain: `conductor-server/src/main/java/dev/conductor/server/brain/`

The Brain is a server-side domain like queue/, notification/, humaninput/. It plugs into the same Spring event bus. It does NOT live in the UI or as a separate service — it's a first-class domain in the existing server.

**Why server-side Java, not a separate Node service:**
- The Claude API has a Java SDK (anthropic-sdk-java)
- All event bus wiring is already in Spring — adding another @EventListener is trivial
- No need for a separate process, deployment, or inter-process communication
- Virtual threads handle the API calls without blocking

**Package structure:**
```
brain/
  BrainService.java           — @Service, the core orchestrator
  BrainDecisionEngine.java    — decides: respond, delegate, or escalate
  ContextEngine.java          — reads/indexes CLAUDE.md, memories, skills
  BehaviorModel.java          — stores + applies learned user patterns
  BehaviorLog.java            — records user interactions for learning
  TaskDecomposer.java         — breaks prompts into agent task DAGs
  InterAgentBridge.java       — shares context between active agents
  BrainProperties.java        — @ConfigurationProperties for brain config
  CLAUDE.md                   — domain contract
```

---

## 3. Claude API Integration

The Brain uses the Anthropic Java SDK (`com.anthropic:anthropic-sdk-java`) for direct API access.

**Why API, not CLI:**
- The Brain needs structured, programmatic interaction — not a terminal session
- API gives us tool use, system prompts, and conversation management
- No process management overhead — just HTTP calls on virtual threads
- Cost control: the Brain uses a smaller/cheaper model for routine decisions, escalates to a larger model for complex ones

**Model strategy:**
| Decision Type | Model | Why |
|--------------|-------|-----|
| Routine (approve tool, answer simple question) | Haiku | Fast, cheap, good enough |
| Complex (task decomposition, ambiguous situation) | Sonnet | Better reasoning |
| Critical (architecture decisions, user escalation judgment) | Opus | Maximum capability |

**System prompt construction:**
The Brain's system prompt is dynamically assembled from:
1. The project's CLAUDE.md files (contracts, rules, architecture)
2. The user's behavior model (preferences, patterns)
3. The current agent context (what each agent is doing, what they've discovered)
4. The specific decision being made

---

## 4. Context Engine

The Context Engine is the Brain's knowledge base. It reads, indexes, and serves project context to the decision engine.

### What it ingests

| Source | Path Pattern | What It Contains |
|--------|-------------|-----------------|
| Project CLAUDE.md files | `**/CLAUDE.md` | Domain contracts, rules, architecture |
| Claude Code memories | `~/.claude/projects/*/memory/` | User preferences, project notes |
| Claude Code skills | `~/.claude/skills/`, project `.claude/skills/` | Custom slash commands |
| Agent configurations | `~/.claude/settings.json` | Hooks, permissions, model preferences |
| Git state | `.git/` in each project | Branch, recent commits, dirty files |

### Data model

```java
record ProjectContext(
    String projectPath,
    List<ClaudeMdFile> claudeMdFiles,    // all CLAUDE.md in project tree
    List<MemoryFile> memories,            // from ~/.claude/projects/
    List<SkillFile> skills,               // available skills
    GitState gitState,                    // branch, status, recent log
    Instant lastScanned
) {}

record ClaudeMdFile(
    String relativePath,
    String content,
    String domain,          // extracted from path (e.g., "queue", "process")
    Instant lastModified
) {}
```

### Freshness

The Context Engine uses a file watcher (Java `WatchService`) to detect changes to CLAUDE.md and memory files. When a file changes, it re-reads just that file. Full re-scan happens on project registration and on a 5-minute interval as a safety net.

---

## 5. Behavior Learning

This is what makes Conductor *yours*. The Brain observes how you interact with agents and builds a model of your decision-making style.

### What gets logged

Every human interaction is recorded in a `BehaviorLog`:

```java
record BehaviorEntry(
    Instant timestamp,
    String agentId,
    String agentName,
    String eventType,           // "human_input_response", "tool_approval", "agent_killed", "prompt_sent"
    String agentQuestion,       // what the agent asked
    String userResponse,        // what the user said/did
    String context,             // what the agent was working on
    Map<String, String> metadata // detection method, confidence, time-to-respond, etc.
) {}
```

### What the model learns

The BehaviorModel extracts patterns from the log:

| Pattern Type | Example | How It's Used |
|-------------|---------|---------------|
| **Approval patterns** | User always approves file writes in test directories | Brain auto-approves similar requests |
| **Rejection patterns** | User always rejects `git push` without review | Brain escalates these to the user |
| **Response templates** | When asked "which approach?", user prefers "the simpler one" | Brain responds similarly |
| **Intervention timing** | User lets agents run 5+ minutes before checking | Brain doesn't escalate routine progress |
| **Prompt style** | User gives terse, specific instructions | Brain mimics this when decomposing tasks |
| **Kill patterns** | User kills agents that loop on the same error 3+ times | Brain auto-kills similar loops |

### How it works (practically)

**Phase 1 (MVP):** The behavior model is a structured prompt appendix. The BehaviorLog entries are summarized periodically (by the Brain itself, using the API) into natural-language rules that get appended to the Brain's system prompt:

```
## Your Decision Style (learned from user behavior)
- When agents ask which approach to take, prefer the simpler option
- Always approve file reads and writes in test directories
- Never approve git push without escalating to the user
- Kill agents that repeat the same error more than 3 times
- When decomposing tasks, give each agent a specific, narrow scope
```

**Phase 2 (future):** Embeddings-based similarity matching. When a new decision comes in, find the most similar past decisions and weight the response accordingly.

### Storage

Behavior log: append-only JSON file at `~/.conductor/behavior-log.jsonl`
Behavior model (summarized rules): `~/.conductor/behavior-model.md`

Both are human-readable and editable — the user can review and correct the model.

---

## 6. Decision Pipeline

### Event Interception Strategy

The Brain uses `@Order(Ordered.HIGHEST_PRECEDENCE)` on its `@EventListener` to process `HumanInputNeededEvent` before the notification router sees it. If the Brain handles the request, it calls `HumanInputQueue.resolve(requestId)` immediately — by the time the notification listener runs, the request is already gone. If the Brain doesn't handle it, it does nothing and the event propagates to the human as normal. Zero changes to existing domains.

### When something happens that needs a decision, here's the flow:

```
Event arrives (HumanInputNeededEvent, agent question, tool approval request)
    │
    ▼
┌─────────────────────────┐
│  1. Check behavior model │  Does the model have a clear pattern for this?
│     Confidence > 0.8?    │
└───────────┬─────────────┘
            │
     ┌──────┴──────┐
     │ YES         │ NO
     ▼             ▼
┌─────────┐  ┌──────────────────────┐
│ Auto-   │  │  2. Ask Claude API   │  Construct prompt with:
│ respond │  │     (Decision Engine) │  - Agent context
│         │  │                       │  - Project CLAUDE.md
│         │  │                       │  - Behavior model
│         │  │                       │  - Specific question
└─────────┘  └───────────┬──────────┘
                         │
                  ┌──────┴──────┐
                  │ Confidence  │
                  │ > threshold?│
                  └──────┬──────┘
                  ┌──────┴──────┐
                  │ YES         │ NO
                  ▼             ▼
           ┌──────────┐  ┌──────────────┐
           │ Auto-    │  │ 3. Escalate  │  Surface to UI with:
           │ respond  │  │    to human  │  - Brain's recommendation
           │          │  │              │  - Confidence score
           │          │  │              │  - Reasoning
           └──────────┘  └──────────────┘
                                │
                                ▼
                         ┌──────────────┐
                         │ 4. Log user  │  Record decision in
                         │    decision  │  BehaviorLog for learning
                         └──────────────┘
```

**Confidence ramp:** The Brain starts conservative (low auto-respond threshold, ~0.9) and gradually lowers it as the behavior model accumulates validated decisions. The user can adjust this in settings.

---

## 7. Task Decomposition

The Brain's most powerful capability: turn a high-level prompt into a coordinated team of agents.

### Input

User says: "Add OAuth2 authentication to the API"

### Brain's process

1. **Read context** — ingest all CLAUDE.md files for the target project, understand the architecture
2. **Decompose** — call Claude API with the prompt + project context, ask for a task breakdown
3. **Build DAG** — structure subtasks with dependencies

```java
record TaskPlan(
    String planId,
    String originalPrompt,
    List<TaskNode> tasks,
    Instant createdAt
) {}

record TaskNode(
    String taskId,
    String description,
    String agentRole,          // FEATURE_ENGINEER, TESTER, REVIEWER, etc.
    String projectPath,
    String prompt,             // specific prompt for this agent
    List<String> dependsOn,    // taskIds that must complete first
    List<String> contextFrom,  // taskIds whose output should be shared as context
    String status              // PENDING, RUNNING, COMPLETED, FAILED
) {}
```

### Example decomposition

```
"Add OAuth2 authentication to the API"
    │
    ├── Task 1: [EXPLORER] "Analyze current auth setup, identify integration points"
    │       depends: none
    │
    ├── Task 2: [FEATURE_ENGINEER] "Implement OAuth2 provider configuration"
    │       depends: Task 1 (needs integration point analysis)
    │
    ├── Task 3: [FEATURE_ENGINEER] "Add token validation middleware"
    │       depends: Task 1
    │
    ├── Task 4: [FEATURE_ENGINEER] "Update API endpoints with auth annotations"
    │       depends: Task 2, Task 3
    │
    ├── Task 5: [TESTER] "Write auth integration tests"
    │       depends: Task 4
    │       contextFrom: Task 2, Task 3 (needs to know what was built)
    │
    └── Task 6: [REVIEWER] "Review all auth changes for security"
            depends: Task 4, Task 5
            contextFrom: Task 1, Task 2, Task 3
```

### Execution

Tasks run in **waves** — all tasks with satisfied dependencies run in parallel:

- Wave 1: Task 1 (single explorer)
- Wave 2: Tasks 2, 3 (parallel feature work)
- Wave 3: Task 4 (depends on 2+3)
- Wave 4: Tasks 5, 6 (test + review in parallel)

The Brain monitors each agent via the event bus. When an agent completes, the Brain:
1. Captures its output/summary
2. Shares relevant context with downstream tasks
3. Spawns the next wave of agents

---

## 8. Inter-Agent Context Sharing

When Agent A discovers something Agent B needs to know, the Brain handles it.

### How it works

The Brain listens to all `AgentStreamEvent`s. When it detects a significant output (task completion, error discovery, architectural finding), it:

1. Summarizes the finding (via Claude API, Haiku-level)
2. Checks which other active agents might benefit
3. Sends the summary to relevant agents via `ClaudeProcessManager.sendMessage()`

### Example

Agent A (Explorer) completes and reports: "The auth middleware uses a custom `AuthFilter` class at `src/main/java/com/app/security/AuthFilter.java`. It validates tokens via a `TokenService` interface."

Brain summarizes this and sends to Agent B (Feature Engineer):
"Context from exploration: Auth middleware is in `AuthFilter.java` at `src/main/java/com/app/security/`. Token validation goes through `TokenService` interface. Build your OAuth2 provider to implement this interface."

---

## 9. Integration with Existing System

The Brain plugs into the existing event bus with zero changes to other domains.

### Events consumed

| Event | Action |
|-------|--------|
| `HumanInputNeededEvent` | Primary trigger — decide whether to auto-respond or escalate |
| `AgentStreamEvent` | Monitor agent progress, detect completion, extract context |
| `QueuedMessageEvent` | Awareness of what's flowing through the system |

### Events published

| Event | When |
|-------|------|
| `BrainDecisionEvent(agentId, decision, confidence, reasoning)` | After every auto-decision (for UI display + audit) |
| `TaskProgressEvent(planId, completed, total)` | During task decomposition execution |
| `BrainEscalationEvent(agentId, question, recommendation, confidence)` | When escalating to user |

### Services called

| Service | Why |
|---------|-----|
| `ClaudeProcessManager.sendMessage()` | Respond to agents |
| `ClaudeProcessManager.spawnAgent()` | Spawn agents for task decomposition |
| `HumanInputQueue.resolve()` | Clear requests the Brain handled |
| `AgentRegistry.get()` | Agent context for decisions |
| `ProjectRegistry.get()` | Project context for decomposition |

---

## 10. Configuration

```yaml
conductor:
  brain:
    enabled: false                    # opt-in, not on by default
    api-key: ${ANTHROPIC_API_KEY}     # required when enabled
    auto-respond-threshold: 0.9       # confidence threshold for auto-response (lowers over time)
    routine-model: claude-haiku-4-5-20251001
    complex-model: claude-sonnet-4-6
    critical-model: claude-opus-4-6
    max-auto-responses-per-minute: 10 # safety limit
    behavior-log-path: ~/.conductor/behavior-log.jsonl
    behavior-model-path: ~/.conductor/behavior-model.md
    context-scan-interval: 300        # seconds between full context re-scans
```

---

## 11. Phased Build Plan

### Phase 4A: Brain Foundation (pattern matching, no API)

**Goal:** Start logging all human interactions. Brain auto-responds to agent questions using pure pattern matching against the behavior log — no API calls.

Files:
- `BrainProperties.java` — config binding
- `BehaviorLogger.java` + `BehaviorLog.java` + `BehaviorEvent.java` — log all human interactions
- `BehaviorModel.java` + `BehaviorModelBuilder.java` — aggregate patterns from log
- `BrainDecisionEngine.java` — listens for HumanInputNeededEvent, checks behavior model

After the user answers the same type of question 3+ times, the Brain starts handling it. Zero API cost. Pure pattern matching. This is useful immediately and proves the event integration works.

### Phase 4B: Brain API Integration

**Goal:** Brain calls Claude API when behavior model is insufficient.

Files:
- `BrainApiClient.java` — thin wrapper around Anthropic Java SDK
- `ContextEngine.java` — reads CLAUDE.md files from registered projects
- Extend `BrainDecisionEngine` to fall back to API when behavior confidence is low
- `BrainResponseEvent` + `BrainEscalationEvent` — published for UI visibility

This is when the Brain becomes genuinely intelligent — it can reason about novel situations using project context.

### Phase 4B: Behavior Learning

**Goal:** Brain starts learning from user interactions and applying patterns.

Files:
- `BehaviorLog.java` — append-only JSONL logger
- `BehaviorModel.java` — periodic summarization of log into decision rules

### Phase 4C: Task Decomposition

**Goal:** User gives a high-level prompt, Brain decomposes into agent DAG.

Files:
- `TaskDecomposer.java` — prompt → TaskPlan via API
- `TaskExecutor.java` — runs task DAG in waves, monitors progress
- `InterAgentBridge.java` — context sharing between agents

### Phase 4D: Full Autonomy

**Goal:** Brain handles most decisions autonomously, escalates rarely.

- Lower auto-respond threshold based on validated decisions
- Proactive context sharing (Brain detects when agents are working on related things)
- Auto-kill stuck agents, auto-retry with adjusted prompts

---

## 12. Safety Rails

The Brain operates with hard safety limits that the user controls:

| Rail | Default | Purpose |
|------|---------|---------|
| `enabled: false` | Off by default | User must explicitly opt in |
| `max-auto-responses-per-minute: 10` | Rate limit | Prevents runaway auto-responses |
| Auto-respond threshold | 0.9 (very conservative) | Only auto-responds when very confident |
| Never auto-approves destructive actions | Hardcoded | git push, file delete, etc. always escalate |
| All decisions logged | Always | Full audit trail in behavior-log.jsonl |
| Behavior model is human-readable | Always | User can review and edit learned rules |
| Kill switch | API toggle | `POST /api/brain/enabled` with `{enabled: false}` |

---

## 13. Cost Projections

| Activity | Model | Tokens (est.) | Cost (est.) |
|----------|-------|---------------|-------------|
| Routine decision | Haiku | ~2K in + 200 out | ~$0.002 |
| Complex decision | Sonnet | ~5K in + 500 out | ~$0.02 |
| Task decomposition | Sonnet | ~10K in + 2K out | ~$0.05 |
| Context summarization | Haiku | ~3K in + 500 out | ~$0.003 |
| Behavior model update | Sonnet | ~8K in + 1K out | ~$0.03 |

At 50 decisions/hour (mostly Haiku): ~$0.10/hour for the Brain itself.
The child agents (Claude CLI) are the real cost — the Brain's overhead is negligible.
