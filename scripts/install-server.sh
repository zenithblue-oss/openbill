#!/usr/bin/env bash
# OpenBill Server installer for Linux (docs/server-setup.md). Run as root, after reading it:
#   curl -fsSLO https://github.com/zenithblue-oss/openbill/releases/latest/download/install-server.sh
#   less install-server.sh && sudo bash install-server.sh            # systemd service, bundled Java
#   sudo bash install-server.sh --docker                              # Docker Compose instead
# Options: --version vX.Y.Z (default: latest release), --docker.
#
# What it does: downloads the release files over HTTPS, checks each against the release's
# SHA256SUMS (refusing anything that does not match), creates the `openbill` system user and
# /var/lib/openbill (mode 0700), installs and starts the service, and prints the one-time admin
# setup command. It never pipes downloaded content into a shell.
set -euo pipefail

REPO=${OPENBILL_REPO:-zenithblue-oss/openbill}
version=""
mode=native
while [ $# -gt 0 ]; do
    case "$1" in
        --version) version=${2:?--version needs a tag like v1.2.0}; shift 2 ;;
        --docker) mode=docker; shift ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *) echo "Unknown option $1" >&2; exit 2 ;;
    esac
done

die() { echo "install-server: $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || die "run as root (sudo bash $0)"
for tool in curl sha256sum tar; do command -v "$tool" >/dev/null || die "$tool is required"; done

if [ -z "$version" ]; then
    version=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n1)
    [ -n "$version" ] || die "could not find the latest release; pass --version"
fi
case "$version" in v[0-9]*) ;; *) die "bad version '$version'" ;; esac
base="https://github.com/$REPO/releases/download/$version"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Downloads a release file and checks it against SHA256SUMS; aborts on any mismatch or absence.
fetch() {
    curl -fsSL --proto '=https' --tlsv1.2 -o "$work/$1" "$base/$1" || die "download failed: $1"
    grep -E "^[0-9a-f]{64}  $1\$" "$work/SHA256SUMS" > "$work/$1.sum" || die "$1 is not listed in SHA256SUMS"
    (cd "$work" && sha256sum --quiet -c "$1.sum") || die "checksum mismatch for $1: refusing to install"
}
curl -fsSL --proto '=https' --tlsv1.2 -o "$work/SHA256SUMS" "$base/SHA256SUMS" || die "download failed: SHA256SUMS"

if ! id openbill >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/openbill --shell /usr/sbin/nologin --user-group openbill
fi
install -d -m 0700 -o openbill -g openbill /var/lib/openbill

if [ "$mode" = docker ]; then
    command -v docker >/dev/null || die "docker is required for --docker"
    docker compose version >/dev/null 2>&1 || die "the docker compose plugin is required"
    fetch docker-compose.yml
    fetch Caddyfile
    install -d -m 0755 /opt/openbill-server
    install -m 0644 "$work/docker-compose.yml" "$work/Caddyfile" /opt/openbill-server/
    [ -f /opt/openbill-server/.env ] || echo "OPENBILL_IMAGE=ghcr.io/${REPO%%/*}/openbill-server:${version#v}" > /opt/openbill-server/.env
    (cd /opt/openbill-server && docker compose up -d)
    echo "Waiting for the server..."
    sleep 15
    (cd /opt/openbill-server && docker compose logs openbill) | grep -E "fingerprint|setupToken|openbill-server admin" || true
    echo "Installed with Docker in /opt/openbill-server (data in the openbill-data volume)."
    exit 0
fi

case "$(uname -m)" in
    x86_64|amd64) arch=x64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) die "no runtime build for $(uname -m); run the JAR with Java 17+ instead (docs/server-setup.md)" ;;
esac
command -v systemctl >/dev/null || die "systemd is required (or run bin/openbill-server yourself)"

name="openbill-server-${version#v}-linux-${arch}"
fetch "$name.tar.gz"
tar -C "$work" -xzf "$work/$name.tar.gz"
[ -x "$work/$name/bin/openbill-server" ] || die "unexpected archive layout"
rm -rf "/opt/$name"
mv "$work/$name" "/opt/$name"
chown -R root:root "/opt/$name"
ln -sfn "/opt/$name" /opt/openbill-server

install -d -m 0755 /etc/openbill
if [ ! -f /etc/openbill/server.env ]; then
    # LAN default: HTTPS with a self-signed certificate on every interface.
    printf '%s\n' "OPENBILL_SERVER_BIND=0.0.0.0" "OPENBILL_SERVER_PORT=8090" "OPENBILL_TLS_MODE=self_signed" > /etc/openbill/server.env
    chmod 0644 /etc/openbill/server.env
fi
install -m 0644 "/opt/$name/deploy/openbill-server.service" /etc/systemd/system/openbill-server.service
systemctl daemon-reload
systemctl enable openbill-server >/dev/null
systemctl restart openbill-server

echo "Waiting for the server..."
for _ in $(seq 1 30); do
    if curl -fsk https://127.0.0.1:8090/healthz >/dev/null 2>&1 || curl -fs http://127.0.0.1:8090/healthz >/dev/null 2>&1; then break; fi
    sleep 1
done
systemctl --no-pager --lines=0 status openbill-server || true
journalctl -u openbill-server --no-pager -n 50 | grep -E "fingerprint|setupToken|openbill-server admin" || true
echo "Installed $version in /opt/$name; data in /var/lib/openbill; settings in /etc/openbill/server.env."
echo "If no setup command is shown above, an admin already exists. Admin from the shell:"
echo "  sudo -u openbill OPENBILL_DATA_DIR=/var/lib/openbill /opt/openbill-server/bin/openbill-server admin create <username>"
