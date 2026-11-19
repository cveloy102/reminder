#!/usr/bin/env bash
# Installs or updates the СВАЛКА sync server with Docker.
#
#   sudo ./install.sh --domain sync.example.com --admin-email me@example.com [--caddy] [--signup] [--port 8090] [--dir /opt/svalka-sync]
#
# Non-interactive, safe to run again (keeps data). Prints the admin password once on first install.
# Meant to be callable from other tools (for example a server panel): exit code 0 = success.
set -euo pipefail

DIR=/opt/svalka-sync
DOMAIN=""
ADMIN_EMAIL=""
ADMIN_PASSWORD=""
PORT=8090
BIND=127.0.0.1
SIGNUP=false
CADDY=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --domain) DOMAIN="$2"; shift 2 ;;
    --admin-email) ADMIN_EMAIL="$2"; shift 2 ;;
    --admin-password) ADMIN_PASSWORD="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --public) BIND=0.0.0.0; shift ;;
    --signup) SIGNUP=true; shift ;;
    --caddy) CADDY=true; shift ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

command -v docker >/dev/null || { echo "Docker is not installed. Install it first: https://docs.docker.com/engine/install/" >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose plugin is missing (docker compose)." >&2; exit 1; }
if [[ "$CADDY" == true && -z "$DOMAIN" ]]; then echo "--caddy needs --domain" >&2; exit 2; fi

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$DIR"
if [[ "$SRC" != "$DIR" ]]; then
  cp -r "$SRC/Dockerfile" "$SRC/docker-compose.yml" "$SRC/Caddyfile" "$SRC/pb_migrations" "$SRC/pb_hooks" "$SRC/svalka-sync.sh" "$DIR/"
fi
chmod +x "$DIR/svalka-sync.sh"
cd "$DIR"

cat > .env <<ENV
SVALKA_DOMAIN=${DOMAIN:-localhost}
SVALKA_PORT=$PORT
SVALKA_BIND=$BIND
SVALKA_ALLOW_SIGNUP=$SIGNUP
SVALKA_CADDY=$CADDY
ENV

PROFILE=()
[[ "$CADDY" == true ]] && PROFILE=(--profile caddy)
BUILD=(--build)
[[ -n "${SVALKA_NO_BUILD:-}" ]] && BUILD=(--no-build)  # use an already built image
docker compose "${PROFILE[@]}" up -d "${BUILD[@]}"

for _ in $(seq 1 30); do
  docker compose exec -T pocketbase wget -qO- http://127.0.0.1:8090/api/health >/dev/null 2>&1 && break
  sleep 1
done

if [[ -n "$ADMIN_EMAIL" ]]; then
  if [[ -z "$ADMIN_PASSWORD" ]]; then ADMIN_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20)"; NEW_PASSWORD=1; fi
  docker compose exec -T pocketbase /pb/pocketbase superuser upsert "$ADMIN_EMAIL" "$ADMIN_PASSWORD" --dir=/pb/pb_data >/dev/null
fi

echo "СВАЛКА sync is running."
[[ -n "$DOMAIN" ]] && echo "  Address for the apps: https://$DOMAIN"
echo "  Local:               http://127.0.0.1:$PORT"
echo "  Sign-up open:        $SIGNUP   (change: $DIR/svalka-sync.sh signup on|off)"
if [[ -n "${NEW_PASSWORD:-}" ]]; then
  if [[ -n "$DOMAIN" ]]; then echo "  Admin panel:         https://$DOMAIN/_/"; else echo "  Admin panel:         http://127.0.0.1:$PORT/_/"; fi
  echo "  Admin login:         $ADMIN_EMAIL"
  echo "  Admin password:      $ADMIN_PASSWORD   (shown once, save it)"
fi
