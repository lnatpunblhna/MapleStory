# Download MySQL Connector/J 8.0.33 into lib/ (Maven Central)
$ErrorActionPreference = "Stop"
$ver = "8.0.33"
$name = "mysql-connector-j-$ver.jar"
$url = "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/$ver/$name"
$destDir = Join-Path $PSScriptRoot "..\lib"
$dest = Join-Path $destDir $name
New-Item -ItemType Directory -Force -Path $destDir | Out-Null
if (Test-Path $dest) {
  Write-Host "Already present: $dest"
  exit 0
}
Write-Host "Downloading $url"
Invoke-WebRequest -Uri $url -OutFile $dest
Write-Host "Saved $dest"
