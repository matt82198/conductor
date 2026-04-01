# Conductor - Project Structure Specification

**Purpose:** Exact file and directory layout for the build phase. An agent given this document should be able to `mkdir -p` every directory and know what goes where.

---

## Repository Root

```
conductor/
├── .github/
│   └── workflows/
│       ├── build.yml               # CI: compile + test all modules
│       └── release.yml             # CD: build Electron app + plugin
├── .claude/
│   ├── settings.local.json         # Claude Code settings for this repo
│   └── agents/                     # Agent definitions for Conductor dev
│       ├── backend-engineer.md
│       ├── frontend-engineer.md
│       └── plugin-engineer.md
├── conductor-server/               # Spring Boot backend (Java 21)
├── conductor-ui/                   # Electron + React dashboard
├── conductor-intellij-plugin/      # IntelliJ Platform plugin (Kotlin)
├── conductor-cli/                  # Optional CLI companion
├── conductor-common/               # Shared types library (Java)
├── spikes/                         # Phase 0 spike prototypes
├── docs/                           # Design docs, ADRs, session logs
│   ├── adr/                        # Architecture Decision Records
│   ├── ARCHITECTURE.md             # (moved from root after Phase 0)
│   └── session-log.md
├── CLAUDE.md                       # Root project instructions
├── README.md
├── settings.gradle.kts             # Gradle multi-module config
├── gradle.properties
├── gradlew / gradlew.bat
└── .gitignore
```

---

## conductor-server (Spring Boot 3.x, Java 21)

```
conductor-server/
├── build.gradle.kts
└── src/
    ├── main/
    │   ├── java/dev/conductor/server/
    │   │   ├── ConductorServerApplication.java      # @SpringBootApplication entry point
    │   │   │
    │   │   ├── agent/                                # Agent domain
    │   │   │   ├── AgentRecord.java                  # Record: agent identity + state
    │   │   │   ├── AgentState.java                   # Enum: LAUNCHING, ACTIVE, BLOCKED, COMPLETED, FAILED
    │   │   │   ├── AgentRole.java                    # Enum: FEATURE_ENGINEER, TESTER, REFACTORER, etc.
    │   │   │   ├── AgentRegistry.java                # Service: in-memory registry + persistence
    │   │   │   ├── AgentLifecycleManager.java        # Service: spawn, monitor, kill, recover
    │   │   │   └── AgentCostTracker.java             # Service: per-agent cost accounting
    │   │   │
    │   │   ├── process/                              # Claude CLI process management
    │   │   │   ├── ClaudeProcessManager.java         # Service: ProcessBuilder wrapper
    │   │   │   ├── ManagedProcess.java               # Record: process handle + streams
    │   │   │   ├── StreamJsonParser.java             # Utility: parse stream-json output
    │   │   │   ├── ProcessOutputRouter.java          # Routes parsed output to event bus
    │   │   │   └── StdinMessageSender.java           # Utility: send messages to agent stdin
    │   │   │
    │   │   ├── queue/                                # Message queue engine
    │   │   │   ├── MessageClassifier.java            # Service: urgency + category classification
    │   │   │   ├── MessageDeduplicator.java          # Service: content-hash dedup
    │   │   │   ├── MessageBatcher.java               # Service: time-window batching
    │   │   │   ├── NoiseFilter.java                  # Service: configurable noise suppression
    │   │   │   ├── QueueManager.java                 # Service: orchestrates the queue pipeline
    │   │   │   └── MuteRegistry.java                 # Service: per-agent and per-category mutes
    │   │   │
    │   │   ├── notification/                         # Notification routing
    │   │   │   ├── NotificationRouter.java           # Service: routes to channels by urgency
    │   │   │   ├── NotificationChannel.java          # Interface: delivery channel abstraction
    │   │   │   ├── WebSocketChannel.java             # Impl: push via WebSocket
    │   │   │   ├── DesktopChannel.java               # Impl: Electron desktop notifications
    │   │   │   └── DndManager.java                   # Service: Do Not Disturb state
    │   │   │
    │   │   ├── humaninput/                           # Human input detection
    │   │   │   ├── HumanInputDetector.java           # Service: multi-layer detection pipeline
    │   │   │   ├── PatternMatcher.java               # Utility: regex question detection
    │   │   │   ├── StallDetector.java                # Service: activity stall monitoring
    │   │   │   ├── ConfidenceScorer.java             # Utility: signal -> confidence score
    │   │   │   ├── HumanInputRequest.java            # Record: queued input request
    │   │   │   ├── HumanInputQueue.java              # Service: priority queue of requests
    │   │   │   └── HumanInputResponder.java          # Service: pipe response to agent
    │   │   │
    │   │   ├── decomposer/                           # Task decomposition (meta-agent)
    │   │   │   ├── TaskDecomposer.java               # Service: invokes meta-agent for planning
    │   │   │   ├── DecompositionPlan.java            # Record: parsed plan from meta-agent
    │   │   │   ├── TaskExecutionEngine.java          # Service: DAG execution, wave scheduling
    │   │   │   ├── DependencyResolver.java           # Utility: topological sort
    │   │   │   ├── SubtaskCoordinator.java           # Service: context passing between subtasks
    │   │   │   └── WorktreeManager.java              # Service: git worktree creation/cleanup
    │   │   │
    │   │   ├── project/                              # Multi-project management
    │   │   │   ├── ProjectRecord.java                # Record: project identity + metadata
    │   │   │   ├── ProjectRegistry.java              # Service: registered projects
    │   │   │   ├── ProjectScanner.java               # Service: detect language, framework, CLAUDE.md
    │   │   │   └── ClaudeMdParser.java               # Utility: parse CLAUDE.md files
    │   │   │
    │   │   ├── event/                                # Event system
    │   │   │   ├── ConductorEvent.java               # Abstract base event
    │   │   │   ├── AgentSpawnedEvent.java
    │   │   │   ├── AgentStateChangedEvent.java
    │   │   │   ├── AgentOutputEvent.java
    │   │   │   ├── AgentErrorEvent.java
    │   │   │   ├── AgentCompletedEvent.java
    │   │   │   ├── AgentCrashedEvent.java
    │   │   │   ├── MessageClassifiedEvent.java
    │   │   │   ├── InputRequestDetectedEvent.java
    │   │   │   ├── InputResponseReceivedEvent.java
    │   │   │   ├── TaskDecomposedEvent.java
    │   │   │   ├── SubtaskCompletedEvent.java
    │   │   │   └── EventStore.java                   # Service: append-only event persistence
    │   │   │
    │   │   ├── persistence/                          # JPA + repositories
    │   │   │   ├── entity/
    │   │   │   │   ├── AgentEntity.java
    │   │   │   │   ├── ProjectEntity.java
    │   │   │   │   ├── MessageEntity.java
    │   │   │   │   ├── InputRequestEntity.java
    │   │   │   │   ├── TaskEntity.java
    │   │   │   │   ├── SubtaskEntity.java
    │   │   │   │   └── EventEntity.java
    │   │   │   └── repository/
    │   │   │       ├── AgentRepository.java
    │   │   │       ├── ProjectRepository.java
    │   │   │       ├── MessageRepository.java
    │   │   │       ├── InputRequestRepository.java
    │   │   │       ├── TaskRepository.java
    │   │   │       └── EventRepository.java
    │   │   │
    │   │   ├── api/                                  # REST + WebSocket controllers
    │   │   │   ├── AgentController.java              # CRUD for agents
    │   │   │   ├── QueueController.java              # Queue state + digest
    │   │   │   ├── InputRequestController.java       # Human input CRUD + respond
    │   │   │   ├── TaskController.java               # Task submission + status
    │   │   │   ├── ProjectController.java            # Project registration
    │   │   │   ├── HealthController.java             # Server health
    │   │   │   └── ConductorWebSocketHandler.java    # WebSocket endpoint
    │   │   │
    │   │   ├── config/                               # Spring configuration
    │   │   │   ├── AsyncConfig.java                  # Virtual thread executor beans
    │   │   │   ├── WebSocketConfig.java              # WebSocket endpoint registration
    │   │   │   ├── SecurityConfig.java               # localhost binding, token auth
    │   │   │   ├── JacksonConfig.java                # JSON serialization config
    │   │   │   ├── PersistenceConfig.java            # JPA + datasource config
    │   │   │   └── ConductorProperties.java          # @ConfigurationProperties for conductor.yml
    │   │   │
    │   │   └── model/                                # Shared enums and value types
    │   │       ├── MessageUrgency.java               # CRITICAL, HIGH, NORMAL, LOW, NOISE
    │   │       ├── MessageCategory.java              # FILE_OP, TEST, BUILD, QUESTION, ERROR, etc.
    │   │       ├── SandboxLevel.java                 # STRICT, STANDARD, TRUSTED, UNRESTRICTED
    │   │       ├── RecoveryStrategy.java             # RESUME, RESTART, ESCALATE, ABANDON
    │   │       └── CostGuardrails.java               # Record: budget limits
    │   │
    │   └── resources/
    │       ├── application.yml                       # Spring Boot config
    │       ├── application-dev.yml                   # Dev profile
    │       ├── application-prod.yml                  # Prod profile
    │       ├── db/migration/                         # Flyway migrations
    │       │   ├── V1__create_projects.sql
    │       │   ├── V2__create_agents.sql
    │       │   ├── V3__create_messages.sql
    │       │   ├── V4__create_input_requests.sql
    │       │   ├── V5__create_tasks.sql
    │       │   └── V6__create_event_store.sql
    │       └── conductor-defaults.yml                # Default configuration values
    │
    └── test/
        └── java/dev/conductor/server/
            ├── agent/
            │   ├── AgentRegistryTest.java
            │   └── AgentLifecycleManagerTest.java
            ├── process/
            │   ├── StreamJsonParserTest.java
            │   └── ClaudeProcessManagerIntegrationTest.java
            ├── queue/
            │   ├── MessageClassifierTest.java
            │   ├── MessageDeduplicatorTest.java
            │   └── MessageBatcherTest.java
            ├── humaninput/
            │   ├── PatternMatcherTest.java
            │   ├── StallDetectorTest.java
            │   └── ConfidencesScorerTest.java
            ├── decomposer/
            │   ├── DependencyResolverTest.java
            │   └── TaskExecutionEngineTest.java
            └── api/
                ├── AgentControllerTest.java
                └── ConductorWebSocketHandlerTest.java
```

---

## conductor-ui (Electron + React + TypeScript)

```
conductor-ui/
├── package.json
├── tsconfig.json
├── vite.config.ts                    # Vite for React bundling
├── tailwind.config.ts
├── electron/
│   ├── main.ts                       # Electron main process
│   ├── preload.ts                    # Context bridge for IPC
│   ├── tray.ts                       # System tray management
│   └── notifications.ts             # Native notification bridge
├── src/
│   ├── main.tsx                      # React entry point
│   ├── App.tsx                       # Root layout
│   ├── components/
│   │   ├── layout/
│   │   │   ├── Sidebar.tsx           # Project list
│   │   │   ├── Header.tsx            # Top bar with DND, settings
│   │   │   └── StatusBar.tsx         # Bottom: cost, health, connections
│   │   ├── agents/
│   │   │   ├── AgentCard.tsx         # Individual agent display
│   │   │   ├── AgentList.tsx         # Agent grid/list view
│   │   │   ├── AgentDetail.tsx       # Full agent view with output
│   │   │   └── SpawnAgentDialog.tsx  # Agent creation form
│   │   ├── queue/
│   │   │   ├── MessageFeed.tsx       # Filtered message stream
│   │   │   ├── MessageItem.tsx       # Single message display
│   │   │   ├── FilterBar.tsx         # Urgency/category filters
│   │   │   └── DigestView.tsx        # Batched digest display
│   │   ├── input/
│   │   │   ├── InputRequestPanel.tsx # Human input queue
│   │   │   ├── InputRequestCard.tsx  # Single request with response UI
│   │   │   └── QuickResponseBar.tsx  # Keyboard-driven response
│   │   ├── tasks/
│   │   │   ├── TaskSubmitForm.tsx    # Submit task for decomposition
│   │   │   ├── TaskDagView.tsx       # Visual DAG of subtasks
│   │   │   └── SubtaskCard.tsx       # Individual subtask status
│   │   ├── projects/
│   │   │   ├── ProjectList.tsx       # Registered projects
│   │   │   └── ProjectDetail.tsx     # Project agents + config
│   │   ├── analytics/
│   │   │   ├── CostTracker.tsx       # Cost charts
│   │   │   └── AgentPerformance.tsx  # Completion time, success rate
│   │   └── common/
│   │       ├── Badge.tsx
│   │       ├── Button.tsx
│   │       ├── Dialog.tsx
│   │       └── Tooltip.tsx
│   ├── hooks/
│   │   ├── useWebSocket.ts          # WebSocket connection management
│   │   ├── useAgents.ts             # Agent state subscription
│   │   ├── useQueue.ts              # Message queue subscription
│   │   ├── useInputRequests.ts      # Human input subscription
│   │   └── useKeyboardShortcuts.ts  # Global keyboard handling
│   ├── stores/
│   │   ├── agentStore.ts            # Zustand: agent state
│   │   ├── queueStore.ts            # Zustand: message queue
│   │   ├── inputStore.ts            # Zustand: human input requests
│   │   ├── projectStore.ts          # Zustand: projects
│   │   └── settingsStore.ts         # Zustand: user preferences
│   ├── services/
│   │   ├── conductorApi.ts          # REST API client
│   │   └── websocketService.ts      # WebSocket client
│   ├── types/
│   │   ├── agent.ts                 # Agent type definitions
│   │   ├── message.ts               # Message type definitions
│   │   ├── task.ts                  # Task type definitions
│   │   └── events.ts                # WebSocket event types
│   └── styles/
│       └── globals.css              # Tailwind base + custom styles
└── electron-builder.yml             # Electron packaging config
```

---

## conductor-intellij-plugin (Kotlin)

```
conductor-intellij-plugin/
├── build.gradle.kts                  # IntelliJ Platform Gradle Plugin
├── src/main/
│   ├── kotlin/dev/conductor/intellij/
│   │   ├── ConductorPlugin.kt       # Plugin lifecycle
│   │   ├── toolwindow/
│   │   │   ├── ConductorToolWindowFactory.kt
│   │   │   ├── AgentListPanel.kt     # Agent list in tool window
│   │   │   └── InputRequestPanel.kt  # Inline response panel
│   │   ├── actions/
│   │   │   ├── SpawnAgentAction.kt   # Spawn agent for current context
│   │   │   ├── RespondToInputAction.kt
│   │   │   ├── MuteAgentAction.kt
│   │   │   └── OpenDashboardAction.kt
│   │   ├── notifications/
│   │   │   └── ConductorNotifier.kt  # IDE notification integration
│   │   ├── client/
│   │   │   ├── ConductorHttpClient.kt   # REST client
│   │   │   └── ConductorWebSocketClient.kt  # WebSocket client
│   │   ├── settings/
│   │   │   ├── ConductorSettings.kt     # Persistent settings
│   │   │   └── ConductorConfigurable.kt # Settings UI
│   │   └── startup/
│   │       └── ProjectRegistrar.kt   # Register project on IDE open
│   └── resources/
│       ├── META-INF/
│       │   └── plugin.xml            # Plugin descriptor
│       └── icons/
│           ├── conductor.svg
│           ├── agent-active.svg
│           ├── agent-blocked.svg
│           └── agent-completed.svg
└── src/test/
    └── kotlin/dev/conductor/intellij/
        └── client/
            └── ConductorHttpClientTest.kt
```

---

## conductor-common (Shared Types)

```
conductor-common/
├── build.gradle.kts
└── src/main/java/dev/conductor/common/
    ├── dto/
    │   ├── AgentDto.java
    │   ├── MessageDto.java
    │   ├── InputRequestDto.java
    │   ├── TaskDto.java
    │   ├── ProjectDto.java
    │   └── HealthDto.java
    ├── event/
    │   ├── WebSocketEvent.java       # Events sent over WebSocket
    │   └── WebSocketEventType.java   # Enum of WS event types
    └── model/
        ├── MessageUrgency.java       # Shared enum
        ├── AgentState.java           # Shared enum
        └── AgentRole.java            # Shared enum
```

---

## spikes/ (Phase 0 Prototypes)

```
spikes/
├── stream-json-capture/
│   ├── StreamJsonCapture.java
│   └── captured-output/             # Raw JSON files from Claude CLI
├── bidirectional-streaming/
│   ├── ClaudeProcessSpawner.java
│   ├── StreamJsonParser.java
│   ├── StdinMessageSender.java
│   └── SpikeMain.java
├── virtual-thread-process-io/
│   └── VirtualThreadProcessSpike.java
└── README.md                        # Spike results and findings
```

---

## Build Configuration

### settings.gradle.kts (root)

```kotlin
rootProject.name = "conductor"

include(
    "conductor-common",
    "conductor-server",
    "conductor-cli"
)

// conductor-ui and conductor-intellij-plugin have their own build systems
// (npm and IntelliJ Platform Gradle Plugin respectively)
```

### Key Gradle Properties

```properties
# gradle.properties
javaVersion=21
springBootVersion=3.4.x
kotlinVersion=1.9.x
group=dev.conductor
version=0.1.0-SNAPSHOT
```
