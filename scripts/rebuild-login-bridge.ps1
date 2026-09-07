# Rebuild LoginBridge classes into bin/maple.jar (Windows PowerShell)
# Run from repo root. Needs a real JDK (javac). Bundled jdk/ is often JRE-only.

$ErrorActionPreference = "Stop"

function Find-Tool([string]$name) {
  $cmd = Get-Command $name -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  if ($env:JAVA_HOME) {
    $p = Join-Path $env:JAVA_HOME "bin\$name"
    if (Test-Path $p) { return $p }
  }
  $bundled = Join-Path $PSScriptRoot "..\jdk\bin\$name"
  if (Test-Path $bundled) { return (Resolve-Path $bundled).Path }
  return $null
}

$javac = Find-Tool "javac.exe"
$jar = Find-Tool "jar.exe"
if (-not $javac) {
  Write-Error @"
javac.exe not found.
This repo's .\jdk is often JRE-only (no compiler).
Install JDK 8 (or 7), then either:
  1) set JAVA_HOME to that JDK, or
  2) ensure javac is on PATH
Then re-run this script.
"@
}
if (-not $jar) {
  Write-Error "jar.exe not found (need JDK bin, or set JAVA_HOME)."
}

Write-Host "Using javac: $javac"
Write-Host "Using jar:   $jar"

New-Item -ItemType Directory -Force -Path .\build\bridge-classes | Out-Null

$sources = @()
$sources += Get-ChildItem .\src\handling\login\bridge\*.java
$sources += Get-Item .\src\handling\login\LoginServer.java
$sources += Get-Item .\src\server\ShutdownServer.java

Write-Host "Compiling $($sources.Count) sources..."
& $javac -encoding UTF-8 -source 1.7 -target 1.7 `
  -cp .\bin\maple.jar `
  -d .\build\bridge-classes `
  ($sources | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$root = (Resolve-Path .\build\bridge-classes).Path
Push-Location $root
try {
  Get-ChildItem -Recurse -Filter *.class | ForEach-Object {
    $rel = $_.FullName.Substring($root.Length + 1)
    Write-Host "jar uf $rel"
    & $jar uf ..\..\bin\maple.jar $rel
    if ($LASTEXITCODE -ne 0) { throw "jar uf failed for $rel" }
  }
} finally {
  Pop-Location
}

Write-Host "Done. Restart the server so LoginBridge loads."
