# Spike 2 Results: Virtual Threads + Process I/O on Windows

**Date:** 2026-04-01  
**JDK:** OpenJDK 25.0.2  
**OS:** Windows 11 10.0, 32 cores  
**Verdict: ALL TESTS PASSED. Virtual threads are safe to use.**

---

## Test 1: Basic Concurrency (10 processes)

| Metric | Value |
|--------|-------|
| Processes | 10 (ping -n 5) |
| Completed | 10/10 |
| Errors | 0 |
| Wall-clock | 4.3s (sequential would be ~40s) |

**Conclusion:** Virtual threads correctly parallelize Process I/O. 10x speedup confirms real concurrency.

## Test 2: Scale (50 processes)

| Metric | Value |
|--------|-------|
| Processes | 50 (ping -n 3) |
| Completed | 50/50 |
| Errors | 0 |
| Wall-clock | 2.4s (sequential would be ~100s) |
| Memory before | 1.5 MB |
| Memory after | 9.9 MB |
| Memory delta | 8.5 MB |

**Conclusion:** 50 concurrent processes with virtual threads uses only ~10MB. Memory is negligible. We can comfortably run 50+ agents.

## Test 3: Pinning Detection

| Metric | Value |
|--------|-------|
| JVM flag | `-Djdk.tracePinnedThreads=short` |
| Pinning warnings on stderr | **None** |
| With synchronized blocks | Completed 10/10 |
| Without synchronized blocks | Completed 10/10 |

**Conclusion:** `Process.getInputStream().read()` does NOT pin virtual threads to carrier threads. This is the critical finding — it means we can use virtual threads for all agent I/O without worrying about carrier thread exhaustion.

## Test 4: Process Churn (spawn/kill 100 processes)

| Metric | Value |
|--------|-------|
| Iterations | 5 x 20 processes |
| Total spawned | 100 |
| Total confirmed killed | 100 |
| Errors | 0 |
| Memory delta | 74.4 KB |
| Leak indicator | No leak detected |

**Conclusion:** Rapid spawn/kill cycles don't leak memory or leave zombie processes on Windows. Safe for dynamic agent lifecycle management.

---

## Architecture Recommendation

**Use virtual threads for all agent I/O.** No need for the platform thread pool fallback mentioned in the spike plan.

Key design implications:
- Each Claude agent gets its own virtual thread for stdout reading — no thread pool sizing needed
- Can scale to hundreds of agents without carrier thread pressure
- Memory overhead per agent is ~170KB (8.5MB / 50 agents), trivial
- Process cleanup on Windows is reliable (`Process.destroyForcibly()` works)
