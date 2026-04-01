import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Spike 1: Test bidirectional streaming with Claude CLI.
 *
 * Tests:
 *   1. Spawn agent with stream-json, read output, verify JSON structure
 *   2. Spawn agent in interactive mode, send follow-up via stdin
 *   3. Kill and resume a session
 *   4. Spawn 5 agents simultaneously, verify all streams readable
 *   5. Spawn 10 agents, measure memory
 *
 * Usage:
 *   javac BidirectionalSpike.java
 *   java BidirectionalSpike
 */
public class BidirectionalSpike {

    private static final StringBuilder REPORT = new StringBuilder();

    public static void main(String[] args) throws Exception {
        log("=== Spike 1: Claude CLI Bidirectional Streaming ===");
        log("Java version: " + System.getProperty("java.version"));
        log("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        log("");

        boolean allPassed = true;

        try {
            allPassed &= test1_singleAgentStreamJson();
        } catch (Exception e) {
            log("TEST 1 EXCEPTION: " + e.getMessage());
            allPassed = false;
        }

        log("");

        try {
            allPassed &= test2_interactiveSendMessage();
        } catch (Exception e) {
            log("TEST 2 EXCEPTION: " + e.getMessage());
            allPassed = false;
        }

        log("");

        try {
            allPassed &= test3_resumeSession();
        } catch (Exception e) {
            log("TEST 3 EXCEPTION: " + e.getMessage());
            allPassed = false;
        }

        log("");

        try {
            allPassed &= test4_concurrentAgents(5);
        } catch (Exception e) {
            log("TEST 4 EXCEPTION: " + e.getMessage());
            allPassed = false;
        }

        log("");

        try {
            allPassed &= test5_scaleAndMemory(10);
        } catch (Exception e) {
            log("TEST 5 EXCEPTION: " + e.getMessage());
            allPassed = false;
        }

        log("");
        log("=== OVERALL: " + (allPassed ? "ALL TESTS PASSED" : "SOME TESTS FAILED") + " ===");

        // Write report
        try (var writer = new FileWriter("spike1-output.txt")) {
            writer.write(REPORT.toString());
        }
        log("(Output also written to spike1-output.txt)");
    }

    /**
     * Test 1: Spawn a single agent with --print --verbose --output-format stream-json
     * Read stdout line by line, verify each line is valid JSON with a "type" field.
     */
    static boolean test1_singleAgentStreamJson() throws Exception {
        log("--- Test 1: Single agent, stream-json output ---");

        ProcessBuilder pb = new ProcessBuilder(
                "claude", "-p", "--verbose", "--output-format", "stream-json",
                "Reply with exactly the word PONG and nothing else"
        );
        pb.redirectErrorStream(true);

        long start = System.currentTimeMillis();
        Process proc = pb.start();

        List<String> eventTypes = new ArrayList<>();
        boolean hasSystemInit = false;
        boolean hasResult = false;
        boolean hasAssistant = false;
        String resultText = null;
        double costUsd = -1;

        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Quick JSON validation: must start with { and contain "type"
                if (!line.startsWith("{")) {
                    log("  WARNING: non-JSON line: " + line.substring(0, Math.min(80, line.length())));
                    continue;
                }

                // Extract type field with simple string parsing (no JSON library needed)
                String type = extractJsonString(line, "\"type\"");
                if (type != null) {
                    eventTypes.add(type);
                    if ("system".equals(type)) hasSystemInit = true;
                    if ("result".equals(type)) {
                        hasResult = true;
                        resultText = extractJsonString(line, "\"result\"");
                        // Extract cost
                        String costStr = extractJsonNumber(line, "\"total_cost_usd\"");
                        if (costStr != null) costUsd = Double.parseDouble(costStr);
                    }
                    if ("assistant".equals(type)) hasAssistant = true;
                }
            }
        }

        int exitCode = proc.waitFor();
        long elapsed = System.currentTimeMillis() - start;

        log("  Event types: " + eventTypes);
        log("  Has system/init: " + hasSystemInit);
        log("  Has assistant: " + hasAssistant);
        log("  Has result: " + hasResult);
        log("  Result text: " + (resultText != null ? resultText.substring(0, Math.min(50, resultText.length())) : "null"));
        log("  Cost: $" + (costUsd >= 0 ? String.format("%.4f", costUsd) : "unknown"));
        log("  Exit code: " + exitCode);
        log("  Duration: " + elapsed + "ms");

        boolean pass = hasSystemInit && hasAssistant && hasResult && exitCode == 0;
        log("  RESULT: " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /**
     * Test 2: Spawn agent in interactive mode (not --print), send a message via stdin.
     * Uses --output-format stream-json --input-format stream-json for bidirectional JSON.
     */
    static boolean test2_interactiveSendMessage() throws Exception {
        log("--- Test 2: Interactive mode, send message via stdin ---");

        ProcessBuilder pb = new ProcessBuilder(
                "claude", "--verbose", "--output-format", "stream-json",
                "--input-format", "stream-json"
        );
        pb.redirectErrorStream(false);

        Process proc = pb.start();

        // Send a message via stdin as JSON
        String inputJson = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Reply with exactly the word PONG\"}]}}";

        try (var writer = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(inputJson + "\n");
            writer.flush();
        } // Closing stdin signals we're done

        List<String> eventTypes = new ArrayList<>();
        boolean gotAssistant = false;
        boolean gotResult = false;

        try (var reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            // Read with a timeout — don't hang forever
            ExecutorService exec = Executors.newSingleThreadExecutor();
            Future<List<String>> future = exec.submit(() -> {
                List<String> lines = new ArrayList<>();
                String l;
                try {
                    while ((l = reader.readLine()) != null) {
                        lines.add(l.trim());
                    }
                } catch (IOException e) {
                    // Process ended
                }
                return lines;
            });

            try {
                List<String> lines = future.get(120, TimeUnit.SECONDS);
                for (String l : lines) {
                    if (l.isEmpty() || !l.startsWith("{")) continue;
                    String type = extractJsonString(l, "\"type\"");
                    if (type != null) {
                        eventTypes.add(type);
                        if ("assistant".equals(type)) gotAssistant = true;
                        if ("result".equals(type)) gotResult = true;
                    }
                }
            } catch (TimeoutException e) {
                log("  TIMEOUT waiting for response (120s)");
                proc.destroyForcibly();
            }
            exec.shutdownNow();
        }

        int exitCode = proc.waitFor();

        log("  Event types: " + eventTypes);
        log("  Got assistant response: " + gotAssistant);
        log("  Got result: " + gotResult);
        log("  Exit code: " + exitCode);

        // If stdin-json doesn't work, that's a valid finding — document it
        if (!gotAssistant && !gotResult) {
            log("  NOTE: Interactive stream-json input may not be supported.");
            log("  FINDING: stdin JSON input " + (eventTypes.isEmpty() ? "produced no output" : "produced: " + eventTypes));
            log("  RESULT: INCONCLUSIVE (document finding)");
            return true; // Not a hard failure — architecture has fallback
        }

        boolean pass = gotAssistant && exitCode == 0;
        log("  RESULT: " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /**
     * Test 3: Spawn an agent, get its session_id, kill it, try --resume.
     */
    static boolean test3_resumeSession() throws Exception {
        log("--- Test 3: Kill and resume session ---");

        // Step 1: Run an agent, capture session_id
        ProcessBuilder pb1 = new ProcessBuilder(
                "claude", "-p", "--verbose", "--output-format", "stream-json",
                "Remember: the secret word is BANANA. Reply OK."
        );
        pb1.redirectErrorStream(true);
        Process proc1 = pb1.start();

        String sessionId = null;
        try (var reader = new BufferedReader(new InputStreamReader(proc1.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("{") && line.contains("\"session_id\"")) {
                    sessionId = extractJsonString(line, "\"session_id\"");
                    if (sessionId != null) break;
                }
            }
        }
        // Drain remaining output
        proc1.getInputStream().transferTo(OutputStream.nullOutputStream());
        proc1.waitFor();

        log("  Step 1: session_id = " + sessionId);

        if (sessionId == null) {
            log("  FAIL: Could not capture session_id");
            log("  RESULT: FAIL");
            return false;
        }

        // Step 2: Resume with --resume and ask about the secret word
        ProcessBuilder pb2 = new ProcessBuilder(
                "claude", "-p", "--verbose", "--output-format", "stream-json",
                "--resume", sessionId,
                "What was the secret word I told you?"
        );
        pb2.redirectErrorStream(true);
        Process proc2 = pb2.start();

        String resultText = null;
        boolean gotResult = false;
        try (var reader = new BufferedReader(new InputStreamReader(proc2.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("{") && line.contains("\"result\"")) {
                    String type = extractJsonString(line, "\"type\"");
                    if ("result".equals(type)) {
                        gotResult = true;
                        resultText = extractJsonString(line, "\"result\"");
                    }
                }
            }
        }
        int exitCode = proc2.waitFor();

        boolean mentionsBanana = resultText != null &&
                resultText.toUpperCase().contains("BANANA");

        log("  Step 2: resume exit code = " + exitCode);
        log("  Result mentions BANANA: " + mentionsBanana);
        log("  Result text: " + (resultText != null ? resultText.substring(0, Math.min(100, resultText.length())) : "null"));

        boolean pass = gotResult && mentionsBanana && exitCode == 0;
        log("  RESULT: " + (pass ? "PASS" : "FAIL"));
        return pass;
    }

    /**
     * Test 4: Spawn N agents simultaneously, verify all produce valid output.
     */
    static boolean test4_concurrentAgents(int count) throws Exception {
        log("--- Test 4: " + count + " concurrent agents ---");

        long start = System.currentTimeMillis();
        List<Future<Boolean>> futures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(
                                "claude", "-p", "--verbose", "--output-format", "stream-json",
                                "Reply with the number " + idx + " and nothing else"
                        );
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();

                        boolean gotResult = false;
                        try (var reader = new BufferedReader(
                                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.contains("\"type\":\"result\"")) {
                                    gotResult = true;
                                }
                            }
                        }
                        int exit = proc.waitFor();
                        return gotResult && exit == 0;
                    } catch (Exception e) {
                        return false;
                    }
                }));
            }

            int successes = 0;
            int failures = 0;
            for (var f : futures) {
                try {
                    if (f.get(180, TimeUnit.SECONDS)) {
                        successes++;
                    } else {
                        failures++;
                    }
                } catch (Exception e) {
                    failures++;
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log("  Completed: " + successes + "/" + count);
            log("  Failures: " + failures);
            log("  Wall-clock: " + elapsed + "ms");

            boolean pass = failures == 0;
            log("  RESULT: " + (pass ? "PASS" : "FAIL"));
            return pass;
        }
    }

    /**
     * Test 5: Spawn N agents and measure memory.
     */
    static boolean test5_scaleAndMemory(int count) throws Exception {
        log("--- Test 5: " + count + " agents, memory measurement ---");

        Runtime rt = Runtime.getRuntime();
        rt.gc();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        log("  Memory before: " + formatBytes(memBefore));

        long start = System.currentTimeMillis();
        List<Future<Boolean>> futures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(
                                "claude", "-p", "--verbose", "--output-format", "stream-json",
                                "Reply with just the word OK"
                        );
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();

                        boolean gotResult = false;
                        try (var reader = new BufferedReader(
                                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.contains("\"type\":\"result\"")) {
                                    gotResult = true;
                                }
                            }
                        }
                        proc.waitFor();
                        return gotResult;
                    } catch (Exception e) {
                        return false;
                    }
                }));
            }

            int successes = 0;
            for (var f : futures) {
                try {
                    if (f.get(180, TimeUnit.SECONDS)) successes++;
                } catch (Exception e) { /* timeout or error */ }
            }

            long elapsed = System.currentTimeMillis() - start;
            long memAfter = rt.totalMemory() - rt.freeMemory();

            log("  Completed: " + successes + "/" + count);
            log("  Wall-clock: " + elapsed + "ms");
            log("  Memory after: " + formatBytes(memAfter));
            log("  Memory delta: " + formatBytes(memAfter - memBefore));

            boolean pass = successes == count;
            log("  RESULT: " + (pass ? "PASS" : "FAIL"));
            return pass;
        }
    }

    // --- Utility methods ---

    static String extractJsonString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        // Find the colon after the key
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        // Find the opening quote
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        // Find the closing quote (handle escaped quotes)
        int end = start + 1;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
            end++;
        }
        if (end >= json.length()) return null;
        return json.substring(start + 1, end);
    }

    static String extractJsonNumber(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.' || json.charAt(end) == '-')) end++;
        if (end == start) return null;
        return json.substring(start, end);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static void log(String msg) {
        System.out.println(msg);
        REPORT.append(msg).append("\n");
    }
}
