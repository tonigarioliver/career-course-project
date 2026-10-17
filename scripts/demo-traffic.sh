#!/bin/bash
# Demo traffic: creates a resource and a handful of reservations, then provokes the interesting failures (conflict 409,
# forbidden 403, a notification that fails for good). Needs the stack from docker-compose.yml and the three services running.
# Usage: scripts/demo-traffic.sh [YYYY-MM-DD]   (the day the reservations are for; random in the future by default, so a
# second run does not just collide with the first one)
DAY=${1:-$(date -u -d "+$((RANDOM % 300 + 30)) days" +%F)}
tok(){ curl -s -X POST http://localhost:8081/realms/slotwise/protocol/openid-connect/token -d client_id=booking-service-client -d username=$1 -d password=$2 -d grant_type=password | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"; }
A=$(tok alice alice123); B=$(tok bob bob123)
call(){ curl -s -w "\nHTTP %{http_code}" -X POST "http://localhost:8080$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -d "$3"; }
echo "### alice (RESERVATION_ADMIN) creates a resource"; R=$(call /api/resources $A '{"name":"Meeting room Barcelona"}'); echo "$R"
RID=$(echo "$R" | head -1 | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
for h in 09 10 11 12 13 14; do echo; echo "### alice reserves ${h}:00"; call /api/reservations $A "{\"resourceId\":$RID,\"startTime\":\"${DAY}T${h}:00:00Z\",\"endTime\":\"${DAY}T${h}:50:00Z\"}"; sleep 3; done
echo; echo "### conflict: overlaps the 10:00 reservation"; call /api/reservations $A "{\"resourceId\":$RID,\"startTime\":\"${DAY}T10:10:00Z\",\"endTime\":\"${DAY}T10:30:00Z\"}"
echo; echo "### conflict again (13:00)"; call /api/reservations $A "{\"resourceId\":$RID,\"startTime\":\"${DAY}T13:15:00Z\",\"endTime\":\"${DAY}T13:30:00Z\"}" >/dev/null
echo; echo "### bob (RESERVATION_USER) tries to create a resource"; call /api/resources $B '{"name":"Not allowed"}'
echo; echo "### bob books for someone else"; call /api/reservations $B "{\"resourceId\":$RID,\"ownerSubject\":\"someone-else\",\"startTime\":\"${DAY}T16:00:00Z\",\"endTime\":\"${DAY}T16:50:00Z\"}"
echo; echo "### alice books for an owner that does not exist in Keycloak (the notification will fail)"; call /api/reservations $A "{\"resourceId\":$RID,\"ownerSubject\":\"ghost-user-0000\",\"startTime\":\"${DAY}T17:00:00Z\",\"endTime\":\"${DAY}T17:50:00Z\"}"
echo
