# Conductor - Phase 0 Spike Plan

**Purpose:** Validate the three critical technical unknowns before committing to the full architecture.
**Duration:** 2-3 days of focused work.
**Output:** Working prototypes that prove or disprove key assumptions.

---

## Spike 1: Claude CLI Bidirectional Streaming

**Question:** Can we reliably spawn a Claude CLI process with `--print --output-format stream-json --input-format stream-json`, read its output, and send follow-up messages via stdin?

**What to build:**

```
ConductorSpikeApp (Java 21, plain main class, no Spring)
├── ClaudeProcessSpawner.java     # Spawn claude CLI as child process
├── StreamJsonParser.java         # Parse stdout line-by-line as JSON
├── StdinMessageSender.java       # Send JSON messages to stdin
└── SpikeMain.java                # Interactive test harness
```

**Test scenarios:**

1. Spawn agent, read output, verify JSON structure
2. Send a follow-up message via stdin after agent produces output
3. Verify agent responds to the follow-up
4. Kill the process, attempt resume with `--resume`
5. Spawn 10 agents simultaneously, verify all streams are readable
6. Spawn 50 agents, measure memory usage and latency

**Success criteria:**
- Bidirectional communication works on Windows (Git Bash shell)
- Resume works after graceful and forceful kill
- 50 concurrent processes remain stable for 5 minutes
- Memory usage stays under 4GB for 50 agents

**Failure fallback:**
- If stdin doesn't work: use `--resume` with a new prompt for each "response"
- If resume doesn't restore context: use session files + explicit context injection
- If 50 concurrent processes are unstable: implement agent pooling (queue tasks, run N at a time)

---

## Spike 2: Virtual Threads + Process I/O on Windows

**Question:** Do Java 21 virtual threads work correctly with `Process.getInputStream().read()` blocking calls on Windows?

**What to build:**

```
VirtualThreadProcessSpike.java   # Single file
```

**Test scenarios:**

1. Spawn 200 virtual threads, each reading from a child process stdout
2. Verify no thread pinning (virtual threads should not pin to carrier threads on blocking I/O)
3. Measure throughput: messages processed per second across all streams
4. Test with `jcmd` to verify virtual thread count and carrier thread usage
5. Stress test: spawn and kill processes rapidly for 10 minutes

**Success criteria:**
- 200 concurrent virtual threads each reading from a process, no deadlocks
- Carrier thread count stays at JVM default (not 200+)
- No memory leaks after process churn

**Failure fallback:**
- If virtual threads pin on Windows process I/O: use platform threads with a bounded pool (like medallioGenAi's agentExecutor pattern)
- If Process I/O is fundamentally unreliable: use file-based IPC (agent writes to temp file, Conductor polls)

---

## Spike 3: Stream-JSON Output Format Discovery

**Question:** What exactly does `--output-format stream-json` produce? What is the schema? Does it include cost/token data?

**What to build:**

```
StreamJsonCapture.java   # Capture and log every line from stream-json output
```

**Test scenarios:**

1. Run `claude -p --output-format stream-json "Say hello"` and capture every line
2. Run a multi-turn conversation and capture the full stream
3. Run an agent that uses tools (Bash, Read, Edit) and capture tool events
4. Check for token usage / cost data in the stream
5. Check for `SendUserMessage` tool events
6. Run `claude -p --output-format stream-json --include-partial-messages "Write a long essay"` and capture partial chunks

**Capture format:**
Save raw output to JSON files for analysis. Document every event type seen.

**Deliverable:**
A document (`STREAM_JSON_SCHEMA.md`) that maps every observed event type, its fields, and when it appears.

---

## Spike Execution Order

```
Day 1: Spike 3 (discovery — informs the other two)
Day 1: Spike 2 (can run in parallel with Spike 3)
Day 2: Spike 1 (depends on Spike 3 findings)
Day 3: Write up findings, update ARCHITECTURE.md with corrections
```

## What Changes If Spikes Fail

| Spike | If it fails | Architecture impact |
|-------|-------------|-------------------|
| Bidirectional streaming | Fall back to resume-based communication | Simpler but higher latency for human responses. Agent must be restarted for each response. |
| Virtual threads + Process I/O | Use platform thread pool | Need to tune pool size. Add agent queueing if pool exhausted. |
| Stream-JSON missing cost data | Poll Anthropic usage API separately | Add API integration for cost tracking. Minor addition. |

None of these failures are architecture-breaking. The core design works with degraded communication; it just gets more expensive or slower.
