# Claude Orchestrator - Architectural Design Document

**Project:** Claude Orchestrator (codename: **Conductor**)
**Author:** Matthew Culliton + Claude Code (planning session 2026-03-31)
**Status:** PLAN - Not yet built
**Version:** 0.1.0-draft

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Problem Statement](#2-problem-statement)
3. [System Overview](#3-system-overview)
4. [Technology Stack Decision](#4-technology-stack-decision)
5. [Core Architecture](#5-core-architecture)
6. [Component Deep Dives](#6-component-deep-dives)
7. [Communication Protocol](#7-communication-protocol)
8. [Queue Architecture](#8-queue-architecture)
9. [Human Input Detection](#9-human-input-detection)
10. [Agent Lifecycle Management](#10-agent-lifecycle-management)
11. [IntelliJ Integration](#11-intellij-integration)
12. [Persistence & Crash Recovery](#12-persistence--crash-recovery)
13. [Security Model](#13-security-model)
14. [Dashboard & UI](#14-dashboard--ui)
15. [Phased Delivery Plan](#15-phased-delivery-plan)
16. [Risk Registry](#16-risk-registry)
17. [Open Questions](#17-open-questions)

---

## 1. Executive Summary

Conductor is a **meta-orchestration platform** for managing fleets of Claude Code agents across multiple IntelliJ projects simultaneously. It solves the signal-to-noise problem inherent in running 50+ concurrent AI agents by providing intelligent message queuing, priority-based notifications, automatic detection of human-input-needed states, and a meta-agent capable of decomposing tasks and spawning specialized sub-agents.

The system is designed for a single power user (scaling to a small team later) who operates dozens of Claude Code agents across multiple codebases and needs a unified command surface to stay in control without being overwhelmed.

**Core value proposition:** Transform agent management from "watch 50 terminal tabs" into "glance at one dashboard, respond to what matters."

---

## 2. Problem Statement

### Current Pain Points

| Pain | Impact | Severity |
|------|--------|----------|
| Agent output floods terminal | Critical signals buried in noise | HIGH |
| No cross-project visibility | Context-switching between IDE windows | HIGH |
| Agents block on questions silently | Wasted time; agent idle until noticed | CRITICAL |
| No unified notification system | Must poll each agent manually | HIGH |
| Manual agent spawning per task | Repetitive, error-prone setup | MEDIUM |
| No task decomposition automation | Complex tasks require manual breakdown | MEDIUM |
| No centralized audit trail | Cannot reconstruct what happened across agents | MEDIUM |
| Crash recovery is manual | Lost state when terminal dies or agent crashes | HIGH |

### Scale Parameters

- **Concurrent agents:** 50+ (design for 200)
- **Concurrent projects:** 5-15 IntelliJ projects
- **Messages per minute across all agents:** estimated 200-500 in burst
- **Human decisions per hour:** estimated 5-20 (the ones that matter)
- **Agent spawning frequency:** 10-30 new agents per work session

---

## 3. System Overview

```
                         +-----------------------+
                         |     CONDUCTOR UI      |
                         |   (Desktop Dashboard) |
                         |   React + Electron    |
                         +----------+------------+
                                    |
                              WebSocket / REST
                                    |
+-------------------+    +----------v------------+    +-------------------+
|  IntelliJ Plugin  |<-->|   CONDUCTOR SERVER    |<-->|  Claude CLI       |
|  (IDE Integration)|    |   (Spring Boot Core)  |    |  Process Manager  |
+-------------------+    +----------+------------+    +-------------------+
                                    |
                    +---------------+---------------+
                    |               |               |
              +-----v-----+  +-----v-----+  +------v----+
              |  Message   |  |  Agent    |  |  Human    |
              |  Queue     |  |  Registry |  |  Input    |
              |  Engine    |  |  + State  |  |  Detector |
              +-----------+  +-----------+  +-----------+
                    |               |               |
              +-----v-----+  +-----v-----+  +------v----+
              | Noise      |  | Task      |  | Notifica- |
              | Filter     |  | Decomposer|  | tion      |
              | + Dedup    |  | (Meta-Agt)|  | Router    |
              +-----------+  +-----------+  +-----------+
                    |               |               |
              +-----v-----------------------------------------+
              |            PERSISTENCE LAYER                   |
              |  PostgreSQL (state) + SQLite (local fallback)  |
              +-----------------------------------------------+
```

---

## 4. Technology Stack Decision

### Why Java/Spring Boot for the Server Core

| Factor | Decision | Rationale |
|--------|----------|-----------|
| **Language** | Java 17+ | User has 8+ years of Java expertise. Fastest path to production. |
| **Framework** | Spring Boot 3.x | Proven event-driven patterns. User already runs a 7-agent Spring system. WebSocket support built in. |
| **Build** | Gradle (Kotlin DSL) | Faster builds than Maven for a multi-module project. Better plugin composition. |
| **Async** | Virtual Threads (Project Loom) | Java 21 virtual threads are ideal for managing hundreds of concurrent agent I/O streams. Each agent gets its own virtual thread. No thread pool tuning. |
| **Messaging** | In-process event bus (Spring Events) + optional Redis Streams | Start simple. Graduate to Redis when multi-node is needed. |
| **Persistence** | PostgreSQL (primary) + SQLite (local-first fallback) | Postgres for production state. SQLite for offline/portable mode. |
| **Desktop UI** | Electron + React + TypeScript | Familiar web tech. Native OS notifications. System tray integration. |
| **IntelliJ Plugin** | Kotlin + IntelliJ Platform SDK | Plugin API is Kotlin-first. Thin plugin that communicates with Conductor server. |

### Why NOT Other Options

- **Python/FastAPI:** User is a Java expert. Python orchestration would slow development and introduce a language boundary.
- **Go:** Good for CLIs but lacks the ecosystem for desktop + IDE plugin + rich event processing.
- **Pure IntelliJ Plugin:** Too constrained. Cannot run headless, hard to do complex queuing, limited to JetBrains runtime.
- **VS Code Extension:** User is an IntelliJ user. Wrong IDE.
- **Tauri (instead of Electron):** Lighter, but harder to do real-time streaming dashboards. Electron is pragmatic here.

### Java Version: 21 (not 17)

This project should target Java 21 specifically to leverage:
- **Virtual Threads:** Critical for managing hundreds of concurrent agent process streams without thread pool exhaustion.
- **Pattern Matching:** Cleaner event handling code.
- **Record Patterns:** Immutable event types.
- **Sequenced Collections:** Ordered queue operations.

The user's existing project targets 17 (compiled with 25), so 21 is a comfortable step.

---

## 5. Core Architecture

### Architectural Style: Event-Driven + CQRS

The system uses an internal event bus with CQRS (Command Query Responsibility Segregation) separation:

- **Command side:** Agent spawning, task decomposition, human responses, configuration changes
- **Query side:** Dashboard reads, notification feeds, agent status queries
- **Event bus:** All state changes propagate as typed events (same pattern as medallioGenAi)

### Module Structure

```
conductor/
├── conductor-server/           # Spring Boot core application
│   └── src/main/java/dev/conductor/
│       ├── agent/              # Agent lifecycle, registry, state machine
│       ├── queue/              # Message queuing, noise filtering, dedup
│       ├── notification/       # Notification routing, urgency classification
│       ├── humaninput/         # Human input detection, prompt queuing
│       ├── decomposer/        # Task decomposition, sub-agent orchestration
│       ├── process/            # Claude CLI process management
│       ├── project/            # Multi-project registry, CLAUDE.md discovery
│       ├── event/              # Event bus, event types, event store
│       ├── persistence/        # JPA entities, repositories, snapshots
│       ├── api/                # REST + WebSocket controllers
│       ├── config/             # Spring configuration
│       └── model/              # Shared domain types
├── conductor-ui/               # Electron + React dashboard
│   ├── src/
│   │   ├── components/         # React components
│   │   ├── hooks/              # WebSocket, notification hooks
│   │   ├── stores/             # State management (Zustand)
│   │   └── services/           # API client layer
│   └── electron/               # Electron main process, tray, notifications
├── conductor-intellij-plugin/  # IntelliJ Platform plugin
│   └── src/main/kotlin/dev/conductor/intellij/
│       ├── toolwindow/         # Agent status panel in IDE
│       ├── actions/            # IDE actions (spawn agent, respond to prompt)
│       ├── notifications/      # IDE notification integration
│       └── client/             # HTTP/WebSocket client to Conductor server
├── conductor-cli/              # Optional: thin CLI wrapper
└── conductor-common/           # Shared types (events, DTOs) as a library
```

### Process Architecture

```
┌──────────────────────────────────────────────┐
│              CONDUCTOR SERVER (JVM)           │
│                                               │
│  ┌─────────┐  ┌─────────────┐  ┌──────────┐ │
│  │ Agent   │  │ Claude CLI  │  │ Event    │ │
│  │ Registry│  │ Process Pool│  │ Store    │ │
│  │         │  │ (VThreads)  │  │          │ │
│  └────┬────┘  └──────┬──────┘  └────┬─────┘ │
│       │              │              │        │
│  ┌────v──────────────v──────────────v─────┐  │
│  │          INTERNAL EVENT BUS            │  │
│  └────┬──────────────┬──────────────┬─────┘  │
│       │              │              │        │
│  ┌────v────┐  ┌──────v─────┐  ┌────v─────┐  │
│  │ Queue   │  │ Notifier   │  │ Human    │  │
│  │ Engine  │  │            │  │ Input    │  │
│  │         │  │            │  │ Detector │  │
│  └─────────┘  └────────────┘  └──────────┘  │
│                                               │
│  ┌────────────────────────────────────────┐   │
│  │  WebSocket Server (to UI + Plugin)     │   │
│  └────────────────────────────────────────┘   │
└──────────────────────────────────────────────┘
         │                    │
    ┌────v────┐         ┌────v─────────────┐
    │Electron │         │IntelliJ Plugin   │
    │Dashboard│         │(per project)     │
    └─────────┘         └──────────────────┘
```

---

## 6. Component Deep Dives

### 6.1 Agent Registry & State Machine

Every Claude Code agent instance is tracked as a first-class entity.

**Agent States:**

```
                    ┌─────────┐
          spawn     │LAUNCHING│
         ────────>  │         │
                    └────┬────┘
                         │ CLI process started
                    ┌────v────┐
                    │  ACTIVE │<──────────────┐
                    │         │               │
                    └────┬────┘          human responds
                         │                    │
              needs input│               ┌────┴────┐
                         ├──────────────>│ BLOCKED │
                         │               │(awaiting│
                         │               │ input)  │
                         │               └─────────┘
                    ┌────v────┐
                    │COMPLETED│
                    │         │
                    └────┬────┘
                         │ error/crash
                    ┌────v────┐
                    │  FAILED │
                    │         │
                    └─────────┘
```

**Agent Record:**

```java
public record AgentRecord(
    UUID agentId,
    String sessionId,           // Claude CLI session ID
    String projectPath,         // Working directory
    String projectName,         // Human-readable project name
    AgentRole role,             // e.g., FEATURE_ENGINEER, REFACTORER, TESTER
    AgentState state,           // LAUNCHING, ACTIVE, BLOCKED, COMPLETED, FAILED
    String systemPrompt,        // The agent's system prompt
    String currentTask,         // What the agent is working on
    Instant spawnedAt,
    Instant lastActivityAt,
    int messageCount,
    int errorCount,
    Map<String, String> metadata
) {}
```

**Agent Lifecycle Manager** responsibilities:
- Spawn Claude CLI processes with correct flags (`--print`, `--output-format stream-json`, `--session-id`)
- Monitor process health (heartbeat via output stream)
- Detect crashes and restart with session resume (`--resume`)
- Track resource usage (API cost via `--max-budget-usd` tracking)
- Enforce concurrent agent limits per project and globally

### 6.2 Claude CLI Process Manager

This is the critical integration layer. Conductor manages Claude Code as **child processes**.

**Spawning an Agent:**

```bash
claude \
  --print \
  --output-format stream-json \
  --input-format stream-json \
  --session-id <uuid> \
  --model <model> \
  --system-prompt "<agent-specific-prompt>" \
  --agent <agent-name> \
  --permission-mode auto \
  --add-dir "<project-path>" \
  --max-budget-usd <limit> \
  "<initial-prompt>"
```

**Key CLI flags leveraged:**

| Flag | Purpose |
|------|---------|
| `--print` | Non-interactive mode; output goes to stdout |
| `--output-format stream-json` | Real-time JSON stream of events |
| `--input-format stream-json` | Allows sending follow-up messages via stdin |
| `--session-id <uuid>` | Deterministic session ID for tracking and resume |
| `--resume <session-id>` | Resume crashed/stopped sessions |
| `--agent <name>` | Use predefined agent configurations |
| `--permission-mode auto` | Auto-approve tool use (for sandboxed agents) |
| `--max-budget-usd` | Cost guardrail per agent |
| `--include-partial-messages` | Stream partial completions for real-time display |
| `--json-schema` | Structured output for meta-agent coordination |

**Process Manager Design:**

```java
@Service
public class ClaudeProcessManager {

    // Each agent runs on its own virtual thread
    // Reading from stdout is blocking I/O - perfect for virtual threads
    
    record ManagedProcess(
        UUID agentId,
        Process process,
        OutputStream stdin,     // Send messages/responses
        Thread outputReader,    // Virtual thread reading stdout
        Thread errorReader      // Virtual thread reading stderr
    ) {}
    
    // Stream-JSON protocol: each line is a JSON event
    // Types: "assistant", "tool_use", "tool_result", "error", "system"
    // We parse these to detect:
    //   - Regular output (queue for noise filtering)
    //   - Tool usage (audit trail)
    //   - Questions to user (human input detection)
    //   - Errors (escalation)
    //   - Completion (state transition)
}
```

**Stdin Protocol for Follow-ups:**

When an agent is BLOCKED and we need to send a human response:

```json
{"type": "user_message", "content": "Use approach B, it's simpler"}
```

This is piped to the agent's stdin via the `--input-format stream-json` channel.

### 6.3 Message Queue Engine

The queue is the heart of the noise-reduction system.

**Queue Tiers:**

```
                 ┌──────────────────────┐
  Agent Output──>│   RAW INGESTION      │ All messages land here first
                 └──────────┬───────────┘
                            │
                 ┌──────────v───────────┐
                 │   CLASSIFICATION     │ Urgency + Category tagging
                 └──────────┬───────────┘
                            │
              ┌─────────────┼─────────────┐
              │             │             │
     ┌────────v──┐  ┌──────v──┐  ┌───────v─────┐
     │ CRITICAL  │  │ NORMAL  │  │    NOISE    │
     │ (instant) │  │ (batch) │  │  (suppress) │
     └───────────┘  └─────────┘  └─────────────┘
```

**Message Classification Rules:**

```java
public enum MessageUrgency {
    CRITICAL,   // Human input needed, error, crash, security alert
    HIGH,       // Task completed, significant milestone, warning
    NORMAL,     // Progress update, file changed, test result
    LOW,        // Verbose output, thinking steps, tool invocations
    NOISE       // Duplicate messages, status polling, heartbeats
}
```

**Classification is multi-layered:**

1. **Pattern matching (fast path):** Regex-based detection of known patterns
   - `"needs human input"` / `"please confirm"` / `"should I"` --> CRITICAL
   - `"error"` / `"failed"` / `"exception"` --> HIGH
   - `"completed"` / `"done"` / `"finished"` --> HIGH
   - `"reading file"` / `"searching"` --> LOW

2. **Semantic dedup (batch path):** Group similar messages within a time window
   - "Reading file src/Foo.java" + "Reading file src/Bar.java" --> "Reading 2 files in src/"
   - Collapse identical progress messages from same agent

3. **Agent-role-based filtering:** Different roles have different noise profiles
   - TESTER agents: test results are HIGH, everything else is LOW
   - REFACTORER agents: diff summaries are HIGH, file reads are NOISE

4. **User-configurable overrides:** The user can set per-agent or per-project filter rules

**Batching Strategy:**

- CRITICAL: Deliver immediately, interrupt user
- HIGH: Deliver within 5 seconds, batch if multiple arrive
- NORMAL: Batch into 30-second digest windows
- LOW: Available on-demand in dashboard, never pushed
- NOISE: Logged to event store only, invisible by default

### 6.4 Notification Router

Multiple delivery channels with urgency-based routing:

```
                    ┌───────────────┐
                    │  Notification │
                    │    Router     │
                    └───────┬───────┘
                            │
          ┌─────────────────┼─────────────────┐
          │                 │                 │
  ┌───────v───────┐ ┌──────v──────┐ ┌────────v────────┐
  │   Desktop     │ │  IntelliJ   │ │   Dashboard     │
  │  OS Notif.    │ │  IDE Notif. │ │  Real-time      │
  │  (Electron)   │ │  (Plugin)   │ │  (WebSocket)    │
  └───────────────┘ └─────────────┘ └─────────────────┘
```

**Routing Rules:**

| Urgency | Desktop Toast | IDE Notification | Dashboard | Sound |
|---------|--------------|-----------------|-----------|-------|
| CRITICAL | Yes (sticky) | Yes (balloon) | Yes (red badge) | Alert tone |
| HIGH | Yes (auto-dismiss 10s) | Yes (event log) | Yes (amber badge) | Subtle chime |
| NORMAL | No | No | Yes (list) | None |
| LOW | No | No | Expandable | None |

**Do Not Disturb Mode:**
- Suppresses all notifications except CRITICAL
- Batches everything for later review
- Activatable via dashboard, system tray, or keyboard shortcut

### 6.5 Human Input Detection Engine

This is the hardest problem and the highest-value feature.

**Detection Strategies (layered, from cheapest to most expensive):**

**Layer 1: Stream-JSON Event Analysis (Free)**

Claude Code's `stream-json` output includes structured events. When the agent produces a `user_message`-type event or uses `SendUserMessage` tool, this is an explicit signal that input is needed.

```json
{"type": "tool_use", "name": "SendUserMessage", "input": {"message": "Should I use approach A or B?"}}
```

This is the cleanest signal and should be intercepted before it reaches the terminal.

**Layer 2: Output Pattern Matching (Cheap)**

Regex patterns against assistant message content:

```
QUESTION_PATTERNS = [
    /should I .+\?/i,
    /do you want me to .+\?/i,
    /please (confirm|choose|decide|select)/i,
    /option [A-D]:/i,
    /which (approach|method|strategy|option)/i,
    /waiting for (your|user) (input|response|decision)/i,
    /\[requires human decision\]/i,
    /before I proceed/i,
    /I need clarification/i
]
```

**Layer 3: Activity Stall Detection (Cheap)**

If an agent has been ACTIVE but produced no tool_use events for > N seconds (configurable, default 30s) after producing a question-like message, it is likely blocked.

```java
record StallDetection(
    UUID agentId,
    Instant lastToolUse,
    Instant lastAssistantMessage,
    String lastMessageContent,
    boolean containsQuestionPattern
) {
    boolean isLikelyBlocked(Duration threshold) {
        return containsQuestionPattern 
            && Duration.between(lastToolUse, Instant.now()).compareTo(threshold) > 0;
    }
}
```

**Layer 4: Structured Output Probing (Moderate cost)**

For the meta-agent specifically, require agents to emit structured status updates:

```json
{
    "status": "BLOCKED",
    "reason": "HUMAN_INPUT_NEEDED",
    "question": "The tests are failing because the DB schema is out of date. Should I: (A) run the migration, (B) update the test fixtures, or (C) skip these tests?",
    "context": "Running test suite for user-service module",
    "options": ["A: Run migration", "B: Update fixtures", "C: Skip tests"],
    "urgency": "HIGH",
    "blockingSince": "2026-03-31T14:22:00Z"
}
```

This is achieved by including instructions in the agent's system prompt to emit these structured status messages. The `--json-schema` flag can enforce the schema.

**Layer 5: Process State Analysis (Free but indirect)**

Monitor the Claude CLI process:
- Process is alive but stdin buffer is draining (agent is reading from us)
- Process stdout has gone quiet after a burst of output
- Process CPU usage dropped to near-zero (waiting for I/O)

**Human Input Queue:**

When input is detected, it goes to a priority queue:

```java
public record HumanInputRequest(
    UUID requestId,
    UUID agentId,
    String projectName,
    String agentRole,
    String question,
    List<String> suggestedOptions,
    String context,              // Recent agent activity summary
    MessageUrgency urgency,
    Instant detectedAt,
    Duration blockedFor
) implements Comparable<HumanInputRequest> {
    // Sort by urgency, then by blocked duration (longest first)
}
```

**Response Flow:**

1. User sees prompt in dashboard/IDE/notification
2. User types response or clicks option
3. Response sent via WebSocket to Conductor server
4. Server pipes response to agent's stdin via stream-json protocol
5. Agent unblocks and continues

### 6.6 Task Decomposer (Meta-Agent)

The meta-agent is itself a Claude Code agent, but with a special system prompt and structured output requirements.

**Meta-Agent Architecture:**

```
┌────────────────────────────────────────────┐
│              META-AGENT                     │
│  (Claude Code with orchestration prompt)    │
│                                             │
│  Input: High-level task description         │
│  Output: Structured decomposition plan      │
│                                             │
│  ┌──────────────────────────────────┐       │
│  │  Task Decomposition Schema       │       │
│  │  (enforced via --json-schema)    │       │
│  │                                  │       │
│  │  - Subtask list with ordering    │       │
│  │  - Agent role per subtask        │       │
│  │  - Dependencies between tasks    │       │
│  │  - System prompts for each agent │       │
│  │  - Success criteria              │       │
│  │  - Merge strategy                │       │
│  └──────────────────────────────────┘       │
└──────────────────┬─────────────────────────┘
                   │
         Structured JSON output
                   │
┌──────────────────v─────────────────────────┐
│         CONDUCTOR ORCHESTRATION ENGINE       │
│                                             │
│  1. Parse decomposition plan                │
│  2. Resolve dependencies (topological sort) │
│  3. Spawn sub-agents for independent tasks  │
│  4. Monitor progress                        │
│  5. Feed outputs of completed tasks to      │
│     dependent tasks                         │
│  6. Run integration/review agent at end     │
│  7. Report final result to user             │
└─────────────────────────────────────────────┘
```

**Decomposition Output Schema:**

```json
{
    "taskId": "uuid",
    "description": "Implement user authentication with OAuth2",
    "subtasks": [
        {
            "subtaskId": "uuid",
            "name": "Design DB schema for auth",
            "agentRole": "ARCHITECT",
            "systemPromptOverrides": "Focus on...",
            "dependsOn": [],
            "projectPath": "/path/to/project",
            "estimatedComplexity": "MEDIUM",
            "successCriteria": "Schema file created and validated",
            "maxBudgetUsd": 0.50
        },
        {
            "subtaskId": "uuid",
            "name": "Implement OAuth2 endpoints",
            "agentRole": "FEATURE_ENGINEER",
            "dependsOn": ["<schema-subtask-id>"],
            "projectPath": "/path/to/project",
            "estimatedComplexity": "HIGH",
            "successCriteria": "All endpoints return correct responses",
            "maxBudgetUsd": 2.00
        }
    ],
    "mergeStrategy": "SEQUENTIAL_COMMIT",
    "integrationAgent": {
        "role": "CODE_REVIEWER",
        "reviewScope": "ALL_SUBTASK_OUTPUTS"
    }
}
```

**Execution Engine:**

```java
@Service
public class TaskExecutionEngine {
    
    // Build dependency DAG from decomposition plan
    // Use topological sort to find execution waves
    // Wave 1: all tasks with no dependencies (run in parallel)
    // Wave 2: tasks depending only on Wave 1 tasks
    // ... etc.
    
    // Between waves:
    //   - Collect outputs from completed agents
    //   - Inject context into next wave's prompts
    //   - Check success criteria
    //   - If any task failed, consult meta-agent for replanning
}
```

**Git Worktree Integration:**

For parallel sub-agents working on the same project, use git worktrees:

```bash
# Each sub-agent gets its own worktree to avoid conflicts
claude --worktree "subtask-auth-schema" --session-id <uuid> ...
```

This leverages Claude CLI's built-in `--worktree` flag. After all subtasks complete, the integration agent merges worktrees.

---

## 7. Communication Protocol

### Internal: Spring Application Events

All components communicate through typed, immutable events on Spring's `ApplicationEventPublisher`.

**Event Hierarchy:**

```
ConductorEvent (abstract)
├── AgentEvent
│   ├── AgentSpawnedEvent
│   ├── AgentStateChangedEvent
│   ├── AgentOutputEvent          // Raw output from agent
│   ├── AgentErrorEvent
│   ├── AgentCompletedEvent
│   └── AgentCrashedEvent
├── QueueEvent
│   ├── MessageClassifiedEvent
│   ├── MessageBatchedEvent
│   └── MessageDeliveredEvent
├── HumanInputEvent
│   ├── InputRequestDetectedEvent
│   ├── InputResponseReceivedEvent
│   └── InputRequestTimedOutEvent
├── TaskEvent
│   ├── TaskDecomposedEvent
│   ├── SubtaskStartedEvent
│   ├── SubtaskCompletedEvent
│   └── TaskMergeCompletedEvent
├── ProjectEvent
│   ├── ProjectRegisteredEvent
│   └── ProjectScanCompletedEvent
└── NotificationEvent
    ├── NotificationSentEvent
    └── NotificationAcknowledgedEvent
```

### External: WebSocket + REST

**WebSocket (real-time):**
- Dashboard and IDE plugin maintain persistent WebSocket connections
- Server pushes: agent state changes, notifications, human input requests, queue digests
- Client sends: human responses, agent commands, filter changes

**REST (request-response):**
- Agent CRUD operations
- Project registration
- Configuration management
- Historical queries
- Health checks

**API Prefix:** `/api/v1/conductor/`

**Key Endpoints:**

```
POST   /api/v1/conductor/agents                    # Spawn agent
GET    /api/v1/conductor/agents                    # List all agents
GET    /api/v1/conductor/agents/{id}               # Agent detail
DELETE /api/v1/conductor/agents/{id}               # Kill agent
POST   /api/v1/conductor/agents/{id}/message       # Send message to agent
GET    /api/v1/conductor/agents/{id}/output        # Agent output history

GET    /api/v1/conductor/queue                     # Current queue state
GET    /api/v1/conductor/queue/digest              # Batched digest

GET    /api/v1/conductor/input-requests            # Pending human input
POST   /api/v1/conductor/input-requests/{id}/respond  # Respond to request

POST   /api/v1/conductor/tasks                     # Submit task for decomposition
GET    /api/v1/conductor/tasks/{id}                # Task execution status

GET    /api/v1/conductor/projects                  # Registered projects
POST   /api/v1/conductor/projects                  # Register project

WS     /ws/conductor                               # WebSocket endpoint
```

---

## 8. Queue Architecture (Detailed)

### Message Flow

```
Agent stdout ──> StreamParser ──> RawMessage ──> Classifier ──> ClassifiedMessage
                                                                      │
                                                       ┌──────────────┼──────────────┐
                                                       │              │              │
                                                  ┌────v───┐   ┌─────v────┐   ┌─────v────┐
                                                  │CRITICAL│   │ BATCHER  │   │ NOISE    │
                                                  │ (push) │   │ (window) │   │ (store)  │
                                                  └────┬───┘   └─────┬────┘   └──────────┘
                                                       │             │
                                                       v             v
                                                  Notification   Digest
                                                  Router         Builder
```

### Deduplication

**Content-based dedup:**
- Hash message content (minus timestamps)
- Within a sliding 60-second window, identical hashes are collapsed
- Counter incremented: "Reading file... (x15)"

**Semantic dedup:**
- Group messages by pattern template
- "Reading src/Foo.java", "Reading src/Bar.java" -> "Reading files in src/ (2 files)"
- Implemented via regex capture groups that extract the variable part

### Mute/Unmute

Per-agent mute:
- Muted agents still run and are tracked
- Their output goes to NOISE tier regardless of actual urgency
- EXCEPT: CRITICAL (human input needed) always breaks through mute
- Mute state persists across restarts

Per-category mute:
- Mute all "file read" messages across all agents
- Mute all "test running" messages
- Category list is extensible via configuration

### Queue Persistence

Messages are persisted to the event store (PostgreSQL) with:
- Full content for audit trail
- Classification metadata
- Delivery status (pending, delivered, acknowledged, expired)
- TTL: CRITICAL messages never expire; NOISE expires after 1 hour

---

## 9. Human Input Detection (Detailed)

### Detection Pipeline

```
Agent Output ──> Pattern Matcher ──> Stall Detector ──> Confidence Scorer ──> Decision
                      │                    │                    │
                 regex match?         no activity           score > 0.7?
                 (high conf)         for 30s after          (medium conf)
                                     question?
                                     (medium conf)
```

### Confidence Scoring

```java
public class HumanInputConfidenceScorer {
    
    // Each signal contributes to a 0.0 - 1.0 confidence score
    static final double EXPLICIT_TOOL_USE = 1.0;      // SendUserMessage tool
    static final double STRONG_QUESTION_PATTERN = 0.8; // "Should I...?"
    static final double WEAK_QUESTION_PATTERN = 0.4;   // Contains "?"
    static final double ACTIVITY_STALL = 0.3;          // No tool use for 30s
    static final double OPTIONS_PRESENTED = 0.5;        // "Option A / Option B"
    static final double PROCESS_IDLE = 0.2;            // Low CPU after output
    
    // Threshold: 0.7 triggers human input request
    // Multiple signals compound: question (0.8) + stall (0.3) = 1.0 (capped)
}
```

### Preventing False Positives

- **Cooldown:** After a false positive is dismissed, increase threshold for that agent temporarily
- **Agent-specific tuning:** Some agents ask more questions by nature (architects vs. implementers)
- **User feedback loop:** "Was this actually a question?" button on each prompt. Feeds back into pattern matching.

---

## 10. Agent Lifecycle Management

### Spawn Flow

```
User Request (or Meta-Agent)
        │
        v
┌──────────────┐
│ Validate:    │
│ - project    │
│ - role       │
│ - budget     │
│ - concurrency│
└──────┬───────┘
       │
┌──────v───────┐
│ Build CLI    │
│ command args │
└──────┬───────┘
       │
┌──────v───────┐
│ Start process│
│ (virtual     │
│  thread)     │
└──────┬───────┘
       │
┌──────v───────┐
│ Register in  │
│ Agent        │
│ Registry     │
└──────┬───────┘
       │
┌──────v───────┐
│ Start output │
│ stream       │
│ readers      │
└──────────────┘
```

### Crash Recovery

**Detection:**
- Process exit code != 0
- Process handle `isAlive()` returns false
- Output stream EOF without completion event

**Recovery Strategy:**

```java
public enum RecoveryStrategy {
    RESUME,         // Use --resume to continue from where it left off
    RESTART,        // Fresh start with same prompt
    ESCALATE,       // Notify user, do not auto-recover
    ABANDON         // Mark as failed, move on
}

// Default recovery: RESUME (up to 3 attempts), then ESCALATE
// The --resume flag + --session-id make this reliable
```

**State Snapshot:**

Before recovery, save:
- Last known agent state
- Message queue position
- Human input requests in flight
- Related subtask dependencies

### Cost Tracking

```java
public record AgentCostTracker(
    UUID agentId,
    BigDecimal budgetUsd,
    BigDecimal spentUsd,           // Parsed from stream-json cost events
    int inputTokens,
    int outputTokens,
    Instant lastCostUpdate
) {
    boolean isOverBudget() {
        return spentUsd.compareTo(budgetUsd) >= 0;
    }
    
    BigDecimal remainingBudget() {
        return budgetUsd.subtract(spentUsd);
    }
}
```

---

## 11. IntelliJ Integration

### Architecture: Thin Plugin + Fat Server

The IntelliJ plugin is deliberately thin. It is a **view layer** that communicates with the Conductor server.

**Why thin plugin:**
- IntelliJ plugin development is notoriously painful (compatibility, class loader issues)
- A thin plugin means the server can evolve without plugin updates
- Multiple IDE instances connect to one Conductor server

### Plugin Components

**1. Tool Window: Agent Panel**

```
┌─────────────────────────────────────────┐
│  CONDUCTOR                    [Settings]│
├─────────────────────────────────────────┤
│  Project: medallioGenAi                 │
│  ────────────────────────────────────   │
│  ● Agent-01 (Feature Engineer) ACTIVE   │
│  ● Agent-02 (Tester)          ACTIVE    │
│  ⚠ Agent-03 (Refactorer)     BLOCKED   │
│    └─ "Should I extract to interface?"  │
│       [Respond] [View Context]          │
│  ○ Agent-04 (Reviewer)       COMPLETED  │
│                                         │
│  [+ Spawn Agent] [View All Projects]    │
├─────────────────────────────────────────┤
│  Queue: 3 pending | 12 batched | 47 ↓  │
└─────────────────────────────────────────┘
```

**2. IDE Notifications**

- Uses IntelliJ's built-in notification system (`Notifications.Bus`)
- CRITICAL: Sticky balloon notification
- HIGH: Event log entry with action buttons
- Clicking notification opens the relevant agent in the tool window

**3. Quick Actions**

- `Ctrl+Shift+C`: Open Conductor command palette
  - "Spawn agent for current file"
  - "Spawn agent for current module"
  - "View blocked agents"
  - "Mute all agents in this project"
- Right-click context menu on files/directories: "Send to Conductor Agent"

**4. Gutter Icons (stretch goal)**

- Show which files are currently being edited by agents
- Small icon in the gutter showing agent activity on that file

### Plugin-Server Communication

```kotlin
class ConductorClient(private val serverUrl: String) {
    private val webSocket: WebSocketClient  // For real-time updates
    private val httpClient: HttpClient       // For REST calls
    
    // Auto-discover server on localhost:8090 (configurable)
    // Reconnect with exponential backoff on disconnect
    // Queue commands during disconnection, replay on reconnect
}
```

### Project Registration

When a project is opened in IntelliJ:
1. Plugin detects project root (look for `.git/`, `pom.xml`, `package.json`, etc.)
2. Plugin sends `ProjectRegisteredEvent` to Conductor server
3. Server scans for `CLAUDE.md`, `.claude/` directory, existing agent configurations
4. Server builds project profile (language, framework, existing agents)
5. Project appears in Conductor dashboard

---

## 12. Persistence & Crash Recovery

### Data Model

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│   projects   │     │    agents    │     │   messages   │
├──────────────┤     ├──────────────┤     ├──────────────┤
│ id (PK)      │     │ id (PK)      │     │ id (PK)      │
│ name         │──<──│ project_id   │──<──│ agent_id     │
│ path         │     │ session_id   │     │ content      │
│ language     │     │ role         │     │ urgency      │
│ framework    │     │ state        │     │ category     │
│ registered_at│     │ system_prompt│     │ delivered    │
│ last_seen_at │     │ current_task │     │ created_at   │
│ metadata     │     │ spawned_at   │     │ expires_at   │
└──────────────┘     │ budget_usd   │     └──────────────┘
                     │ spent_usd    │
                     │ metadata     │     ┌──────────────┐
                     └──────────────┘     │ input_reqs   │
                                          ├──────────────┤
┌──────────────┐     ┌──────────────┐     │ id (PK)      │
│    tasks     │     │  subtasks    │     │ agent_id     │
├──────────────┤     ├──────────────┤     │ question     │
│ id (PK)      │──<──│ task_id      │     │ options      │
│ description  │     │ name         │     │ context      │
│ status       │     │ agent_id     │     │ urgency      │
│ decomposition│     │ depends_on   │     │ response     │
│ created_at   │     │ status       │     │ detected_at  │
│ completed_at │     │ output       │     │ responded_at │
└──────────────┘     └──────────────┘     └──────────────┘

┌──────────────┐
│  event_store │  (append-only audit log)
├──────────────┤
│ id (PK)      │
│ event_type   │
│ payload (JSON│
│ agent_id     │
│ project_id   │
│ timestamp    │
└──────────────┘
```

### Crash Recovery Protocol

**Conductor Server Crash:**

1. On restart, load all agent records from PostgreSQL
2. For each agent in ACTIVE or BLOCKED state:
   a. Check if Claude CLI process is still running (by PID stored in metadata)
   b. If running: re-attach to stdout/stderr streams
   c. If dead: attempt resume with `claude --resume <session-id>`
   d. If resume fails: mark as FAILED, notify user
3. Restore queue state from persisted messages
4. Restore pending human input requests
5. Re-establish WebSocket connections (clients auto-reconnect)

**Agent Process Crash:**

1. Output reader thread detects EOF or process exit
2. Record crash in event store with last known output
3. Apply recovery strategy (RESUME -> RESTART -> ESCALATE)
4. If part of a decomposed task, notify TaskExecutionEngine
5. Notify user via notification router

**Database Recovery:**

- PostgreSQL WAL for point-in-time recovery
- SQLite journal mode WAL for local fallback
- Event store is append-only (natural audit trail)
- Snapshots every 5 minutes for fast recovery

### Local-First Fallback

When PostgreSQL is unavailable:
- Fall back to embedded SQLite automatically
- Queue state persists to local disk
- On Postgres reconnection, sync local events to Postgres
- Never lose data during network partitions

---

## 13. Security Model

### Threat Model

| Threat | Mitigation |
|--------|------------|
| Agent escapes sandbox | `--permission-mode` controls per agent; default to restricted |
| Agent accesses wrong project files | `--add-dir` restricts file access per agent |
| Runaway cost | `--max-budget-usd` per agent + global budget tracking |
| Malicious agent output injection | All output is treated as untrusted text; no eval/exec |
| Unauthorized Conductor access | Server binds to localhost only; auth token for remote |
| Secrets in agent prompts | Secrets injected via env vars, never in prompts |

### Agent Sandboxing Levels

```java
public enum SandboxLevel {
    STRICT,      // --permission-mode default (asks for everything)
    STANDARD,    // --permission-mode acceptEdits (auto-approve edits, ask for bash)
    TRUSTED,     // --permission-mode auto (auto-approve most things)
    UNRESTRICTED // --dangerously-skip-permissions (only for isolated environments)
}
```

Default: STANDARD for spawned agents, TRUSTED for meta-agent.

### API Authentication

- **Local mode:** No auth (localhost binding)
- **Network mode:** Bearer token authentication
- **Future:** OIDC integration for team deployment

### Cost Guardrails

```java
public record CostGuardrails(
    BigDecimal perAgentMaxUsd,        // Default: $5.00
    BigDecimal perTaskMaxUsd,         // Default: $20.00
    BigDecimal dailyGlobalMaxUsd,     // Default: $100.00
    BigDecimal monthlyGlobalMaxUsd,   // Default: $2000.00
    boolean hardStop                   // true = kill agent at limit
) {}
```

---

## 14. Dashboard & UI

### Dashboard Layout

```
┌──────────────────────────────────────────────────────────────────┐
│  CONDUCTOR                              [DND] [Settings] [Tray] │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─ Projects ──────────┐  ┌─ Active Agents ──────────────────┐  │
│  │ ● medallioGenAi (4) │  │                                  │  │
│  │ ○ frontend-app  (2) │  │  [Agent cards with status,       │  │
│  │ ○ data-pipeline (1) │  │   current task, output preview,  │  │
│  │ ○ infra-config  (0) │  │   cost tracker, action buttons]  │  │
│  │                      │  │                                  │  │
│  │ [+ Register Project] │  │                                  │  │
│  └──────────────────────┘  └──────────────────────────────────┘  │
│                                                                   │
│  ┌─ Human Input Needed ──────────────────────────────────────┐   │
│  │ ⚠ Agent-03 (medallioGenAi/refactorer) - 2m ago           │   │
│  │   "Should I extract the interface or keep the abstract?"  │   │
│  │   [A: Extract] [B: Keep Abstract] [Type Response...]      │   │
│  │                                                            │   │
│  │ ⚠ Agent-07 (frontend/tester) - 5m ago                    │   │
│  │   "Tests require a running backend. Start it?"            │   │
│  │   [Yes] [No] [Type Response...]                           │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌─ Message Feed ─────────────────────────────── [Filter ▼] ─┐   │
│  │ 14:22 Agent-01 completed: "Auth endpoints implemented"    │   │
│  │ 14:21 Agent-02: Running test suite (47/120 passed)        │   │
│  │ 14:20 Agent-05: 3 files modified in src/services/         │   │
│  │ 14:19 [Batch] 4 agents: Reading project files...          │   │
│  │ ...                                                        │   │
│  └────────────────────────────────────────────────────────────┘   │
│                                                                   │
│  ┌─ Cost Tracker ────┐  ┌─ System Health ─────────────────┐     │
│  │ Today: $12.47     │  │ Server: Running | Agents: 7/200 │     │
│  │ This week: $89.20 │  │ Memory: 2.1GB  | CPU: 12%      │     │
│  │ Budget: $500/mo   │  │ DB: Connected  | WS: 3 clients │     │
│  └───────────────────┘  └─────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────┘
```

### System Tray

- Conductor icon in system tray (Windows taskbar)
- Badge count for pending human input requests
- Right-click menu: Quick actions, DND toggle, open dashboard
- Left-click: Show/hide dashboard

### Keyboard-Driven Workflow

The dashboard is optimized for keyboard navigation:
- `1-9`: Quick-respond to numbered human input requests
- `j/k`: Navigate message feed
- `a`: Spawn new agent
- `m`: Mute selected agent
- `/`: Filter messages
- `Esc`: Dismiss current notification
- `Tab`: Cycle between panels

---

## 15. Phased Delivery Plan

### Phase 0: Foundation (Week 1-2)
**Goal:** Minimal working skeleton — spawn one agent, see its output.

Deliverables:
- [ ] Gradle multi-module project structure
- [ ] Spring Boot server with virtual threads enabled
- [ ] Claude CLI process manager (spawn, attach stdout, kill)
- [ ] Agent registry (in-memory, persisted to Postgres)
- [ ] Raw output stream via WebSocket
- [ ] Minimal Electron shell with WebSocket connection
- [ ] Display raw agent output in dashboard

**Exit criteria:** Can spawn a Claude Code agent from the dashboard and see its output in real-time.

---

### Phase 1: Noise Reduction (Week 3-4)
**Goal:** Make the output stream usable at scale.

Deliverables:
- [ ] Message classification engine (pattern-based)
- [ ] 5-tier urgency system (CRITICAL through NOISE)
- [ ] Content-based dedup within sliding windows
- [ ] Message batching (30-second digest windows)
- [ ] Mute/unmute per agent
- [ ] Filtered message feed in dashboard
- [ ] Queue statistics display

**Exit criteria:** Running 10 agents simultaneously, dashboard shows a clean, prioritized feed.

---

### Phase 2: Human Input Detection (Week 5-6)
**Goal:** Never miss a blocked agent again.

Deliverables:
- [ ] Pattern-based question detection (Layer 2)
- [ ] Activity stall detection (Layer 3)
- [ ] Confidence scoring system
- [ ] Human input request queue
- [ ] Response pipeline (dashboard -> server -> agent stdin)
- [ ] Desktop notifications for CRITICAL items (Electron)
- [ ] "Human Input Needed" panel in dashboard

**Exit criteria:** Blocked agents are detected within 30 seconds, user can respond from dashboard and unblock.

---

### Phase 3: Multi-Project Support (Week 7-8)
**Goal:** Manage agents across multiple codebases.

Deliverables:
- [ ] Project registry with auto-discovery
- [ ] Per-project agent views
- [ ] Project-scoped configuration (noise rules, default agents)
- [ ] CLAUDE.md scanning and display
- [ ] Cross-project agent status overview
- [ ] Project health indicators

**Exit criteria:** 5 projects registered, agents running in 3 simultaneously, clear per-project visibility.

---

### Phase 4: IntelliJ Plugin (Week 9-11)
**Goal:** IDE-native agent management.

Deliverables:
- [ ] IntelliJ plugin skeleton (Kotlin, Platform SDK)
- [ ] Tool window with agent list
- [ ] IDE notification integration
- [ ] Quick-response to human input requests from IDE
- [ ] Context menu: "Send to Conductor Agent"
- [ ] Keyboard shortcut: Conductor command palette
- [ ] Auto-register project on IDE open

**Exit criteria:** Full agent management from within IntelliJ without switching to dashboard.

---

### Phase 5: Task Decomposition & Meta-Agent (Week 12-15)
**Goal:** An agent that can plan, spawn, and coordinate other agents.

Deliverables:
- [ ] Meta-agent system prompt and structured output schema
- [ ] Task decomposition engine
- [ ] Dependency DAG resolution
- [ ] Sub-agent spawning with context injection
- [ ] Progress tracking across subtasks
- [ ] Git worktree integration for parallel work
- [ ] Integration/review agent at task completion
- [ ] Task visualization in dashboard (DAG view)

**Exit criteria:** Submit "implement feature X" to meta-agent, watch it decompose into 5 sub-agents that execute and merge.

---

### Phase 6: Advanced Features (Week 16-20)
**Goal:** Power-user features and hardening.

Deliverables:
- [ ] Cost tracking and budget enforcement
- [ ] Agent templates and presets
- [ ] Saved workflows (repeatable agent compositions)
- [ ] Advanced noise filtering (semantic dedup, category muting)
- [ ] Agent performance analytics (time-to-complete, cost-per-task)
- [ ] Crash recovery with automatic resume
- [ ] Configuration export/import
- [ ] CLI companion tool (`conductor spawn ...`, `conductor status`)

**Exit criteria:** Production-quality system running 50+ agents daily with full observability.

---

### Phase 7: Team Features (Future)
**Goal:** Multi-user support.

Deliverables:
- [ ] Authentication (OIDC)
- [ ] Shared agent pools
- [ ] Role-based access control
- [ ] Team cost allocation
- [ ] Shared task templates
- [ ] Audit trail for compliance

---

## 16. Risk Registry

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Claude CLI stream-json format changes between versions | MEDIUM | HIGH | Pin CLI version; abstract parser behind interface; integration tests against real CLI |
| Virtual thread + Process I/O interaction bugs on Windows | MEDIUM | MEDIUM | Test early on Windows; fall back to platform threads for process I/O if needed |
| IntelliJ plugin compatibility across versions | HIGH | MEDIUM | Target latest 3 versions; thin plugin minimizes surface area |
| Agent processes accumulate and exhaust memory | MEDIUM | HIGH | Enforce max concurrent agents; periodic health checks; auto-kill idle agents |
| Human input detection false positives annoy user | HIGH | MEDIUM | Confidence threshold tuning; user feedback loop; per-agent adjustment |
| Stream-json stdin protocol for sending responses may not work reliably | MEDIUM | CRITICAL | Prototype this in Phase 0; if unreliable, fall back to `--resume` with new prompt |
| Electron app feels heavy/slow | MEDIUM | LOW | Lazy load panels; virtualize long lists; profile early |
| Git worktree conflicts during parallel sub-agent work | MEDIUM | MEDIUM | Each worktree on its own branch; integration agent handles merge conflicts |
| Cost tracking may not be accurate from stream output | LOW | MEDIUM | Cross-reference with Anthropic usage API if available |

### Critical Prototype-First Items

These must be validated before committing to the architecture:

1. **Claude CLI stdin/stdout stream-json bidirectional communication** - Can we reliably send messages to a running `--print --input-format stream-json` process and get responses?

2. **Process management on Windows** - Java `ProcessBuilder` on Windows with long-running Claude CLI processes. Test: spawn, read output, send input, detect crash, resume.

3. **Virtual thread + blocking I/O** - Confirm that `Process.getInputStream().read()` plays well with virtual threads at scale (100+ concurrent readers).

---

## 17. Open Questions

These need answers before or during Phase 0:

1. **Session resume reliability:** How reliable is `claude --resume <session-id>` in practice? Does it restore full context or just conversation history? Test with a crashed mid-task agent.

2. **Stream-JSON input protocol:** The `--input-format stream-json` flag suggests bidirectional streaming. What is the exact JSON schema for sending user messages? Is this documented anywhere, or do we need to reverse-engineer it?

3. **Agent cost reporting:** Does `--output-format stream-json` include token usage/cost data per response? If not, how do we track per-agent cost?

4. **Concurrent agent limits:** Is there a practical limit on concurrent Claude CLI processes (API rate limiting, account limits)? At 50+ agents, we may hit Anthropic's concurrent request limits.

5. **Git worktree + Claude Code:** Does `--worktree` create the worktree, or does it expect one to exist? Can multiple Claude agents share a worktree, or must each have its own?

6. **IntelliJ plugin minimum version:** What is the minimum IntelliJ version to target? This determines which Platform SDK APIs are available.

7. **Electron vs. Tauri (revisit):** If the dashboard is simple enough, Tauri would be 10x smaller. Decision point: after Phase 0, before Phase 1 UI work.

8. **MCP Server approach:** Should Conductor expose itself as an MCP server that Claude Code agents can call? This would allow agents to self-register, report status, and request coordination. Could replace some of the stdout parsing.

---

## Appendix A: Key Commands Reference

### Spawning agents programmatically

```bash
# Basic agent spawn
claude -p \
  --output-format stream-json \
  --input-format stream-json \
  --session-id "550e8400-e29b-41d4-a716-446655440000" \
  --model opus \
  --permission-mode auto \
  "Implement the user authentication module"

# Agent with custom system prompt
claude -p \
  --output-format stream-json \
  --input-format stream-json \
  --session-id "$(uuidgen)" \
  --system-prompt "You are a code reviewer. Review all changes for security issues." \
  --add-dir "/path/to/project" \
  --max-budget-usd 5.00 \
  "Review the recent changes in src/auth/"

# Agent using predefined agent config
claude -p \
  --output-format stream-json \
  --agent independent-feature-engineer \
  --session-id "$(uuidgen)" \
  "Build a caching layer for the API"

# Resume a crashed agent
claude -p \
  --output-format stream-json \
  --input-format stream-json \
  --resume "550e8400-e29b-41d4-a716-446655440000" \
  "Continue where you left off"
```

### Working directory management

```bash
# Each agent runs in a specific project directory
# Set via ProcessBuilder.directory() in Java
# Or via --add-dir for additional access
```

## Appendix B: Event Schema Examples

### AgentOutputEvent (from stream-json parsing)

```json
{
    "eventType": "AGENT_OUTPUT",
    "agentId": "550e8400-e29b-41d4-a716-446655440000",
    "projectId": "medallioGenAi",
    "timestamp": "2026-03-31T14:22:00Z",
    "payload": {
        "type": "assistant",
        "content": "I've implemented the auth endpoints. Should I also add rate limiting?",
        "toolUse": null,
        "tokenUsage": {
            "inputTokens": 1200,
            "outputTokens": 450
        }
    },
    "classification": {
        "urgency": "CRITICAL",
        "category": "HUMAN_INPUT",
        "confidence": 0.92,
        "matchedPatterns": ["QUESTION_SHOULD_I"]
    }
}
```

### HumanInputRequest (pushed to dashboard)

```json
{
    "requestId": "req-001",
    "agentId": "550e8400-e29b-41d4-a716-446655440000",
    "agentRole": "FEATURE_ENGINEER",
    "projectName": "medallioGenAi",
    "question": "I've implemented the auth endpoints. Should I also add rate limiting?",
    "suggestedOptions": [
        "Yes, add rate limiting with default 100 req/min",
        "No, rate limiting will be handled by the API gateway",
        "Yes, but make it configurable via properties"
    ],
    "context": "Agent has been implementing OAuth2 endpoints for the last 12 minutes. 4 files created, 2 tests passing.",
    "urgency": "CRITICAL",
    "detectedAt": "2026-03-31T14:22:00Z",
    "blockedFor": "PT0S"
}
```

## Appendix C: Technology Dependency Matrix

| Component | Core Dependencies |
|-----------|-------------------|
| **conductor-server** | Spring Boot 3.x, Spring WebSocket, Spring Data JPA, PostgreSQL driver, Jackson, Virtual Threads (Java 21) |
| **conductor-ui** | Electron 30+, React 19, TypeScript 5, Zustand, TailwindCSS, Recharts (for analytics) |
| **conductor-intellij-plugin** | IntelliJ Platform SDK 2024.x, Kotlin 1.9+, Ktor Client (HTTP/WS) |
| **conductor-cli** | Picocli (Java CLI framework), Jackson |
| **conductor-common** | Jackson annotations only (shared DTOs/events) |

## Appendix D: Configuration Schema

```yaml
# conductor.yml - Main configuration file
server:
  port: 8090
  bind: localhost              # localhost for single-user, 0.0.0.0 for team

agents:
  max-concurrent: 200
  default-model: opus
  default-sandbox: STANDARD
  default-budget-usd: 5.00
  idle-timeout-minutes: 30     # Kill agents idle for this long
  crash-recovery:
    max-retries: 3
    strategy: RESUME            # RESUME, RESTART, ESCALATE

queue:
  batch-window-seconds: 30
  dedup-window-seconds: 60
  noise-ttl-minutes: 60
  critical-sound: true
  
notifications:
  desktop: true
  ide: true
  dnd-schedule:                # Auto-DND during off hours
    start: "22:00"
    end: "08:00"

cost:
  per-agent-max-usd: 5.00
  daily-max-usd: 100.00
  monthly-max-usd: 2000.00
  hard-stop: true

human-input:
  detection-threshold: 0.7
  stall-timeout-seconds: 30
  false-positive-cooldown-seconds: 300

persistence:
  primary: postgresql
  fallback: sqlite
  snapshot-interval-seconds: 300
  
projects:
  auto-discover: true
  scan-patterns:
    - ".git"
    - "pom.xml"
    - "build.gradle"
    - "package.json"
    - "CLAUDE.md"
```
