#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f lib/mysql-connector-j-8.0.33.jar ]]; then
  echo "Downloading MySQL Connector/J 8.0.33..."
  mkdir -p lib
  curl -fsSL -o lib/mysql-connector-j-8.0.33.jar \
    https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar
fi

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
if [[ -z "${JAVA_BIN}" || ! -x "${JAVA_BIN}" ]]; then
  if [[ -x ./jdk/jre/bin/java ]]; then
    JAVA_BIN=./jdk/jre/bin/java
  else
    JAVA_BIN=java
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
