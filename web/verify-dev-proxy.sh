#!/usr/bin/env bash
# Replays exactly what web/src/api/http.ts sends, but through the Vite dev proxy instead of a browser, so the
# proxy + cookie + CSRF path is proven without any UI automation.
#
#   backend:  PORT=18080 DB_URL=... RSSHUB_OPERATOR_PASSWORD=<scratch> java -jar target/rss-hub-0.0.1-SNAPSHOT.jar
#   frontend: VITE_API_TARGET=http://127.0.0.1:18080 npx vite --port 5173
#   verify:   VERIFY_OPERATOR_USER=admin VERIFY_OPERATOR_PASSWORD=<scratch> bash verify-dev-proxy.sh
#
# PROXY_BASE must be `localhost`, not `127.0.0.1`: the Vite dev server binds the IPv6/loopback name only, so an
# address-literal request fails at connect time and looks like a dead backend. The *backend* target may be
# 127.0.0.1 — Tomcat listens on both. The password is read from the environment on purpose: it never appears here.
set -uo pipefail

BASE="${PROXY_BASE:-http://localhost:5173}"
USER_NAME="${VERIFY_OPERATOR_USER:?set VERIFY_OPERATOR_USER}"
PASSWORD="${VERIFY_OPERATOR_PASSWORD:?set VERIFY_OPERATOR_PASSWORD}"
JAR=$(mktemp)
CSRF_HEADER='X-XSRF-TOKEN'
fail=0

check() { # got want label
  if [ "$1" = "$2" ]; then
    printf 'PASS  %-52s %s\n' "$3" "$1"
  else
    printf 'FAIL  %-52s got=[%s] want=[%s]\n' "$3" "$1" "$2"
    fail=1
  fi
}

# The cookie the SPA reads. After login the token rotates, so it must be re-read from the jar every time.
csrf() { awk '$6 == "XSRF-TOKEN" { t = $7 } END { print t }' "$JAR"; }

status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# 1. Cold start: the router guard asks who I am, and that GET is what plants the CSRF cookie.
me=$(curl -s -c "$JAR" -H 'Accept: application/json' "$BASE/api/auth/me")
check "$me" '{"authenticated":false}' 'guard GET /api/auth/me is anonymous JSON'
check "$(status_of "$BASE/api/feeds")" '401' 'anonymous GET /api/feeds rejected through proxy'

if [ -n "$(csrf)" ]; then
  printf 'PASS  %-52s\n' 'XSRF-TOKEN cookie planted by the first GET'
else
  printf 'FAIL  %-52s no cookie in jar\n' 'XSRF-TOKEN cookie planted by the first GET'
  fail=1
fi

# 2. The login POST: form body (Security reads params, not JSON) + raw token in the header.
before=$(csrf)
login=$(curl -s -b "$JAR" -c "$JAR" -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/x-www-form-urlencoded" -H "$CSRF_HEADER: $before" \
  --data-urlencode "username=$USER_NAME" --data-urlencode "password=$PASSWORD" \
  -o /dev/null -w '%{http_code}')
check "$login" '200' 'POST /api/auth/login with form body + csrf header'

# The SPA re-reads document.cookie for every mutating call, so rotation is invisible to it — but only if the
# backend actually writes the new value on the login response. If it does not, the next POST is a silent 403.
rotated=$(csrf)
if [ -n "$rotated" ] && [ "$rotated" != "$before" ]; then
  printf 'PASS  %-52s\n' 'csrf cookie rotated by the login response'
else
  printf 'FAIL  %-52s token unchanged or missing\n' 'csrf cookie rotated by the login response'
  fail=1
fi

# 3. The authenticated read, using the token as it stands after login.
feeds=$(curl -s -b "$JAR" -H "$CSRF_HEADER: $rotated" "$BASE/api/feeds")
check "$feeds" '[]' 'authenticated GET /api/feeds returns the empty list'

# 4. A mutating request with no CSRF header must still be refused while signed in.
check "$(status_of -X POST -b "$JAR" "$BASE/api/auth/logout")" '403' 'POST without csrf header rejected while signed in'

# 5. Logout uses the rotated token, then the session is really gone.
check "$(status_of -X POST -b "$JAR" -c "$JAR" -H "$CSRF_HEADER: $(csrf)" "$BASE/api/auth/logout")" '204' 'POST /api/auth/logout with csrf token'
check "$(status_of -b "$JAR" "$BASE/api/feeds")" '401' 'GET /api/feeds after logout is 401 again'

rm -f "$JAR"
[ "$fail" = 0 ] && echo 'ALL PASS' || echo 'SOME CHECKS FAILED'
exit "$fail"
