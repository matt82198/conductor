#!/bin/bash
# Spike 3: Discover stream-json output format
# Run this script from a SEPARATE terminal (not inside Claude Code)
# Usage: bash run-spike3.sh

OUTDIR="$(cd "$(dirname "$0")" && pwd)"
echo "Output directory: $OUTDIR"

echo ""
echo "=== Test 1: Simple prompt ==="
claude -p --verbose --output-format stream-json "Say hello in exactly 5 words" > "$OUTDIR/capture-simple.jsonl" 2>&1
echo "Captured $(wc -l < "$OUTDIR/capture-simple.jsonl") lines to capture-simple.jsonl"

echo ""
echo "=== Test 2: Tool use prompt ==="
claude -p --verbose --output-format stream-json "Read the file C:/Users/matt8/Chris Basso Sessions Dev/claude-orchestrator/ARCHITECTURE.md and tell me how many sections it has" > "$OUTDIR/capture-tools.jsonl" 2>&1
echo "Captured $(wc -l < "$OUTDIR/capture-tools.jsonl") lines to capture-tools.jsonl"

echo ""
echo "=== Test 3: Multi-tool prompt ==="
claude -p --verbose --output-format stream-json "List the files in C:/Users/matt8/Chris Basso Sessions Dev/claude-orchestrator/ and then read SPIKE-PLAN.md" > "$OUTDIR/capture-multi-tool.jsonl" 2>&1
echo "Captured $(wc -l < "$OUTDIR/capture-multi-tool.jsonl") lines to capture-multi-tool.jsonl"

echo ""
echo "=== Test 4: Long output prompt ==="
claude -p --verbose --output-format stream-json "Write a 200-word essay about software architecture" > "$OUTDIR/capture-long.jsonl" 2>&1
echo "Captured $(wc -l < "$OUTDIR/capture-long.jsonl") lines to capture-long.jsonl"

echo ""
echo "=== All captures complete ==="
echo "Files:"
ls -la "$OUTDIR"/capture-*.jsonl
echo ""
echo "Now bring these files back to Claude Code for analysis."
echo "Run: claude (in the claude-orchestrator project) and ask it to analyze the spike3 captures."
