# Spike 3: Discover stream-json output format
# Run this script from a SEPARATE terminal (PowerShell, not inside Claude Code)
# Usage: .\run-spike3.ps1

$outDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "Output directory: $outDir"

Write-Host ""
Write-Host "=== Test 1: Simple prompt ==="
claude -p --output-format stream-json "Say hello in exactly 5 words" 2>&1 | Out-File -FilePath "$outDir\capture-simple.jsonl" -Encoding utf8
$lines = (Get-Content "$outDir\capture-simple.jsonl").Count
Write-Host "Captured $lines lines to capture-simple.jsonl"

Write-Host ""
Write-Host "=== Test 2: Tool use prompt ==="
claude -p --output-format stream-json "Read the file C:/Users/matt8/Chris Basso Sessions Dev/claude-orchestrator/ARCHITECTURE.md and tell me how many sections it has" 2>&1 | Out-File -FilePath "$outDir\capture-tools.jsonl" -Encoding utf8
$lines = (Get-Content "$outDir\capture-tools.jsonl").Count
Write-Host "Captured $lines lines to capture-tools.jsonl"

Write-Host ""
Write-Host "=== Test 3: Multi-tool prompt ==="
claude -p --output-format stream-json "List the files in C:/Users/matt8/Chris Basso Sessions Dev/claude-orchestrator/ and then read SPIKE-PLAN.md" 2>&1 | Out-File -FilePath "$outDir\capture-multi-tool.jsonl" -Encoding utf8
$lines = (Get-Content "$outDir\capture-multi-tool.jsonl").Count
Write-Host "Captured $lines lines to capture-multi-tool.jsonl"

Write-Host ""
Write-Host "=== Test 4: Long output prompt ==="
claude -p --output-format stream-json "Write a 200-word essay about software architecture" 2>&1 | Out-File -FilePath "$outDir\capture-long.jsonl" -Encoding utf8
$lines = (Get-Content "$outDir\capture-long.jsonl").Count
Write-Host "Captured $lines lines to capture-long.jsonl"

Write-Host ""
Write-Host "=== All captures complete ==="
Get-ChildItem "$outDir\capture-*.jsonl" | Format-Table Name, Length
Write-Host ""
Write-Host "Now bring these files back to Claude Code for analysis."
