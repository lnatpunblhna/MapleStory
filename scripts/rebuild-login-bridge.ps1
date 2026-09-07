# Rebuild LoginBridge classes into bin/maple.jar (Windows PowerShell)
# Run from repo root.

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path .\build\bridge-classes | Out-Null

$sources = @()
$sources += Get-ChildItem .\src\handling\login\bridge\*.java
$sources += Get-Item .\src\handling\login\LoginServer.java
$sources += Get-Item .\src\server\ShutdownServer.java

Write-Host "Compiling $($sources.Count) sources..."
& .\jdk\bin\javac.exe -encoding UTF-8 -source 1.7 -target 1.7 `
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
    & ..\..\jdk\bin\jar.exe uf ..\..\bin\maple.jar $rel
    if ($LASTEXITCODE -ne 0) { throw "jar uf failed for $rel" }
  }
} finally {
  Pop-Location
}

Write-Host "Done. Restart the server so LoginBridge loads."
