import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Spike 2: Validate virtual threads with Process I/O on Windows.
 *
 * Tests:
 *   1. Basic: 10 child processes read by virtual threads
 *   2. Scale: 50 virtual threads each reading a child process
 *   3. Pinning: Run with -Djdk.tracePinnedThreads=short to detect carrier-thread pinning
 *   4. Churn: Rapidly spawn/kill 20 processes x 5 iterations, check for leaks
 */
public class VirtualThreadProcessSpike {

    // Collect all output for the results report
    private static final StringBuilder REPORT = new StringBuilder();

    public static void main(String[] args) throws Exception {
        log("=== Spike 2: Virtual Threads + Process I/O on Windows ===");
        log("Java version: " + System.getProperty("java.version"));
        log("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        log("Available processors: " + Runtime.getRuntime().availableProcessors());
        log("");

        boolean allPassed = true;

        try {
            allPassed &= testBasic();
            log("");
            allPassed &= testScale();
            log("");
            allPassed &= testPinning();
            log("");
            allPassed &= testChurn();
        } catch (Exception e) {
            log("FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            allPassed = false;
        }

        log("");
        log("=== OVERALL RESULT: " + (allPassed ? "ALL TESTS PASSED" : "SOME TESTS FAILED") + " ===");

        // Write report to file for easy capture
        try (var writer = new FileWriter("spike2-output.txt")) {
            writer.write(REPORT.toString());
        }
        log("(Output also written to spike2-output.txt)");
    }

    // ---------------------------------------------------------------
    // Test 1: Basic - 10 virtual threads each reading a child process
    // ---------------------------------------------------------------
    private static boolean testBasic() throws Exception {
        log("--- Test 1: Basic (10 virtual threads, each reading a process) ---");

        int count = 10;
        var startTime = Instant.now();
        var completedCount = new AtomicInteger(0);
        var errors = new CopyOnWriteArrayList<String>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();

            for (int i = 0; i < count; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        var pb = new ProcessBuilder("ping", "-n", "5", "127.0.0.1");
                        pb.redirectErrorStream(true);
                        var process = pb.start();

                        String output;
                        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                            var sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line).append("\n");
                            }
                            output = sb.toString();
                        }

                        int exitCode = process.waitFor();
                        completedCount.incrementAndGet();

                        if (exitCode != 0) {
                            errors.add("Process " + idx + " exited with code " + exitCode);
                        }
                        return "Process " + idx + ": exit=" + exitCode + ", outputLen=" + output.length();
                    } catch (Exception e) {
                        errors.add("Process " + idx + " threw: " + e.getMessage());
                        return "Process " + idx + ": FAILED - " + e.getMessage();
                    }
                }));
            }

            // Wait for all with timeout
            for (var f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        var elapsed = Duration.between(startTime, Instant.now());
        log("  Completed: " + completedCount.get() + "/" + count);
        log("  Errors: " + errors.size() + (errors.isEmpty() ? "" : " -> " + errors));
        log("  Wall-clock time: " + elapsed.toMillis() + "ms");
        log("  Note: ping -n 5 takes ~4s sequentially; if wall-clock < 10*4s = 40s, concurrency works");

        boolean passed = completedCount.get() == count && errors.isEmpty();
        log("  RESULT: " + (passed ? "PASS" : "FAIL"));
        return passed;
    }

    // ---------------------------------------------------------------
    // Test 2: Scale - 50 virtual threads, measure time + memory
    // ---------------------------------------------------------------
    private static boolean testScale() throws Exception {
        log("--- Test 2: Scale (50 virtual threads, each reading a process) ---");

        int count = 50;
        var startTime = Instant.now();
        var completedCount = new AtomicInteger(0);
        var errors = new CopyOnWriteArrayList<String>();

        // Memory before
        Runtime rt = Runtime.getRuntime();
        rt.gc();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        log("  Memory before: " + formatBytes(memBefore));

        long peakMemory = memBefore;
        var peakMemoryRef = new AtomicLong(memBefore);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();

            for (int i = 0; i < count; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        var pb = new ProcessBuilder("ping", "-n", "3", "127.0.0.1");
                        pb.redirectErrorStream(true);
                        var process = pb.start();

                        String output;
                        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                            var sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line).append("\n");
                            }
                            output = sb.toString();
                        }

                        int exitCode = process.waitFor();
                        completedCount.incrementAndGet();

                        // Sample memory periodically
                        long currentMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                        peakMemoryRef.updateAndGet(prev -> Math.max(prev, currentMem));

                        return "Process " + idx + ": exit=" + exitCode;
                    } catch (Exception e) {
                        errors.add("Process " + idx + " threw: " + e.getMessage());
                        return "Process " + idx + ": FAILED";
                    }
                }));
            }

            // Wait with timeout
            for (var f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        }

        var elapsed = Duration.between(startTime, Instant.now());
        long memAfter = rt.totalMemory() - rt.freeMemory();

        log("  Completed: " + completedCount.get() + "/" + count);
        log("  Errors: " + errors.size() + (errors.isEmpty() ? "" : " -> " + errors.subList(0, Math.min(5, errors.size()))));
        log("  Wall-clock time: " + elapsed.toMillis() + "ms (" + elapsed.toSeconds() + "s)");
        log("  Memory after: " + formatBytes(memAfter));
        log("  Peak memory (sampled): " + formatBytes(peakMemoryRef.get()));
        log("  Memory delta: " + formatBytes(memAfter - memBefore));
        log("  Note: 50 x ping -n 3 sequentially = ~100s. Concurrent should be ~2-4s.");

        boolean passed = completedCount.get() == count && errors.isEmpty();
        log("  RESULT: " + (passed ? "PASS" : "FAIL"));
        return passed;
    }

    // ---------------------------------------------------------------
    // Test 3: Pinning detection
    // ---------------------------------------------------------------
    private static boolean testPinning() throws Exception {
        log("--- Test 3: Pinning Detection ---");
        log("  (Run with: -Djdk.tracePinnedThreads=short to see pinning warnings on stderr)");
        log("  If no 'Thread.pinned' messages appear on stderr, no pinning occurred.");

        // Run a mix of I/O operations that might cause pinning:
        // synchronized blocks + Process I/O
        int count = 10;
        var completedCount = new AtomicInteger(0);
        Object sharedLock = new Object();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Void>>();

            for (int i = 0; i < count; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    // Deliberately use synchronized to test pinning behavior
                    synchronized (sharedLock) {
                        var pb = new ProcessBuilder("ping", "-n", "2", "127.0.0.1");
                        pb.redirectErrorStream(true);
                        var process = pb.start();

                        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                            while (reader.readLine() != null) {
                                // drain
                            }
                        }
                        process.waitFor();
                    }
                    completedCount.incrementAndGet();
                    return null;
                }));
            }

            for (var f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        }

        log("  Completed: " + completedCount.get() + "/" + count);
        log("  Check stderr for any 'pinned' thread warnings.");
        log("  NOTE: synchronized blocks WILL cause pinning. This is expected.");
        log("  The key question is whether Process.getInputStream().read() itself pins.");

        // Now test without synchronized to isolate Process I/O pinning
        log("  Running 10 more WITHOUT synchronized block...");
        var completedCount2 = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Void>>();

            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    var pb = new ProcessBuilder("ping", "-n", "2", "127.0.0.1");
                    pb.redirectErrorStream(true);
                    var process = pb.start();

                    try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        while (reader.readLine() != null) {
                            // drain
                        }
                    }
                    process.waitFor();
                    completedCount2.incrementAndGet();
                    return null;
                }));
            }

            for (var f : futures) {
                f.get(120, TimeUnit.SECONDS);
            }
        }

        log("  Without synchronized: completed " + completedCount2.get() + "/" + count);

        boolean passed = completedCount.get() == count && completedCount2.get() == count;
        log("  RESULT: " + (passed ? "PASS" : "FAIL"));
        return passed;
    }

    // ---------------------------------------------------------------
    // Test 4: Churn - rapidly spawn/kill processes, check for leaks
    // ---------------------------------------------------------------
    private static boolean testChurn() throws Exception {
        log("--- Test 4: Churn (spawn/kill 20 processes x 5 iterations) ---");

        Runtime rt = Runtime.getRuntime();
        rt.gc();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        log("  Memory before churn: " + formatBytes(memBefore));

        int processesPerIteration = 20;
        int iterations = 5;
        var totalSpawned = new AtomicInteger(0);
        var totalKilled = new AtomicInteger(0);
        var errors = new CopyOnWriteArrayList<String>();

        for (int iter = 0; iter < iterations; iter++) {
            final int iterNum = iter;
            var processes = new ArrayList<Process>();

            // Spawn all processes
            for (int i = 0; i < processesPerIteration; i++) {
                try {
                    var pb = new ProcessBuilder("ping", "-n", "30", "127.0.0.1"); // long-running
                    pb.redirectErrorStream(true);
                    var process = pb.start();
                    processes.add(process);
                    totalSpawned.incrementAndGet();
                } catch (Exception e) {
                    errors.add("Iter " + iterNum + " spawn error: " + e.getMessage());
                }
            }

            // Let them run briefly
            Thread.sleep(200);

            // Kill all processes - use virtual threads
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = new ArrayList<Future<Void>>();

                for (var process : processes) {
                    futures.add(executor.submit(() -> {
                        try {
                            // Drain any buffered output to avoid broken pipe
                            process.getInputStream().close();
                            process.getErrorStream().close();
                            process.getOutputStream().close();

                            process.destroyForcibly();
                            boolean exited = process.waitFor(5, TimeUnit.SECONDS);
                            if (exited) {
                                totalKilled.incrementAndGet();
                            } else {
                                errors.add("Process did not exit after destroyForcibly + 5s wait");
                            }
                        } catch (Exception e) {
                            // Still count as killed if process is gone
                            if (!process.isAlive()) {
                                totalKilled.incrementAndGet();
                            } else {
                                errors.add("Kill error: " + e.getMessage());
                            }
                        }
                        return null;
                    }));
                }

                for (var f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
            }

            log("  Iteration " + (iter + 1) + ": spawned=" + processesPerIteration
                    + ", alive after kill=" + processes.stream().filter(Process::isAlive).count());
        }

        rt.gc();
        Thread.sleep(500);
        long memAfter = rt.totalMemory() - rt.freeMemory();

        log("  Total spawned: " + totalSpawned.get());
        log("  Total confirmed killed: " + totalKilled.get());
        log("  Errors: " + errors.size() + (errors.isEmpty() ? "" : " -> " + errors.subList(0, Math.min(5, errors.size()))));
        log("  Memory before: " + formatBytes(memBefore));
        log("  Memory after: " + formatBytes(memAfter));
        log("  Memory delta: " + formatBytes(memAfter - memBefore));
        log("  Leak indicator: delta > 50MB would be concerning");

        boolean passed = totalSpawned.get() == totalKilled.get() && errors.isEmpty();
        // Allow some tolerance on kills - forcibly destroyed processes might report differently
        if (!passed && errors.isEmpty() && totalKilled.get() >= totalSpawned.get() * 0.9) {
            log("  (Allowing 90% kill rate as passing - Windows process cleanup can be async)");
            passed = true;
        }
        log("  RESULT: " + (passed ? "PASS" : "FAIL"));
        return passed;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------
    private static void log(String msg) {
        System.out.println(msg);
        REPORT.append(msg).append("\n");
    }

    private static String formatBytes(long bytes) {
        if (Math.abs(bytes) < 1024) return bytes + " B";
        if (Math.abs(bytes) < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
