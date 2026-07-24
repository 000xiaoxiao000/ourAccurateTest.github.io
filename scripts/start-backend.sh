#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICE_DIR="$ROOT_DIR/oAT-service"
WAR_PATH="$SERVICE_DIR/oAT-service-web/target/oAT-service-web-1.0.0-SNAPSHOT.war"

if [[ ! -f "$WAR_PATH" ]]; then
  echo "WAR not found: $WAR_PATH" >&2
  echo "Build it first: cd $SERVICE_DIR && ./oAT-service-web/mvnw -f pom.xml package -DskipTests" >&2
  exit 1
fi

exec java \
  --enable-native-access=ALL-UNNAMED \
  -Dio.netty.noUnsafe=true \
  ${JAVA_OPTS:-} \
  -jar "$WAR_PATH" "$@"
