# Windows start — requires JDK/JRE 8+ and lib/mysql-connector-j-8.0.33.jar
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path .\lib\mysql-connector-j-8.0.33.jar)) {
  Write-Host "Fetching MySQL Connector/J..."
  & .\scripts\fetch-mysql-connector.ps1
}

$java = $null
if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
  $java = "$env:JAVA_HOME\bin\java.exe"
} elseif (Get-Command java -ErrorAction SilentlyContinue) {
  $java = (Get-Command java).Source
} elseif (Test-Path .\jdk\jre\bin\java.exe) {
  $java = (Resolve-Path .\jdk\jre\bin\java.exe).Path
} else {
  throw "java.exe not found. Set JAVA_HOME to JDK 8+."
}

# lib first so com.mysql.cj wins over any old driver shaded in maple.jar
$cp = ".\lib\mysql-connector-j-8.0.33.jar;.\bin\maple.jar"
Write-Host "Using java: $java"
Write-Host "Classpath: $cp"

& $java -cp $cp -server `
  "-DhomePath=./config/" `
  "-DscriptsPath=./scripts/" `
  "-DwzPath=./scripts/wz" `
  -Xms512m -Xmx2048m `
  server.Start
