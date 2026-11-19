#!/usr/bin/env bash
# Installs or updates the СВАЛКА sync server, in Docker or as a plain systemd service.
#
#   sudo ./install.sh --domain sync.example.com --admin-email me@example.com [--mode docker|native] [--caddy] [--signup] [--port 8090] [--dir /opt/svalka-sync]
#
# --mode native runs PocketBase as a systemd service without Docker (about 30 MB of RAM); default: docker if installed, else native.
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
MODE=""
PB_VERSION="${PB_VERSION:-0.40.6}"

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
    --mode) MODE="$2"; shift 2 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$MODE" ]]; then command -v docker >/dev/null && MODE=docker || MODE=native; fi
case "$MODE" in docker|native) ;; *) echo "--mode must be docker or native" >&2; exit 2 ;; esac
if [[ "$CADDY" == true && -z "$DOMAIN" ]]; then echo "--caddy needs --domain" >&2; exit 2; fi
if [[ "$CADDY" == true && "$MODE" == native ]]; then echo "--caddy works with --mode docker; in native mode add a site to your own Caddy/nginx (see README)" >&2; exit 2; fi
if [[ "$MODE" == docker ]]; then
  command -v docker >/dev/null || { echo "Docker is not installed. Install it first: https://docs.docker.com/engine/install/" >&2; exit 1; }
  docker compose version >/dev/null 2>&1 || { echo "Docker Compose plugin is missing (docker compose)." >&2; exit 1; }
else
  command -v systemctl >/dev/null || { echo "Native mode needs systemd." >&2; exit 1; }
  command -v curl >/dev/null && command -v unzip >/dev/null || { echo "Native mode needs curl and unzip (apt install curl unzip)." >&2; exit 1; }
fi

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p "$DIR"
if [[ "$SRC" != "$DIR" ]]; then
  cp -r "$SRC/Dockerfile" "$SRC/docker-compose.yml" "$SRC/Caddyfile" "$SRC/svalka-sync.sh" "$DIR/"
  rm -rf "$DIR/pb_migrations" "$DIR/pb_hooks"
  cp -r "$SRC/pb_migrations" "$SRC/pb_hooks" "$DIR/"
fi
chmod +x "$DIR/svalka-sync.sh"
cd "$DIR"

cat > .env <<ENV
SVALKA_MODE=$MODE
SVALKA_DOMAIN=${DOMAIN:-localhost}
SVALKA_PORT=$PORT
SVALKA_BIND=$BIND
SVALKA_ALLOW_SIGNUP=$SIGNUP
SVALKA_CADDY=$CADDY
PB_VERSION=$PB_VERSION
TZ=${TZ:-Europe/Moscow}
ENV

if [[ "$MODE" == docker ]]; then
  PROFILE=()
  [[ "$CADDY" == true ]] && PROFILE=(--profile caddy)
  BUILD=(--build)
  [[ -n "${SVALKA_NO_BUILD:-}" ]] && BUILD=(--no-build)  # use an already built image
  docker compose "${PROFILE[@]}" up -d "${BUILD[@]}"
  PB=(docker compose exec -T pocketbase /pb/pocketbase)
  PB_DATA=/pb/pb_data
else
  # PocketBase binary, checked against the release checksums.
  if [[ ! -x pocketbase ]] || ! ./pocketbase --version 2>/dev/null | grep -q "$PB_VERSION"; then
    case "$(uname -m)" in x86_64) ARCH=amd64 ;; aarch64|arm64) ARCH=arm64 ;; *) echo "Unsupported CPU $(uname -m)" >&2; exit 1 ;; esac
    ZIP="pocketbase_${PB_VERSION}_linux_${ARCH}.zip"
    URL="https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}"
    TMP="$(mktemp -d)"
    curl -fsSL -o "$TMP/$ZIP" "$URL/$ZIP"
    curl -fsSL -o "$TMP/checksums.txt" "$URL/checksums.txt"
    (cd "$TMP" && grep " $ZIP\$" checksums.txt | sha256sum -c --quiet -)
    unzip -o -q "$TMP/$ZIP" pocketbase -d "$DIR"
    chmod +x pocketbase
    rm -rf "$TMP"
  fi
  id svalka >/dev/null 2>&1 || useradd --system --home-dir "$DIR" --shell /usr/sbin/nologin svalka
  mkdir -p pb_data
  chown -R svalka:svalka pb_data
  cat > /etc/systemd/system/svalka-sync.service <<UNIT
[Unit]
Description=СВАЛКА sync server (PocketBase)
After=network-online.target
Wants=network-online.target

[Service]
User=svalka
Group=svalka
WorkingDirectory=$DIR
EnvironmentFile=$DIR/.env
ExecStart=$DIR/pocketbase serve --http=$BIND:$PORT --dir=$DIR/pb_data --migrationsDir=$DIR/pb_migrations --hooksDir=$DIR/pb_hooks
Restart=always
RestartSec=3
MemoryMax=200M
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=$DIR/pb_data
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload
  systemctl enable svalka-sync >/dev/null 2>&1
  systemctl restart svalka-sync
  PB=(sudo -u svalka "$DIR/pocketbase")
  command -v sudo >/dev/null || PB=(runuser -u svalka -- "$DIR/pocketbase")
  PB_DATA="$DIR/pb_data"
fi

for _ in $(seq 1 30); do
  curl -fsS "http://127.0.0.1:$PORT/api/health" >/dev/null 2>&1 && break
  sleep 1
done

if [[ -n "$ADMIN_EMAIL" ]]; then
  if [[ -z "$ADMIN_PASSWORD" ]]; then ADMIN_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20)"; NEW_PASSWORD=1; fi
  "${PB[@]}" superuser upsert "$ADMIN_EMAIL" "$ADMIN_PASSWORD" --dir="$PB_DATA" >/dev/null
fi

echo "СВАЛКА sync is running ($MODE)."
[[ -n "$DOMAIN" ]] && echo "  Address for the apps: https://$DOMAIN"
echo "  Local:               http://127.0.0.1:$PORT"
echo "  Sign-up open:        $SIGNUP   (change: $DIR/svalka-sync.sh signup on|off)"
if [[ -n "${NEW_PASSWORD:-}" ]]; then
  if [[ -n "$DOMAIN" ]]; then echo "  Admin panel:         https://$DOMAIN/_/"; else echo "  Admin panel:         http://127.0.0.1:$PORT/_/"; fi
  echo "  Admin login:         $ADMIN_EMAIL"
  echo "  Admin password:      $ADMIN_PASSWORD   (shown once, save it)"
fi
