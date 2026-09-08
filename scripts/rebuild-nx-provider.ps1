# 把 NX provider 及相关改动重新打进 bin/maple.jar（Windows PowerShell）
# 从仓库根目录运行。需要完整 JDK（javac），JRE 没有编译器。

$ErrorActionPreference = "Stop"

function Find-Tool([string]$name) {
  $cmd = Get-Command $name -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  if ($env:JAVA_HOME) {
    $p = Join-Path $env:JAVA_HOME "bin\$name"
    if (Test-Path $p) { return $p }
  }
  return $null
}

$javac = Find-Tool "javac.exe"
$jar = Find-Tool "jar.exe"
if (-not $javac) {
  Write-Error "javac.exe not found. 需要完整 JDK 8+，请设置 JAVA_HOME 或把 javac 加入 PATH。"
}
if (-not $jar) {
  Write-Error "jar.exe not found（需要 JDK bin，或设置 JAVA_HOME）。"
}

Write-Host "Using javac: $javac"
Write-Host "Using jar:   $jar"

$out = ".\build\nx-classes"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = @()
$sources += Get-ChildItem .\src\provider\*.java
$sources += Get-ChildItem .\src\provider\nx\*.java
$sources += Get-Item .\src\provider\WzXML\MapleDataType.java
$sources += Get-Item .\src\server\maps\MapleMapFactory.java
$sources += Get-Item .\src\server\life\MapleLifeFactory.java
$sources += Get-Item .\src\tools\NXCheck.java

$files = $sources | ForEach-Object { $_.FullName }
Write-Host "Compiling $($files.Count) sources..."

# 新 JDK 已不接受 -source 1.7，优先 --release 8
& $javac --release 8 -encoding UTF-8 -nowarn -cp .\bin\maple.jar -d $out $files 2>$null
if ($LASTEXITCODE -ne 0) {
  Write-Host "--release 8 不可用，退回 -source 1.7 -target 1.7"
  & $javac -source 1.7 -target 1.7 -encoding UTF-8 -nowarn -cp .\bin\maple.jar -d $out $files
  if ($LASTEXITCODE -ne 0) { throw "javac failed" }
}

Write-Host "Packing into bin/maple.jar..."
& $jar uf .\bin\maple.jar -C $out provider -C $out server -C $out tools
if ($LASTEXITCODE -ne 0) { throw "jar uf failed" }

Write-Host ""
Write-Host "完成。启服前先跑一遍数据预检："
Write-Host "  java -cp bin\maple.jar -DwzPath=.\wz tools.NXCheck"
