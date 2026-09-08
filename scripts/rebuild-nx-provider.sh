#!/usr/bin/env bash
# 把 NX provider 及相关改动重新打进 bin/maple.jar。
# 从仓库根目录运行。需要本机装了完整 JDK（javac + jar），JRE 不够。
set -euo pipefail
cd "$(dirname "$0")/.."

find_tool() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/$1" ]]; then
    echo "$JAVA_HOME/bin/$1"; return
  fi
  if command -v "$1" >/dev/null 2>&1; then
    command -v "$1"; return
  fi
  if [[ -x /usr/libexec/java_home ]] && JH="$(/usr/libexec/java_home 2>/dev/null)"; then
    if [[ -x "$JH/bin/$1" ]]; then echo "$JH/bin/$1"; return; fi
  fi
}

JAVAC="$(find_tool javac)"
JAR="$(find_tool jar)"
if [[ -z "${JAVAC:-}" ]]; then
  echo "找不到 javac。JRE 没有编译器，需要完整 JDK 8+，并设置 JAVA_HOME 或把 javac 放到 PATH。" >&2
  exit 1
fi
if [[ -z "${JAR:-}" ]]; then
  echo "找不到 jar（需要 JDK 的 bin 目录，或设置 JAVA_HOME）。" >&2
  exit 1
fi
echo "Using javac: $JAVAC"
echo "Using jar:   $JAR"

SOURCES=(
  src/provider/*.java
  src/provider/nx/*.java
  src/provider/WzXML/MapleDataType.java
  src/server/maps/MapleMapFactory.java
  src/server/life/MapleLifeFactory.java
  src/tools/NXCheck.java
)

OUT=build/nx-classes
rm -rf "$OUT"
mkdir -p "$OUT"

# 新 JDK 已经不接受 -source 1.7（rebuild-login-bridge.ps1 里那套），优先用 --release 8
echo "Compiling ${#SOURCES[@]} source globs..."
if ! "$JAVAC" --release 8 -encoding UTF-8 -nowarn -cp bin/maple.jar -d "$OUT" "${SOURCES[@]}" 2>/dev/null; then
  echo "--release 8 不可用，退回 -source 1.7 -target 1.7"
  "$JAVAC" -source 1.7 -target 1.7 -encoding UTF-8 -nowarn -cp bin/maple.jar -d "$OUT" "${SOURCES[@]}"
fi

# 清掉 jar 里已删除的 WZ-XML 实现（MapleDataType 要留着，仍在用）
if command -v zip >/dev/null 2>&1; then
  zip -q -d bin/maple.jar \
    'provider/WzXML/XMLWZFile*.class' \
    'provider/WzXML/XMLDomMapleData*.class' \
    'provider/WzXML/FileStoredPngMapleCanvas*.class' \
    'provider/WzXML/PNGMapleCanvas*.class' \
    'provider/WzXML/WZEntry*.class' \
    'provider/WzXML/WZDirectoryEntry*.class' \
    'provider/WzXML/WZFileEntry*.class' >/dev/null 2>&1 || true   # 已经清过就会报 "Nothing to do"，忽略
fi

echo "Packing into bin/maple.jar..."
# 用 -C 让 jar 自己递归，条目名才不会带上 ./ 前缀
"$JAR" uf bin/maple.jar -C "$OUT" provider -C "$OUT" server -C "$OUT" tools

echo
echo "完成。启服前先跑一遍数据预检："
echo "  java -cp bin/maple.jar -DwzPath=./wz tools.NXCheck"
