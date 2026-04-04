# brain/ — Conductor Brain (Leader Agent Layer)

## Responsibility
Leader agent that acts as a product owner between the human and child agents. Reads project context, learns user behavior patterns, auto-responds to routine agent questions, and escalates important decisions to the human. Never writes code — only orchestrates.

## Status: Phase 4A + 4C + Knowledge Extractor Complete + Feedback Loop + Bootstrap

## Contracts

### Consumes
- `HumanInputNeededEvent` (from humaninput/) — primary trigger for auto-response decisions
- `AgentStreamEvent` (from process/) — monitors all agent output for awareness
- `QueuedMessageEvent` (from queue/) — situational awareness of classified messages
- `HumanInputResponder.respond()` (from humaninput/) — pipes Brain responses to agents
- `HumanInputQueue.resolve()` (from humaninput/) — resolves handled requests
- `ClaudeProcessManager.spawnAgent()` (from process/) — spawns agents for task decomposition (Phase 4D)
- `ClaudeProcessManager.sendMessage()` (from process/) — shares context between agents (Phase 4E)
- `AgentRegistry` (from agent/) — agent state lookups
- `ProjectRegistry` (from project/) — project context lookups

### Provides
- `BrainDecisionEngine` @EventListener — auto-responds to agent questions or escalates
- `BehaviorLogger` — logging hooks for controllers to record user interactions
- `BehaviorModelBuilder.build()` → BehaviorModel — aggregated user behavior patterns
- `ContextIngestionService.buildIndex()` → ContextIndex — indexed project context
- `BrainApiClient.evaluate()` → Optional<BrainDecision> — Claude API decision (Phase 4B)
- `TaskDecomposer.decompose(prompt, projectPath, context)` → DecompositionPlan — prompt to subtask DAG
- `TaskExecutor.execute(plan)` → DecompositionPlan — runs plan in waves, spawns agents
- `TaskExecutor.getPlan(planId)` → Optional<DecompositionPlan> — plan lookup
- `TaskExecutor.getActivePlans()` → Collection<DecompositionPlan> — all tracked plans
- `TaskExecutor.cancel(planId)` → Optional<DecompositionPlan> — cancel running plan
- `InterAgentBridge.shareContext(plan, completedTask)` — shares output between agents
- `ProjectKnowledgeExtractor.analyze(path, id, name)` → ProjectKnowledge — deep project analysis via Claude API
- `ProjectKnowledgeStore.save(knowledge)` — persist extracted knowledge as JSON
- `ProjectKnowledgeStore.load(projectId)` → Optional<ProjectKnowledge> — load by project ID
- `ProjectKnowledgeStore.loadAll()` → List<ProjectKnowledge> — all stored knowledge
- `ProjectKnowledgeStore.renderForPrompt(maxChars)` → String — knowledge formatted for prompts
- `KnowledgeAwareContextRenderer.renderForPrompt(index, path, maxChars)` — context + knowledge combined
- `BrainFeedbackStore.append(feedback)` → void — record user feedback on Brain decisions
- `BrainFeedbackStore.readAll()` → List<BrainFeedback> — all feedback entries
- `BrainFeedbackStore.readRecent(count)` → List<BrainFeedback> — last N entries
- `BehaviorModelBuilder.build()` — includes bootstrap defaults (empty log) and feedback integration

### Events Published
- `BrainResponseEvent(requestId, agentId, response, confidence, reasoning)` — Brain auto-responded
- `BrainEscalationEvent(requestId, agentId, reason, recommendation, confidence)` — Brain deferred to human
- `TaskProgressEvent(planId, completed, total, currentPhase)` — subtask state changed in a plan

## Sub-packages
| Package | Purpose |
|---------|---------|
| `behavior/` | User behavior logging, model building, pattern matching |
| `context/` | CLAUDE.md scanning, context indexing, prompt rendering |
| `decision/` | Core decision engine, event listeners, response/escalation |
| `api/` | Claude API client wrapper (Phase 4B stub) |
| `task/` | Task decomposition, DAG execution, inter-agent context sharing (Phase 4C) |

## Files
| File | Purpose |
|------|---------|
| `BrainProperties.java` | @ConfigurationProperties — enabled, apiKey, model, thresholds |
| `BrainConfig.java` | @Configuration — enables BrainProperties binding |
| `behavior/BehaviorEvent.java` | Record — single logged user interaction |
| `behavior/BehaviorLog.java` | @Service — append-only JSONL file manager |
| `behavior/BehaviorLogger.java` | @Service — high-level logging API for controllers |
| `behavior/BehaviorModel.java` | Record — aggregated behavior patterns |
| `behavior/BehaviorModelBuilder.java` | @Service — builds model from log, caches 60s, bootstrap defaults, feedback integration |
| `behavior/BehaviorMatch.java` | Record — pattern match result with confidence |
| `behavior/BrainFeedback.java` | Record — user feedback on a Brain decision (GOOD/BAD/NEUTRAL) |
| `behavior/BrainFeedbackStore.java` | @Service — append-only JSONL at ~/.conductor/brain-feedback.jsonl |
| `context/ContextIndex.java` | Record — full context index |
| `context/ProjectContext.java` | Record — per-project context |
| `context/DomainClaudeMd.java` | Record — single CLAUDE.md content |
| `context/GlobalContext.java` | Record — global ~/.claude context |
| `context/ClaudeMdScanner.java` | @Service — recursive CLAUDE.md discovery |
| `context/ContextIngestionService.java` | @Service — builds and maintains context index |
| `context/ProjectKnowledge.java` | Record — extracted project knowledge (tech stack, patterns, key files) |
| `context/ProjectKnowledgeStore.java` | @Service — persists knowledge as JSON in ~/.conductor/project-knowledge/ |
| `context/ProjectKnowledgeExtractor.java` | @Service — analyzes projects via Claude API to extract patterns |
| `context/KnowledgeAwareContextRenderer.java` | @Service — wraps ContextIngestionService with cross-project knowledge |
| `decision/BrainDecision.java` | Record — decision result (respond/escalate) |
| `decision/BrainDecisionEngine.java` | @Service — core event listener + decision logic |
| `decision/BrainResponseEvent.java` | Spring event — Brain auto-responded |
| `decision/BrainEscalationEvent.java` | Spring event — Brain escalated to human |
| `api/BrainApiClient.java` | @Service — Claude API wrapper (Phase 4B stub) |
| `task/SubtaskStatus.java` | Enum — PENDING, RUNNING, COMPLETED, FAILED, CANCELLED lifecycle |
| `task/Subtask.java` | Record — single unit of work with dependencies and context sharing |
| `task/DecompositionPlan.java` | Record — full plan: subtasks, status, completion tracking |
| `task/TaskProgressEvent.java` | Spring event — published on subtask state changes |
| `task/DependencyResolver.java` | Utility — Kahn's algorithm topological sort into execution waves |
| `task/TaskDecomposer.java` | @Service — prompt → DecompositionPlan (template-based for Phase 4C) |
| `task/TaskExecutor.java` | @Service — executes plans in waves, spawns agents, tracks progress |
| `task/InterAgentBridge.java` | @Service — shares completed subtask context with running agents |

## Test Files
| File | Coverage |
|------|----------|
| `BehaviorLogTest.java` | 11 tests: append/read roundtrip, ordering, readRecent, size, null safety, metadata |
| `BehaviorLoggerTest.java` | 8 tests: all 6 event types logged correctly, metadata preserved |
| `BehaviorModelBuilderTest.java` | 17 tests: empty model, approval rate, patterns, auto-approve, matching, word count |
| `ClaudeMdScannerTest.java` | 15 tests: find root/nested files, skip dirs, domain extraction, path handling |
| `ContextIngestionServiceTest.java` | 11 tests: project scanning, index building, prompt rendering, edge cases |
| `BrainDecisionEngineTest.java` | 5 tests: disabled brain, escalation, auto-response, low confidence |
| `SubtaskStatusTest.java` | 5 tests: terminal state checks for all enum values |
| `SubtaskTest.java` | 6 tests: defaults, with* methods, immutability, field preservation |
| `DecompositionPlanTest.java` | 10 tests: defaults, withSubtask, completedCount, failedCount, isComplete |
| `DependencyResolverTest.java` | 14 tests: waves, cycles, diamonds, linear chains, unknown deps |
| `TaskDecomposerTest.java` | 20 tests: template decomposition, role assignment, DAG validity, error cases |
| `TaskProgressEventTest.java` | 3 tests: timestamp defaults, field access |
| `ProjectKnowledgeStoreTest.java` | 12 tests: save/load roundtrip, multi-project, delete, render, maxChars, overwrite |
| `ProjectKnowledgeExtractorTest.java` | 20 tests: file discovery, build file reading, source samples, fallback, parsing |
| `BrainFeedbackStoreTest.java` | 11 tests: append/readAll roundtrip, readRecent, size, null safety, defaults |
| `BehaviorModelBuilderBootstrapTest.java` | 10 tests: bootstrap model, auto-approve/escalate patterns, non-empty log, feedback integration |

## Key Design
- Brain listens with `@Order(HIGHEST_PRECEDENCE)` to handle events before notification routing
- If Brain handles a request, it resolves it from HumanInputQueue — notification never sees it
- If Brain doesn't handle it, event propagates to human as normal (zero impact on existing flow)
- Behavior model is cached for 60s to avoid rebuilding on every event
- BehaviorLog is append-only JSONL, human-readable, capped at 10K entries
- Context is rendered with token budget — target project full, others summarized
- Brain is opt-in: `conductor.brain.enabled=false` by default

## Gotchas
- BrainProperties uses @ConfigurationProperties — needs @EnableConfigurationProperties or @ConfigurationPropertiesScan
- BehaviorLog file I/O must be synchronized (concurrent event listeners)
- ClaudeMdScanner must skip .git/, node_modules/, target/, dist/
- ContextIngestionService reads ~/.claude/ paths — these are Windows paths on this system
- BrainApiClient is @ConditionalOnProperty — won't instantiate without API key
- ProjectKnowledgeExtractor gracefully falls back to build-file-only detection when no API key is set
- ProjectKnowledgeStore writes to ~/.conductor/project-knowledge/ — creates directory if needed
- KnowledgeAwareContextRenderer allocates 75% budget to base context, 25% to knowledge
- BehaviorModelBuilder handles bootstrap (empty log) and feedback integration directly — no subclass needed
- Bootstrap model is returned when behavior log is empty (first-run scenario)
- BrainFeedbackStore writes to ~/.conductor/brain-feedback.jsonl — creates directory if needed
- BAD feedback adds to alwaysEscalate, GOOD feedback adds to autoApprove, overrides are applied in order
