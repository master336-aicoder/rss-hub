#!/usr/bin/env bash
# Runs backend/smoke.sh against a throwaway instance: fresh H2 file, a password that lives only in this shell,
# and the loopback fixture feed the sequence subscribes. Never the dev database, never the real operator password.
#   bash .dev-logs/smoke-run.sh [db-tag]
set -uo pipefail
cd /d/qorder/rss

RUN_TAG="${1:-db}"
PORT=18080
BASE="http://127.0.0.1:$PORT"
DB_URL="jdbc:h2:file:D:/qorder/rss/.dev-logs/smoke-$RUN_TAG"
rm -f ".dev-logs/smoke-$RUN_TAG.mv.db" ".dev-logs/smoke-$RUN_TAG.trace.db"

if [ ! -f backend/target/rss-hub-0.0.1-SNAPSHOT.jar ]; then
  echo "no jar: run ./mvnw -o -B -ntp verify first" >&2
  exit 2
fi

node .dev-logs/smoke-feed-server.js >.dev-logs/smoke-feed-server.log 2>&1 &
FEED_PID=$!

SMOKE_PASS="$(openssl rand -hex 12 2>/dev/null || date +%s%N)"
export SMOKE_PASS
RSSHUB_OPERATOR_PASSWORD="$SMOKE_PASS" \
  java -jar backend/target/rss-hub-0.0.1-SNAPSHOT.jar \
  --server.port=$PORT \
  --spring.datasource.url="$DB_URL" \
  --rsshub.ssrf.allow-private-prefixes=127. \
  >.dev-logs/smoke-backend.log 2>&1 &
BACKEND_PID=$!

cleanup() {
  kill "$BACKEND_PID" "$FEED_PID" 2>/dev/null
  wait "$BACKEND_PID" 2>/dev/null
  wait "$FEED_PID" 2>/dev/null
}
trap cleanup EXIT

for _ in $(seq 1 90); do
  if curl -s -o /dev/null "$BASE/actuator/health"; then break; fi
  sleep 1
done
if ! curl -s -o /dev/null "$BASE/actuator/health"; then
  echo "backend did not come up; see .dev-logs/smoke-backend.log" >&2
  tail -30 .dev-logs/smoke-backend.log
  exit 3
fi

BASE="$BASE" bash backend/smoke.sh
