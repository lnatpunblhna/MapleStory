#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f lib/mysql-connector-j-8.0.33.jar ]]; then
  echo "Downloading MySQL Connector/J 8.0.33..."
  mkdir -p lib
  curl -fsSL -o lib/mysql-connector-j-8.0.33.jar \
    https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar
fi

# 使用本机 JDK/JRE：优先 JAVA_HOME，其次 PATH 上的 java
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [[ -z "${JAVA_BIN}" || ! -x "${JAVA_BIN}" ]]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(command -v java)"
  elif [[ -x /usr/libexec/java_home ]] && JH="$(/usr/libexec/java_home 2>/dev/null)"; then
    JAVA_BIN="$JH/bin/java"
  else
    echo "找不到 java。请安装 JDK 8+ 并设置 JAVA_HOME，或把 java 加入 PATH。" >&2
    exit 1
  fi
fi

CP="lib/mysql-connector-j-8.0.33.jar:bin/maple.jar"
echo "Using java: $JAVA_BIN"
exec "$JAVA_BIN" -cp "$CP" -server \
  -DhomePath=./config/ \
  -DscriptsPath=./scripts/ \
  -DwzPath=./scripts/wz \
  -Xms512m -Xmx2048m \
  server.Start
