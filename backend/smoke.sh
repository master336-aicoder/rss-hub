#!/usr/bin/env bash
# HTTP smoke test against an already-running backend (curl only, no build tooling).
# One pass over real HTTP covering the M0 session chain, the M1 subscription/conditional-fetch
# flow, the M3 rules/tokens/ingest surface, the M3.5 settings/dashboard/status trio, the M5 repack
# collection plus its public outlet, the F1.6 changelog outlet and the trail behind it, the F2.4
# inbox filters over the rows the reader pane shows, the F10 operator's own columns, the F11
# rails that turn the wall from a card list into one payload of totals, and the F15 tag/category
# slices plus the standalone counters route that until now only MockMvc had ever seen.
#   SMOKE_PASS=<the password the instance was started with> bash smoke.sh
# Prerequisites: a disposable instance on $BASE — fresh DB and a temporary password, never the
# dev database or the real operator password — started with
#   --rsshub.ssrf.allow-private-prefixes=127.
# so the fixture feed (node .dev-logs/smoke-feed-server.js, http://127.0.0.1:19999) is fetchable.
# Prints PASS/FAIL per assertion and exits non-zero if any failed.
set -u
BASE="${BASE:-http://127.0.0.1:18080}"
JAR="${TMPDIR:-.}/smoke-cookies.txt"
USER_NAME="${SMOKE_USER:-admin}"
PASS="${SMOKE_PASS:?set SMOKE_PASS to the password this instance was started with}"
FAIL=0

check() { # check <expected> <label> <actual>
  if [ "$1" = "$3" ]; then printf 'PASS  %-48s %s\n' "$2" "$3"; else printf 'FAIL  %-48s got [%s] want [%s]\n' "$2" "$3" "$1"; FAIL=1; fi
}

has() { # has <needle> <haystack> — one grep per JSON fact, so a failure names the fact it lost
  printf '%s' "$2" | grep -q "$1" && echo yes || echo no
}

# --- M0: session and CSRF hygiene ---------------------------------------------------------------

# Spring Security answers a CSRF rejection with 401 or 403 depending on whether an anonymous context already
# exists when CsrfFilter rejects, so the contract under test is "rejected and not authenticated".
check 4 "cross-site login form (no csrf) rejected" "$(curl -s -o /dev/null -w '%{http_code}' --data-urlencode "username=$USER_NAME" --data-urlencode "password=$PASS" "$BASE/api/auth/login" | cut -c1)"

csrf() { awk '/XSRF-TOKEN/ {print $7}' "$JAR" | tail -1; }

curl -s -c "$JAR" -o /dev/null "$BASE/api/auth/me"
check yes "csrf cookie issued" "$([ -n "$(csrf)" ] && echo yes || echo no)"

check 200 "login with csrf cookie+header" "$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' \
  -H "X-XSRF-TOKEN: $(csrf)" --data-urlencode "username=$USER_NAME" --data-urlencode "password=$PASS" "$BASE/api/auth/login")"

# Rotating the token is CsrfAuthenticationStrategy's job on login, so a client must re-read the cookie.
check yes "csrf token rotated by login" "$([ -n "$(csrf)" ] && echo yes || echo no)"

check 200 "health is anonymous" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"
check UP "health reports UP" "$(curl -s "$BASE/actuator/health" | sed -n 's/.*"status":"\([A-Z]*\)".*/\1/p')"
check 401 "anonymous GET /api/feeds" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/feeds")"
# "/" is the public HTML landing M2 added (SecurityConfig permits it next to /feed.*), so the contract is
# "anonymous may read it, and reading it must not mint a session".
ANON="${TMPDIR:-.}/smoke-anon.txt"
check 200 "public landing page is anonymous" "$(curl -s -c "$ANON" -o /dev/null -w '%{http_code}' "$BASE/")"
check no "public landing creates no session" "$([ "$(grep -c RSSHUB_SESSION "$ANON")" = 0 ] && echo no || echo yes)"
check 200 "authenticated GET /api/feeds" "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/feeds")"
check true "session is remembered on /me" "$(curl -s -b "$JAR" "$BASE/api/auth/me" | sed -n 's/.*"authenticated":\([a-z]*\).*/\1/p')"

# --- M3/M3.5: every operator endpoint refuses anonymous callers ---------------------------------

for path in rules notifications events tokens settings dashboard repacks system/status; do
  check 401 "anonymous GET /api/$path" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/$path")"
done
check 401 "anonymous POST /api/ingest/events" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"title":"x"}' "$BASE/api/ingest/events")"
check 4 "settings PATCH without csrf rejected" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -X PATCH -H 'Content-Type: application/json' -d '{"inbox.pageSize":25}' "$BASE/api/settings" | cut -c1)"

# --- M1: subscribe the fixture feed and prove a real conditional fetch --------------------------

FID=$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X POST "$BASE/api/feeds" -d '{"url":"http://127.0.0.1:19999/feed.xml","group":"Smoke"}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
check yes "subscribe the fixture feed" "$([ -n "$FID" ] && echo yes || echo no)"

poll() { # poll <json-fact> — the dispatcher is async, so wait for the fact to land
  local _i
  for _i in $(seq 1 30); do
    sleep 1
    if curl -s -b "$JAR" "$BASE/api/feeds/$FID" | grep -q "$1"; then echo yes; return; fi
  done
  echo no
}

curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -o /dev/null -X POST "$BASE/api/feeds/$FID/refresh"
check yes "first fetch lands the baseline entry" "$(poll '"entryCount":1')"

# One host gets one request per rsshub.fetch.per-host-gap (30s here). 立即抓取 now wakes the dispatcher, so the
# second click would otherwise be politely deferred to the very edge of poll()'s patience; waiting the gap out
# first makes what follows measure the wake rather than the courtesy.
sleep 31
curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -o /dev/null -X POST "$BASE/api/feeds/$FID/refresh"
check yes "unchanged source answers 304" "$(poll '"lastHttpStatus":304')"

sleep 31
curl -s -o /dev/null "http://127.0.0.1:19999/bump"
curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -o /dev/null -X POST "$BASE/api/feeds/$FID/refresh"
check yes "bumped source delivers a new entry" "$(poll '"entryCount":2')"

check 2 "all entries listed" "$(curl -s -b "$JAR" "$BASE/api/entries?state=all&feed=$FID" | grep -o '"id":' | wc -l | tr -d ' ')"
check 1 "baseline excluded from unread" "$(curl -s -b "$JAR" "$BASE/api/entries?state=unread&feed=$FID" | grep -o '"id":' | wc -l | tr -d ' ')"

# --- M3: rule + test button, machine token, ingest, delivery log --------------------------------

RID=$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X POST "$BASE/api/rules" -d '{"name":"smoke mark","match":{"includeAny":["Smoke"]},"actions":[{"channel":"markOnly"}]}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
check yes "create a markOnly rule" "$([ -n "$RID" ] && echo yes || echo no)"

PREVIEW=$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -X POST "$BASE/api/rules/$RID/test")
check yes "test button scans both stored entries" "$(has '"scanned":2' "$PREVIEW")"
check yes "test button matches on the keyword" "$(has '"matched":2' "$PREVIEW")"

SECRET=$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X POST "$BASE/api/tokens" -d '{"name":"smoke-ingest","scope":"ingest"}' \
  | sed -n 's/.*"secret":"\([^"]*\)".*/\1/p')
check yes "token secret issued once" "$([ -n "$SECRET" ] && echo yes || echo no)"
check no "token list hides the secret" "$(has '"secret"' "$(curl -s -b "$JAR" "$BASE/api/tokens")")"

check yes "bearer ingest stores a new event" "$(has '"created":true' "$(curl -s -H "Authorization: Bearer $SECRET" -H 'Content-Type: application/json' -d '{"title":"Smoke event 1","dedupeKey":"smoke-evt-1"}' "$BASE/api/ingest/events")")"
check yes "same dedupeKey is not a second event" "$(has '"created":false' "$(curl -s -H "Authorization: Bearer $SECRET" -H 'Content-Type: application/json' -d '{"title":"Smoke event 1","dedupeKey":"smoke-evt-1"}' "$BASE/api/ingest/events")")"
check 401 "wrong bearer rejected" "$(curl -s -o /dev/null -w '%{http_code}' -H 'Authorization: Bearer not-the-secret' -H 'Content-Type: application/json' -d '{"title":"x"}' "$BASE/api/ingest/events")"
check yes "public event feed carries the event" "$(has 'Smoke event 1' "$(curl -s "$BASE/feed/events.rss")")"

check 200 "delivery log answers" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/notifications")"
check 400 "delivery log rejects unknown state" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/notifications?state=nonsense")"

# --- M3.5: settings, dashboard, system status ---------------------------------------------------

SETTINGS=$(curl -s -b "$JAR" "$BASE/api/settings")
check yes "settings defaults 20/60/50/100" "$(has '"dashboardEntriesPerFeed":20,"dashboardRefreshSeconds":60,"inboxPageSize":50,"notificationsPageSize":100' "$SETTINGS")"

PATCHED=$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X PATCH "$BASE/api/settings" -d '{"dashboard.entriesPerFeed":1,"dashboard.refreshSeconds":30}')
check yes "patch applies and leaves neighbours alone" "$(has '"dashboardEntriesPerFeed":1,"dashboardRefreshSeconds":30,"inboxPageSize":50' "$PATCHED")"
check yes "patched values persist on re-read" "$(has '"dashboardEntriesPerFeed":1' "$(curl -s -b "$JAR" "$BASE/api/settings")")"
check 400 "unknown setting key refused" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X PATCH "$BASE/api/settings" -d '{"fetch.intervalMinutes":5}')"
check 400 "out-of-range setting refused" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X PATCH "$BASE/api/settings" -d '{"inbox.pageSize":1}')"

DASH=$(curl -s -b "$JAR" "$BASE/api/dashboard")
check yes "dashboard echoes the live knobs" "$(has '"entriesPerFeed":1,"refreshSeconds":30' "$DASH")"
# `failCount` is the key that appears exactly once per card: `unread` now also rides on the stats block and every
# group tile, so counting it would count the rails rather than the sources.
check 1 "one card per feed" "$(printf '%s' "$DASH" | grep -o '"failCount":' | wc -l | tr -d ' ')"
check yes "card carries the feed title" "$(has '"title":"Smoke source"' "$DASH")"
check 1 "card truncated to the newest entry" "$(printf '%s' "$DASH" | grep -o '"readState":' | wc -l | tr -d ' ')"
check yes "newest entry is the bumped one" "$(has 'Smoke item 2 of revision 2' "$DASH")"
check yes "unread badge counts the non-baseline row" "$(has '"unread":1' "$DASH")"

STATUS=$(curl -s -b "$JAR" "$BASE/api/system/status")
check yes "database up with product name" "$(has '"up":true,"productName":"H2' "$STATUS")"
check yes "database counts feeds and entries" "$(has '"feeds":1,"entries":2' "$STATUS")"
check yes "both dispatchers report enabled" "$(has '"fetchEnabled":true,"notifyEnabled":true' "$STATUS")"
check yes "queue and delivery maps present" "$(has '"taskQueueByState"' "$STATUS")"
check yes "last fetch timestamp present" "$(has '"lastFetchedAt":"2' "$STATUS")"

# --- M5: repack collection, provenance, and the summary-only default -----------------------------

# The absolute origin inside publicUrl comes from rsshub.publish.base-url, which a disposable instance leaves at its
# default, so only the path is asserted here; the origin is the M2 publisher test's job.
RPSLUG=$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X POST "$BASE/api/repacks" -d '{"slug":"Smoke-Picks","title":"Smoke picks","description":"forwarded"}' \
  | sed -n 's/.*"slug":"\([^"]*\)".*/\1/p')
check smoke-picks "create lower-cases the slug" "$RPSLUG"
check yes "outlet path is the repack scope" "$(has '"publicUrl":"[^"]*/repack/smoke-picks.rss"' "$(curl -s -b "$JAR" "$BASE/api/repacks")")"
check 4 "repack POST without csrf rejected" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -H 'Content-Type: application/json' -X POST "$BASE/api/repacks" -d '{"slug":"no-csrf","title":"x"}' | cut -c1)"
check 400 "slug with a space refused" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X POST "$BASE/api/repacks" -d '{"slug":"Bad Slug","title":"x"}')"

check yes "membership echoes the source feed" "$(has '"feedId":'"$FID" "$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X POST "$BASE/api/repacks/smoke-picks/feeds" -d "{\"feedId\":$FID}")")"
check 409 "same feed added twice" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X POST "$BASE/api/repacks/smoke-picks/feeds" -d "{\"feedId\":$FID}")"
check 404 "adding an unknown feed" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X POST "$BASE/api/repacks/smoke-picks/feeds" -d '{"feedId":999999}')"

OUTLET=$(curl -s "$BASE/repack/smoke-picks.rss")
check 2 "outlet forwards both entries" "$(printf '%s' "$OUTLET" | grep -o '<item>' | wc -l | tr -d ' ')"
check yes "each item names its source" "$(has '<source url="http://127.0.0.1:19999/feed.xml">Smoke source</source>' "$OUTLET")"
check yes "the link still points at the original post" "$(has '<link>http://127.0.0.1:19999/post/' "$OUTLET")"
check yes "the guid is the original URL" "$(has '<guid isPermaLink="true">http://127.0.0.1:19999/post/' "$OUTLET")"
check no "FR-7.2: full text withheld by default" "$(has 'Full body' "$OUTLET")"
check yes "the summary is forwarded" "$(has 'Body 2, revision 2.' "$OUTLET")"

RJSON=$(curl -s "$BASE/repack/smoke-picks.json")
check yes "JSON feed carries external_url" "$(has '"external_url":"http://127.0.0.1:19999/feed.xml"' "$RJSON")"
check no "JSON feed withholds the full body too" "$(has 'Full body' "$RJSON")"
check yes "Atom carries atom:source" "$(has '<source><id>http://127.0.0.1:19999/feed.xml</id><title>Smoke source</title></source>' "$(curl -s "$BASE/repack/smoke-picks.atom")")"
check 404 "an unknown collection has no outlet" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/repack/nope.rss")"

RPOK=$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X PATCH "$BASE/api/repacks/smoke-picks/feeds/$FID" -d '{"fullContent":true}')
check yes "the member flips to full text" "$(has '"fullContent":true' "$RPOK")"
check yes "full text ships after the opt-in" "$(has 'Full body' "$(curl -s "$BASE/repack/smoke-picks.rss")")"
AUDIT=$(curl -s -b "$JAR" "$BASE/api/repacks/smoke-picks/audit")
check yes "audit holds the join and the opt-in" "$(has '"action":"full_content_on"' "$AUDIT")"
check yes "audit holds the join row" "$(has '"action":"add"' "$AUDIT")"
curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X PATCH "$BASE/api/repacks/smoke-picks/feeds/$FID" -d '{"fullContent":true}' -o /dev/null
check 1 "an unchanged flag writes no audit row" "$(printf '%s' "$(curl -s -b "$JAR" "$BASE/api/repacks/smoke-picks/audit")" | grep -o '"action":"full_content_on"' | wc -l | tr -d ' ')"

RPTAG=$(curl -s -D - -o /dev/null "$BASE/repack/smoke-picks.rss" | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: //p')
check yes "outlet sends an ETag" "$([ -n "$RPTAG" ] && echo yes || echo no)"
check 304 "outlet answers a conditional GET" "$(curl -s -o /dev/null -w '%{http_code}' -H "If-None-Match: $RPTAG" "$BASE/repack/smoke-picks.rss")"
RPOUT="${TMPDIR:-.}/smoke-repack-anon.txt"
curl -s -c "$RPOUT" -o /dev/null "$BASE/repack/smoke-picks.rss"
check no "the outlet mints no session" "$([ "$(grep -c RSSHUB_SESSION "$RPOUT")" = 0 ] && echo no || echo yes)"
rm -f "$RPOUT"

check yes "rename keeps the untouched description" "$(has '"description":"forwarded"' "$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X PATCH "$BASE/api/repacks/smoke-picks" -d '{"title":"Smoke picks renamed"}')")"
check 204 "delete the collection" "$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' -H "X-XSRF-TOKEN: $(csrf)" -X DELETE "$BASE/api/repacks/smoke-picks")"
check 404 "the outlet is gone with it" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/repack/smoke-picks.rss")"

# --- F1.6: the changelog outlet and the trail that feeds it ---------------------------------------

check 401 "anonymous GET /api/changelog" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/changelog")"
check 405 "the changelog API is read-only" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X POST "$BASE/api/changelog" -d '{}')"

CREATED=$(curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X POST "$BASE/api/posts" -d '{"title":"Smoke changelog","bodyMd":"Body one.","status":"draft"}')
POST_ID=$(printf '%s' "$CREATED" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
POST_SLUG=$(printf '%s' "$CREATED" | sed -n 's/.*"slug":"\([^"]*\)".*/\1/p')

post() { # post <method> <suffix> <json> — a CSRF-bearing write against the post API; its body is often unread
  curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X "$1" "$BASE/api/posts$2" -d "$3"
}
trail() { curl -s -b "$JAR" "$BASE/api/changelog?postId=$POST_ID"; }
count() { printf '%s' "$1" | grep -o '"id":' | wc -l | tr -d ' '; }

check yes "create a draft post" "$([ -n "$POST_ID" ] && echo yes || echo no)"
check 0 "a draft writes no change row" "$(count "$(trail)")"
check no "a draft never reaches the outlet" "$(has 'Smoke changelog' "$(curl -s "$BASE/feed/changelog.rss")")"

post PATCH "/$POST_ID/status" '{"status":"published"}' >/dev/null
CLG=$(curl -s "$BASE/feed/changelog.rss")
# Coming out of a draft is a status move, not a birth — the announcement names the transition. `New post:` belongs
# to a post created already-published, which the next block covers.
check yes "publishing announces the transition" "$(has 'Published: Smoke changelog' "$CLG")"
check yes "the item is tagged with its action" "$(has '<category>status</category>' "$CLG")"
check yes "the guid is the change's own URN" "$(has '<guid isPermaLink="false">urn:rsshub:change:' "$CLG")"
check yes "the outlet points at itself" "$(has '<atom:link href="[^"]*/feed/changelog.rss" rel="self"' "$CLG")"
check yes "the site feed carries the permalink" "$(has '<link>[^<]*/blog/'"$POST_SLUG"'</link>' "$(curl -s "$BASE/feed.rss")")"
check 1 "publishing wrote exactly one row" "$(count "$(trail)")"

BORN=$(post POST "" '{"title":"Smoke born public","bodyMd":"Published at birth.","status":"published"}')
BORN_ID=$(printf '%s' "$BORN" | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
BORN_CLG=$(curl -s "$BASE/feed/changelog.rss")
check yes "a birth announces New post" "$(has 'New post: Smoke born public' "$BORN_CLG")"
check yes "a birth is tagged created" "$(has '<category>created</category>' "$BORN_CLG")"
check 1 "a birth writes one row" "$(count "$(curl -s -b "$JAR" "$BASE/api/changelog?postId=$BORN_ID")")"

# An edit is a change; saving the identical document again is not. `bodyMd` is the case the editor hits daily.
post PUT "/$POST_ID" '{"title":"Smoke changelog","bodyMd":"Body two.","status":"published"}' >/dev/null
check yes "an edit announces an update" "$(has 'Updated: Smoke changelog' "$(curl -s "$BASE/feed/changelog.rss")")"
post PUT "/$POST_ID" '{"title":"Smoke changelog","bodyMd":"Body two.","status":"published"}' >/dev/null
check 2 "a no-op save adds no row" "$(count "$(trail)")"

post PATCH "/$POST_ID/status" '{"status":"archived"}' >/dev/null
check 3 "a status move writes its own row" "$(count "$(trail)")"
check yes "a status move announces itself" "$(has 'Archived: Smoke changelog' "$(curl -s "$BASE/feed/changelog.rss")")"
check 409 "an illegal transition is refused" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' -X PATCH "$BASE/api/posts/$POST_ID/status" -d '{"status":"scheduled"}')"
check 3 "an illegal transition writes nothing" "$(count "$(trail)")"

check yes "the newest row is the status move" "$(has '"action":"status","fromStatus":"published","toStatus":"archived"' "$(curl -s -b "$JAR" "$BASE/api/changelog?postId=$POST_ID&limit=1")")"
check 1 "limit=1 keeps one row" "$(count "$(curl -s -b "$JAR" "$BASE/api/changelog?postId=$POST_ID&limit=1")")"
check 1 "limit=0 is clamped to one" "$(count "$(curl -s -b "$JAR" "$BASE/api/changelog?limit=0")")"
check 0 "an unknown post has no trail" "$(count "$(curl -s -b "$JAR" "$BASE/api/changelog?postId=999999")")"

# Conditional GET on the outlet, and the fact that it is its own cache document, not the site feed's.
CTAG=$(curl -s -D - -o /dev/null "$BASE/feed/changelog.rss" | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: //p')
STAG=$(curl -s -D - -o /dev/null "$BASE/feed.rss" | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: //p')
check yes "the outlet sends an ETag" "$([ -n "$CTAG" ] && echo yes || echo no)"
check yes "the validator differs from the site feed's" "$([ -n "$CTAG" ] && [ "$CTAG" != "$STAG" ] && echo yes || echo no)"
check 304 "the outlet answers a conditional GET" "$(curl -s -o /dev/null -w '%{http_code}' -H "If-None-Match: $CTAG" "$BASE/feed/changelog.rss")"
ANONCL="${TMPDIR:-.}/smoke-changelog-anon.txt"
curl -s -c "$ANONCL" -o /dev/null "$BASE/feed/changelog.json"
check no "the outlet mints no session" "$([ "$(grep -c RSSHUB_SESSION "$ANONCL")" = 0 ] && echo no || echo yes)"
rm -f "$ANONCL"
check yes "Atom renders the same trail" "$(has '<entry>' "$(curl -s "$BASE/feed/changelog.atom")")"
check yes "JSON Feed renders the same trail" "$(has '"items":' "$(curl -s "$BASE/feed/changelog.json")")"
check 404 "the changelog path has no opml format" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/feed/changelog.opml")"
check yes "the blog page advertises the outlet" "$(has '/feed/changelog.rss' "$(curl -s "$BASE/blog")")"
check yes "the advertisement names the outlet" "$(has 'Changelog' "$(curl -s "$BASE/blog")")"

# Back out of the archive before deleting: an archived post already has no reader-visible URL, so removing it is
# not news (the archive row said so). Deleting something the public could still fetch is the announcement that counts.
post PATCH "/$POST_ID/status" '{"status":"published"}' >/dev/null
check 4 "coming back out of the archive writes its own row" "$(count "$(trail)")"
check yes "the way back is announced" "$(has 'Published: Smoke changelog' "$(curl -s "$BASE/feed/changelog.rss")")"

check 204 "delete the post" "$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' -H "X-XSRF-TOKEN: $(csrf)" -X DELETE "$BASE/api/posts/$POST_ID")"
check yes "the removal is announced" "$(has 'Deleted: Smoke changelog' "$(curl -s "$BASE/feed/changelog.rss")")"
check yes "the notice says the URL is dead" "$(has 'no longer resolves' "$(curl -s "$BASE/feed/changelog.rss")")"
check no "the site feed dropped the permalink" "$(has "$POST_SLUG" "$(curl -s "$BASE/feed.rss")")"
# `content_change.post_id` has no foreign key on purpose: the trail is what proves the removal was not a silent edit.
check 5 "the trail outlives the post" "$(count "$(trail)")"

# … and the mirror of that: removing something that was never public stays off the record entirely.
GONE_ID=$(post POST "" '{"title":"Smoke draft gone","bodyMd":"Never published.","status":"draft"}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
check 204 "delete a post that was never public" "$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' -H "X-XSRF-TOKEN: $(csrf)" -X DELETE "$BASE/api/posts/$GONE_ID")"
check 0 "its removal is not announced" "$(count "$(curl -s -b "$JAR" "$BASE/api/changelog?postId=$GONE_ID")")"
check no "its title never reached the outlet" "$(has 'Smoke draft gone' "$(curl -s "$BASE/feed/changelog.rss")")"

# --- F2.4: the inbox's two filters, over the rows the operator actually reads --------------------

# Flipping the default stream into the inbox makes one ingest write both facts these filters read: a new entry whose
# categories are the event's own tags, judged by the markOnly rule the M3 section left enabled. The hit row commits in
# the same transaction as the entry (03 §8.1), so neither of them needs a dispatcher wait here.
check 200 "the event stream joins the inbox" "$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' \
  -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X PATCH "$BASE/api/events/events/visibility" -d '{"visibility":"virtual_feed"}')"
check yes "ingest an event that carries tags" "$(has '"created":true' "$(curl -s -H "Authorization: Bearer $SECRET" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Smoke filter event","dedupeKey":"smoke-filter-1","tags":["SmokeTag","ops"]}' \
  "$BASE/api/ingest/events")")"

inbox() { curl -s -b "$JAR" "$BASE/api/entries$1"; }
# hasf is the fixed-string twin of has(): a JSON array's brackets are a pattern to grep, so `["a","b"]` as a needle
# silently means "one character out of that set" and can never match the text it was copied from.
hasf() { printf '%s' "$2" | grep -qF "$1" && echo yes || echo no; }
ALL=$(count "$(inbox '?state=all')")
HITS=$(count "$(inbox '?state=all&alerted=true')")
MISSES=$(count "$(inbox '?state=all&alerted=false')")

check yes "the list row hands back the tags the reader pane clicks" "$(hasf '"categories":["SmokeTag","ops"]' "$(inbox '?state=all&category=SmokeTag')")"
check 1 "a tag matches its whole element" "$(count "$(inbox '?state=all&category=SmokeTag')")"
# The second element matches too, and a tag that is only a prefix of the first matches nothing: the pattern is the
# quoted JSON element, so "Smoke" cannot report the row tagged "SmokeTag".
check 1 "any element of the array counts" "$(count "$(inbox '?state=all&category=ops')")"
check 0 "a prefix of a tag is not that tag" "$(count "$(inbox '?state=all&category=Smoke')")"
check "$ALL" "a blank tag is not a filter" "$(count "$(inbox '?state=all&category=')")"

check 1 "only the rule's own hit comes back" "$HITS"
check yes "the filtered row carries the badge the filter promised" "$(hasf '"alertedBy":["smoke mark"]' "$(inbox '?state=all&alerted=true')")"
check "$ALL" "the two halves add up to the whole list" "$((HITS + MISSES))"
check 1 "the hit filter composes with the keyword box" "$(count "$(inbox '?state=all&alerted=true&q=filter')")"
check 1 "the hit filter composes with a page size" "$(count "$(inbox '?state=all&alerted=true&limit=1')")"
check no "a one-row page of one match hands back no cursor" "$(has 'nextCursor' "$(inbox '?state=all&alerted=true&limit=1')")"
check 400 "the filter refuses a non-boolean alerted" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/entries?state=all&alerted=maybe")"
check urn:rsshub:bad_alerted "and names the reason its own way" \
  "$(curl -s -b "$JAR" "$BASE/api/entries?state=all&alerted=maybe" | sed -n 's/.*"type":"\([^"]*\)".*/\1/p')"

# --- F2.4 tail: the page size is the setting, not a number the client picks -----------------------
# The inbox asks for no `limit` at all (web/src/stores/entries.ts), so over real HTTP the settings knob has to be the
# whole story. FR-9.2 promises exactly that: a stored value the read path never consults would make the settings page
# a liar, and the promise is only checkable by shrinking the page from the API and re-reading a no-limit request.
TOTAL=$(inbox '?state=all' | sed -n 's/.*"total":\([0-9]*\).*/\1/p')
check 200 "the inbox page size is writable" "$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' \
  -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X PATCH "$BASE/api/settings" -d '{"inbox.pageSize":10}')"
# min(ALL, 10): a run whose whole inbox fits under the floor cannot show a shrink, so the expectation follows the data.
check "$([ "$ALL" -lt 10 ] && echo "$ALL" || echo 10)" "a no-limit page takes the setting's number" \
  "$(count "$(inbox '?state=all')")"
check "$([ "$ALL" -gt 10 ] && echo yes || echo no)" "and hands back a cursor for the rows it dropped" \
  "$(has 'nextCursor' "$(inbox '?state=all')")"
check "$TOTAL" "the badge stays global while the page shrinks" \
  "$(inbox '?state=all' | sed -n 's/.*"total":\([0-9]*\).*/\1/p')"
check 200 "restoring the setting is writable" "$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' \
  -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
  -X PATCH "$BASE/api/settings" -d '{"inbox.pageSize":50}')"
check "$ALL" "and the page follows it back up" "$(count "$(inbox '?state=all')")"

# --- F10: the operator's own columns, an address that cannot move, and a fetch that runs now -------
# The subscription page writes two things the site also reports — its name and its cadence — so the checks below are
# about ownership: an override must survive the next document, an edit must be able to hand the naming back, and an
# address must be refused rather than quietly ignored.
patch_feed() { # patch_feed <json> — the body is the whole patch; absent keys stay put on both sides.
  # It goes through a file on purpose: a native curl.exe receives its argv in the console codepage, so a Chinese
  # title passed with -d arrives as bytes the server cannot decode as UTF-8. The file keeps the bytes this script holds.
  printf '%s' "$1" > "$F10_BODY"
  curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -H 'Content-Type: application/json' \
    -X PATCH "$BASE/api/feeds/$FID" --data-binary "@$F10_BODY"
}
F10_BODY="${TMPDIR:-.}/f10-patch.json"
feed_row() { curl -s -b "$JAR" "$BASE/api/feeds/$FID"; }
next_fire() { feed_row | sed -n 's/.*"nextFetchAt":"\([^"]*\)".*/\1/p'; }
in_window() { # in_window <lo-seconds> <hi-seconds> <instant> — jitter is added on top of the interval, never taken off
  local epoch now
  epoch=$(date -d "$3" +%s 2>/dev/null) || { echo unparseable; return; }
  now=$(date +%s)
  [ "$epoch" -gt $((now + $1)) ] && [ "$epoch" -lt $((now + $2)) ] && echo yes || echo no
}

check yes "the policy endpoint states this install's cadence" \
  "$(has '"defaultIntervalMinutes":60,"minIntervalMinutes":5' "$(curl -s -b "$JAR" "$BASE/api/feeds/policy")")"

RENAMED=$(patch_feed '{"title":"我的源名"}')
check yes "a rename writes the operator's own column" "$(has '"customTitle":"我的源名"' "$RENAMED")"
# The site's title stays on the row: the next document refreshes that half, and the override is the half that lasts.
check yes "and leaves the site's own title in place" "$(has '"title":"Smoke source"' "$RENAMED")"
check yes "the inbox labels the rows with the name the operator chose" \
  "$(has '"feedTitle":"我的源名"' "$(inbox "?state=all&feed=$FID")")"
check yes "the health page calls the same source by the same name" \
  "$(has '"title":"我的源名"' "$(curl -s -b "$JAR" "$BASE/api/health/feeds")")"

# An edit that carries an address is the case the API could get wrong in the loudest way: answer 200, change nothing.
check 400 "an edit that names the address is refused" \
  "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" \
     -H 'Content-Type: application/json' -X PATCH "$BASE/api/feeds/$FID" \
     -d '{"canonicalUrl":"http://127.0.0.1:19999/other.xml"}')"
check urn:rsshub:unknown_field "and says which key it refused" \
  "$(patch_feed '{"canonicalUrl":"http://127.0.0.1:19999/other.xml"}' \
     | sed -n 's/.*"type":"\([^"]*\)".*/\1/p')"
check http://127.0.0.1:19999/feed.xml "the address is exactly where it was" \
  "$(feed_row | sed -n 's/.*"canonicalUrl":"\([^"]*\)".*/\1/p')"

refuses() { # refuses <body> <label> — both halves of one refusal: the status and the code a page can show
  check 400 "$2" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" \
    -H 'Content-Type: application/json' -X PATCH "$BASE/api/feeds/$FID" -d "$1")"
}
refuses '{"intervalMinutes":-1}' "a negative interval is refused"
check urn:rsshub:bad_interval "and names the reason" \
  "$(patch_feed '{"intervalMinutes":-1}' | sed -n 's/.*"type":"\([^"]*\)".*/\1/p')"
refuses '{"intervalMinutes":43201}' "an interval past thirty days is refused"
check 43200 "the thirty-day ceiling itself is accepted" \
  "$(patch_feed '{"intervalMinutes":43200}' | sed -n 's/.*"fetchIntervalMinutes":\([0-9]*\).*/\1/p')"

# The override has to outlive the next document: this feed advertises <ttl>60</ttl>, so a fetch that re-recorded the
# metadata would move a 15-minute schedule back to an hour if the two owners still shared one column.
check 15 "an override of fifteen minutes is written" \
  "$(patch_feed '{"intervalMinutes":15}' | sed -n 's/.*"fetchIntervalMinutes":\([0-9]*\).*/\1/p')"
curl -s -o /dev/null "http://127.0.0.1:19999/bump"
curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -o /dev/null -X POST "$BASE/api/feeds/$FID/refresh"
check yes "立即抓取 fetched this round, not the next one" "$(poll '"entryCount":3')"
check yes "and re-armed on the override, not on the site's ttl" "$(in_window 600 1800 "$(next_fire)")"

# `0` is what the page sends for an empty box, and it has to mean "follow the site again", not "keep the override".
check no "zero clears the override" "$(has '"fetchIntervalMinutes"' "$(patch_feed '{"intervalMinutes":0}')")"
# This host gets 30s between fetches; waiting it out keeps the next check about the schedule rather than politeness.
sleep 31
curl -s -o /dev/null "http://127.0.0.1:19999/bump"
curl -s -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $(csrf)" -o /dev/null -X POST "$BASE/api/feeds/$FID/refresh"
check yes "the cleared source is fetched again" "$(poll '"entryCount":4')"
check yes "and goes back to the hour the site asked for" "$(in_window 3300 4200 "$(next_fire)")"

# An empty title is the same hand-back for the name: the column stays, the value goes NULL, the site's title wins.
check no "an empty title hands the naming back to the site" "$(has '"customTitle"' "$(patch_feed '{"title":""}')")"
check yes "the site's title is what readers see now" \
  "$(has '"feedTitle":"Smoke source"' "$(inbox "?state=all&feed=$FID")")"
check yes "a group-only edit leaves the overrides alone" \
  "$(has '"groupName":"Relabelled"' "$(patch_feed '{"group":"Relabelled"}')")"

# --- F11: the rails around the cards ---------------------------------------------------------------
# The wall is one payload, so every number on it is checkable against the page it claims to summarise. These are
# those cross-checks: the bars and the headline, the rails and the lists they come from.
WALL=$(curl -s -b "$JAR" "$BASE/api/dashboard")
STATS=$(printf '%s' "$WALL" | sed -n 's/.*"stats":{\([^}]*\)}.*/\1/p')
stat() { printf '%s' "$STATS" | sed -n 's/.*"'"$1"'":\([0-9-]*\).*/\1/p'; }
# Each rail is one array of flat objects, so the next rail's key is a safe right-hand anchor.
ALERTS=$(printf '%s' "$WALL" | sed -n 's/.*"alerts":\[\(.*\)\],"posts".*/\1/p')
POSTS=$(printf '%s' "$WALL" | sed -n 's/.*"posts":\[\(.*\)\],"changes".*/\1/p')
CHANGES=$(printf '%s' "$WALL" | sed -n 's/.*"changes":\[\(.*\)\]}\?$/\1/p')

check 24 "the histogram is exactly 24 buckets, never a bar count that shuffles" \
  "$(printf '%s' "$WALL" | grep -o '"arrivals":' | wc -l | tr -d ' ')"
HOURS=$(printf '%s' "$WALL" | grep -o '"hour":"[^"]*"' | sed 's/.*:"//; s/"$//')
check 82800 "and its ends are 23 hours apart, oldest first" \
  "$(( $(date -d "$(printf '%s\n' "$HOURS" | tail -1)" +%s) - $(date -d "$(printf '%s\n' "$HOURS" | head -1)" +%s) ))"
check "$(stat arrivedToday)" "the bars add up to the arrival headline" \
  "$(printf '%s' "$WALL" | grep -o '"arrivals":[0-9]*' | cut -d: -f2 | awk '{ s += $1 } END { print s + 0 }')"
check "$(stat hitsToday)" "and the hit bars add up too" \
  "$(printf '%s' "$WALL" | grep -o '"hits":[0-9]*' | cut -d: -f2 | awk '{ s += $1 } END { print s + 0 }')"

# The scalars against the pages they claim to summarise, not against numbers copied into this script.
INBOX_COUNTS=$(printf '%s' "$(inbox '?state=all')" | sed -n 's/.*"counters":{\([^}]*\)}.*/\1/p')
check "$(printf '%s' "$INBOX_COUNTS" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')" "the wall's entry total is the inbox's" \
  "$(stat entries)"
check "$(printf '%s' "$INBOX_COUNTS" | sed -n 's/.*"unread":\([0-9]*\).*/\1/p')" "and its unread is the inbox's unread" \
  "$(stat unread)"
check "$(printf '%s' "$WALL" | grep -o '"failCount":' | wc -l | tr -d ' ')" "one card per subscription" "$(stat feeds)"
# The delivery log is the other half of the same table the rail reads; this instance is minutes old, so "since the
# install" and "in the wall's window" are the same set here. The list takes no `state=all` — omitting the filter is
# how it asks for every row.
check "$(curl -s -b "$JAR" "$BASE/api/notifications" | grep -o '"channel":' | wc -l | tr -d ' ')" \
  "the hit count is the delivery log's row count" "$(stat hitsToday)"
check 1 "the published count is what a reader can open" "$(stat publishedPosts)"
check 0 "a source fetched this round is not a problem" "$(stat problemFeeds)"
# The virtual event stream is written by ingest, never polled, so it is the one quiet card — and the wall says so
# instead of pretending every subscription has a fetch stamp.
check 1 "and the pushed-to event stream is the quiet one" "$(stat quietFeeds)"

check yes "the group rail folds the operator's own label" "$(has '"name":"Relabelled"' "$WALL")"
check yes "the card carries its failure half" "$(has '"failCount":0' "$WALL")"
check yes "and the queue's next promise" "$(has '"nextFetchAt":"2' "$WALL")"
check no "a source with nothing wrong leaves out the error field" "$(has '"lastError"' "$WALL")"

check yes "the alert rail names the rule that spoke" "$(has '"ruleName":"smoke mark"' "$ALERTS")"
check yes "and the entry it was about" "$(has '"entryTitle":"Smoke filter event"' "$ALERTS")"
check yes "the publish rail carries the post that was born public" "$(has '"title":"Smoke born public"' "$POSTS")"
# The deleted post is still on the changelog rail — that rail is a history, this one is a catalogue.
check no "a deleted post drops off the publish rail" "$(has "$POST_SLUG" "$POSTS")"
check yes "but its removal is still on the trail rail" "$(has '"action":"deleted"' "$CHANGES")"
check no "a post that was never public is off the publish rail" "$(has 'Smoke draft gone' "$POSTS")"
check no "and off the trail rail" "$(has 'Smoke draft gone' "$CHANGES")"

# --- F15: the outlets and the counter route this script had claimed but never actually called ------------------
# TC-PUB-40 was tagged `[C]` while the only evidence for /tag and /category lived in MockMvc; these three curls
# make the tag true, and the fourth pins the "unknown term is a 404, not an empty feed" contract over real HTTP.
OUTLET=$(post POST "" '{"title":"Smoke outlet post","bodyMd":"Tagged at birth.","status":"published","tags":["Smoke Outlet"],"categories":["Smoke Notes"]}')
check yes "a post can be born carrying a tag and a category" "$(has '"id":' "$OUTLET")"
check 200 "GET /tag/smoke-outlet.rss anonymously" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/tag/smoke-outlet.rss")"
check 1 "the tag slice holds exactly its own post" "$(curl -s "$BASE/tag/smoke-outlet.rss" | grep -o '<title>Smoke outlet post</title>' | wc -l | tr -d ' ')"
check yes "the slice is titled after the term, not the slug" "$(has '<title>Smoke Outlet · ' "$(curl -s "$BASE/tag/smoke-outlet.rss")")"
check 200 "GET /category/smoke-notes.atom anonymously" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/category/smoke-notes.atom")"
check 1 "the json slice answers with one item" "$(curl -s "$BASE/tag/smoke-outlet.json" | grep -o '"title":"Smoke outlet post"' | wc -l | tr -d ' ')"
check no "a tag is not matched by a post that never used it" "$(has 'Smoke born public' "$(curl -s "$BASE/tag/smoke-outlet.rss")")"
check 404 "an unused tag is a 404, not an empty feed" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/tag/no-such-smoke-tag.rss")"
check 404 "and an unused category likewise" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/category/no-such-smoke-category.json")"
# The inbox's own counters have a route of their own; the store polls it (web/src/stores/entries.ts:204) and until
# now only the copy nested inside the page payload had ever been fetched.
COUNTERS=$(curl -s -b "$JAR" "$BASE/api/entries/counters")
check yes "GET /api/entries/counters answers with the unread scalar" "$(has '"unread":' "$COUNTERS")"
check "$(printf '%s' "$COUNTERS" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')" "and it agrees with the page's nested counters" \
  "$(printf '%s' "$(inbox '?state=all')" | sed -n 's/.*"counters":{\([^}]*\)}.*/\1/p' | sed -n 's/.*"total":\([0-9]*\).*/\1/p')"
check 401 "anonymous counters is refused" "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/entries/counters")"

# --- M0 again: logout ends the session ----------------------------------------------------------

check 4 "authenticated logout without csrf rejected" "$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -X POST "$BASE/api/auth/logout" | cut -c1)"
check 204 "logout with csrf" "$(curl -s -b "$JAR" -c "$JAR" -o /dev/null -w '%{http_code}' -H "X-XSRF-TOKEN: $(csrf)" -X POST "$BASE/api/auth/logout")"
check 401 "GET /api/feeds after logout" "$(curl -s -b "$JAR" -o /dev/null -w '%{http_code}' "$BASE/api/feeds")"

TRACE="$(curl -s -D - -o /dev/null "$BASE/actuator/health" | tr -d '\r' | sed -n 's/^X-Trace-Id: //p')"
check 36 "trace id header on every response" "${#TRACE}"

rm -f "$JAR" "$ANON" "$F10_BODY"
[ "$FAIL" = 0 ] && echo "ALL PASS" || echo "SOME CHECKS FAILED"
exit "$FAIL"
