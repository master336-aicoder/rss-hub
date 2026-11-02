#!/usr/bin/env bash
# Starts the whole stack for daily use: backend on :8080 (H2 file db at backend/data/rss-hub.mv.db)
# and the Vue dev server on :5173, which proxies /api and /actuator. Open http://localhost:5173/
#
#   bash start-dev.sh              start both in the background, wait until they answer
#   bash start-dev.sh stop         stop both
#   bash start-dev.sh status       show what is listening
#
# Credentials are never in this file: the operator password is read from backend/dev.env, which the first
# start provisions and Flyway-independent bootstrap then refuses to overwrite (see OperatorBootstrap).
set -uo pipefail

cd "$(dirname "$0")" || exit 1
ROOT=$(pwd)
LOG_DIR="$ROOT/.dev-logs"
mkdir -p "$LOG_DIR"

# Absolute because the backend is launched from backend/, and its relative H2 path (./data/rss-hub) is
# resolved against that working directory.
BACKEND_JAR=$(ls "$ROOT"/backend/target/rss-hub-*.jar 2>/dev/null | head -1)

stop() {
  for port in 8080 5173; do
    local pid
    pid=$(netstat -ano | grep ":$port " | grep LISTENING | head -1 | awk '{print $NF}')
    if [ -n "${pid:-}" ]; then
      echo "stopping :$port (pid $pid)"
      taskkill //PID "$pid" //F >/dev/null 2>&1
    fi
  done
}

status() {
  netstat -ano | grep -E ':(8080|5173) ' | grep LISTENING || echo "nothing listening on 8080/5173"
}

case "${1:-start}" in
  stop)
    stop
    exit 0
    ;;
  status)
    status
    exit 0
    ;;
  start) ;;
  *)
    echo "usage: bash start-dev.sh [start|stop|status]" >&2
    exit 2
    ;;
esac

if [ -z "$BACKEND_JAR" ]; then
  echo "no jar found; build first:  cd backend && ./mvnw -DskipTests package" >&2
  exit 1
fi

if [ ! -f backend/dev.env ]; then
  echo "backend/dev.env is missing. Create it with one line:" >&2
  echo "  RSSHUB_OPERATOR_PASSWORD=<the password you want for the admin account>" >&2
  echo "It is only used while the app_user table is empty; afterwards the database owns the credential." >&2
  exit 1
fi

# shellcheck disable=SC1091
set -a; . backend/dev.env; set +a
export RSSHUB_OPERATOR_USER="${RSSHUB_OPERATOR_USER:-admin}"

stop
rm -f "$LOG_DIR/backend.log" "$LOG_DIR/vite.log"

( cd backend && java -jar "$BACKEND_JAR" > "$LOG_DIR/backend.log" 2>&1 ) &
( cd web && npm run dev -- --port 5173 --strictPort > "$LOG_DIR/vite.log" 2>&1 ) &

for _ in $(seq 1 60); do
  if curl -sf -o /dev/null http://127.0.0.1:8080/actuator/health \
     && curl -sf -o /dev/null http://localhost:5173/; then
    echo "ready: http://localhost:5173/   (sign in as $RSSHUB_OPERATOR_USER)"
    exit 0
  fi
  sleep 1
done

echo "not ready after 60s — see $LOG_DIR/backend.log and $LOG_DIR/vite.log" >&2
tail -5 "$LOG_DIR/backend.log" >&2
exit 1
