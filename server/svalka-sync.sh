#!/usr/bin/env bash
# Everyday management of the СВАЛКА sync server. Run from its folder (default /opt/svalka-sync).
#
#   ./svalka-sync.sh status
#   ./svalka-sync.sh user add EMAIL PASSWORD     create an account for the apps
#   ./svalka-sync.sh user passwd EMAIL PASSWORD  change a password
#   ./svalka-sync.sh signup on|off               open or close self sign-up in the apps
#   ./svalka-sync.sh backup [DEST]               copy a consistent snapshot of the data (DEST: folder or user@host:path)
#   ./svalka-sync.sh update                      rebuild with the current files and restart
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
[[ -f .env ]] && set -a && source .env && set +a
PROFILE=()
[[ "${SVALKA_CADDY:-false}" == true ]] && PROFILE=(--profile caddy)
API="http://127.0.0.1:${SVALKA_PORT:-8090}"
pbx() { docker compose exec -T pocketbase "$@"; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
api() { curl -fsS -H "Content-Type: application/json" "$@"; }

superuser_token() {
  # A short-lived superuser token without knowing the admin password.
  local email="cli@svalka-sync.internal" pass
  pass="$(head -c 24 /dev/urandom | base64 | tr -d '/+=' | head -c 24)"
  pbx /pb/pocketbase superuser upsert "$email" "$pass" --dir=/pb/pb_data >/dev/null
  api -d "{\"identity\":\"$email\",\"password\":\"$pass\"}" "$API/api/collections/_superusers/auth-with-password" \
    | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

case "${1:-}" in
  status)
    docker compose ps
    api "$API/api/svalka/info"; echo ;;
  user)
    [[ $# -eq 4 ]] || { echo "usage: $0 user add|passwd EMAIL PASSWORD" >&2; exit 2; }
    token="$(superuser_token)"
    if [[ "$2" == add ]]; then
      api -H "Authorization: $token" -d "{\"email\":\"$3\",\"password\":\"$4\",\"passwordConfirm\":\"$4\",\"verified\":true}" \
        "$API/api/collections/users/records" >/dev/null && echo "Account $3 created."
    elif [[ "$2" == passwd ]]; then
      id="$(api -G -H "Authorization: $token" --data-urlencode "filter=email='$3'" "$API/api/collections/users/records" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)"
      [[ -n "$id" ]] || { echo "No account $3" >&2; exit 1; }
      api -X PATCH -H "Authorization: $token" -d "{\"password\":\"$4\",\"passwordConfirm\":\"$4\"}" \
        "$API/api/collections/users/records/$id" >/dev/null && echo "Password changed."
    else echo "usage: $0 user add|passwd EMAIL PASSWORD" >&2; exit 2; fi ;;
  signup)
    case "${2:-}" in on) v=true ;; off) v=false ;; *) echo "usage: $0 signup on|off" >&2; exit 2 ;; esac
    sed -i "s/^SVALKA_ALLOW_SIGNUP=.*/SVALKA_ALLOW_SIGNUP=$v/" .env
    export SVALKA_ALLOW_SIGNUP=$v
    docker compose "${PROFILE[@]}" up -d
    echo "Sign-up: $2" ;;
  backup)
    dest="${2:-./backups}"
    name="svalka-$(date +%Y-%m-%d_%H-%M).zip"
    token="$(superuser_token)"
    api -H "Authorization: $token" -d "{\"name\":\"$name\"}" "$API/api/backups" >/dev/null
    if [[ "$dest" == *:* ]]; then scp -q "pb_data/backups/$name" "$dest"
    else mkdir -p "$dest" && cp "pb_data/backups/$name" "$dest/"; fi
    echo "Backup $name -> $dest" ;;
  update)
    docker compose "${PROFILE[@]}" up -d --build ;;
  *)
    sed -n '2,10p' "$0"; exit 2 ;;
esac
