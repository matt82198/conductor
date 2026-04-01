#!/bin/bash
# Spike 1: Bidirectional Claude CLI streaming
# Run from a SEPARATE terminal (not inside Claude Code)
# Usage: bash run-spike1.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

export JAVA_HOME="C:/Users/matt8/.jdks/openjdk-25.0.2"

echo "=== Compiling BidirectionalSpike.java ==="
$JAVA_HOME/bin/javac BidirectionalSpike.java
if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo ""
echo "=== Running Spike 1 ==="
echo "(This spawns real Claude agents — may take a few minutes and cost ~$1)"
echo ""

$JAVA_HOME/bin/java -Djdk.tracePinnedThreads=short BidirectionalSpike

echo ""
echo "=== Done. Results in spike1-output.txt ==="
