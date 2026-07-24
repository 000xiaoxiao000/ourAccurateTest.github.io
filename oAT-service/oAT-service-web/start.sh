#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WAR_PATH="$SCRIPT_DIR/target/oAT-service-web-1.0.0-SNAPSHOT.war"

if [[ ! -f "$WAR_PATH" ]]; then
  echo "WAR not found: $WAR_PATH" >&2
  echo "Build it first: ./mvnw -f ../pom.xml package -DskipTests" >&2
  exit 1
fi

exec java \
  --enable-native-access=ALL-UNNAMED \
  -Dio.netty.noUnsafe=true \
  ${JAVA_OPTS:-} \
  -jar "$WAR_PATH" "$@"
