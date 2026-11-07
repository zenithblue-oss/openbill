# OpenBill Server setup

The server is optional: every app works without it. It keeps your devices in sync and holds
a copy of the books. Design: [10-server.md](10-server.md). Status: milestone S1a (server,
accounts, HTTP sync). Pairing devices from the apps arrives in S1b, so for now this is for
trying the server out and for operators preparing an install.

Pick one:

| Where | How | HTTPS |
| --- | --- | --- |
| This PC only | Run the JAR | Not needed (localhost) |
| Shop LAN (PC, Raspberry Pi) | Install script or Docker | Self-signed certificate, pinned by devices |
| VPS with a domain | Docker Compose with Caddy | Let's Encrypt, automatic |

Requirements: Linux, macOS or Windows with Java 17+ for the JAR; the runtime tarball and
Docker image bring their own Java. Memory: about 200-300 MB (Raspberry Pi 4/5 with 2 GB or a
1 GB VPS). Data lives in one directory; back it up (server backups arrive in S1c).

## First admin

On first start, while no admin exists, the server prints a one-time setup command with a
setup token (`obst_...`), for example:

```sh
curl -X POST http://127.0.0.1:8090/api/v1/setup -H 'Content-Type: application/json' \
  -d '{"setupToken":"obst_...","username":"admin","password":"<at least 10 characters>"}'
```

Or create the admin from the shell (password typed twice, or from `OPENBILL_ADMIN_PASSWORD`):
`openbill-server admin create admin`. The token stops working once an admin exists, and a
new one is printed on each start until then.

Then sign in and create a workspace (one per business or group of firms):

```sh
curl -X POST http://127.0.0.1:8090/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"..."}'                      # accessToken, refreshToken
curl -X POST http://127.0.0.1:8090/api/v1/workspaces -H "Authorization: Bearer <accessToken>" \
  -H 'Content-Type: application/json' -d '{"name":"Sharma Traders"}'
```

## On this PC (localhost)

```sh
java -jar openbill-server-<version>.jar            # data in ./openbill-data, http://127.0.0.1:8090
OPENBILL_DATA_DIR=$HOME/openbill-server java -jar openbill-server-<version>.jar
```

`GET /healthz` answers `ok`; `GET /api/v1/version` shows the version and whether setup is
still needed.

## Shop LAN (PC or Raspberry Pi)

Read the script, then run it as root. It downloads the release over HTTPS, checks every file
against the release's `SHA256SUMS` and refuses on mismatch, creates the `openbill` user and
`/var/lib/openbill` (mode 0700), installs a hardened systemd service with its own Java
runtime (x64 and arm64), starts it, and prints the setup command and the certificate
fingerprint:

```sh
curl -fsSLO https://github.com/zenithblue-oss/openbill/releases/latest/download/install-server.sh
less install-server.sh
sudo bash install-server.sh            # or: --version v1.2.0, --docker
```

It listens on port 8090 on every interface with a self-signed certificate (created once, kept
in `/var/lib/openbill/keys`, so its fingerprint stays the same). Devices will pin that
fingerprint when they pair (S1b); browsers and curl need `-k` until then. Settings:
`/etc/openbill/server.env`; logs: `journalctl -u openbill-server`.

If other devices cannot connect, the firewall may block the port. On a distribution with
firewalld: `sudo firewall-cmd --add-port=8090/tcp --permanent && sudo firewall-cmd --reload`;
with ufw: `sudo ufw allow 8090/tcp`. Allow it on your LAN only, not from the internet.

Without the script (any OS with Java 17+): run the JAR with `OPENBILL_SERVER_BIND=0.0.0.0` and
`OPENBILL_TLS_MODE=self_signed`.

## VPS with a domain (Docker Compose and Caddy)

Point the domain's DNS at the VPS, open ports 80 and 443, then in a directory with
`docker-compose.yml` and `Caddyfile` (release files, or `server/deploy` in the repository)
create `.env`:

```sh
OPENBILL_DOMAIN=billing.example.com
OPENBILL_TLS_MODE=off
OPENBILL_SERVER_ALLOW_INSECURE_HTTP=true
OPENBILL_SERVER_TRUST_FORWARDED_FOR=true
OPENBILL_PUBLISH=127.0.0.1:8090
```

and run `docker compose --profile https up -d`. Caddy gets and renews the certificate; the
server speaks plain HTTP only on the private Compose network and its port is published on
localhost only. `docker compose logs openbill` shows the setup command (use
`https://billing.example.com` in it). Without `.env`, `docker compose up -d` runs the LAN
setup (self-signed HTTPS on 8090).

The image (`ghcr.io/zenithblue-oss/openbill-server`, linux/amd64 and linux/arm64) runs as an
unprivileged user with a read-only root file system; data is in the `openbill-data` volume.
If the image is not published for a release, build it from the repository after
`./gradlew :server:fatJar`: `docker build -f server/deploy/Dockerfile -t openbill-server .`
and set `OPENBILL_IMAGE=openbill-server` in `.env`.

## Raspberry Pi

Use Raspberry Pi OS (64-bit) or Ubuntu Server arm64 and the install script above (arm64
runtime), or Docker. A 32-bit OS is not supported by the runtime tarball; there, install Java
17+ and run the JAR. Prefer an SSD over an SD card for the data directory.

## Settings

`<data-dir>/server.toml` (or the file in `OPENBILL_CONFIG`), every key also settable as
`OPENBILL_<SECTION>_<KEY>` (environment wins). `OPENBILL_DATA_DIR` sets the data directory.
Unknown keys are an error, so typos do not pass silently.

```toml
[server]
bind = "127.0.0.1"            # 0.0.0.0 for every interface
port = 8090
allow_insecure_http = false   # plain HTTP off localhost is refused unless true (then warned on every start)
trust_forwarded_for = false   # only behind your own reverse proxy

[tls]
mode = "off"                  # off | self_signed | files
cert_file = "/etc/letsencrypt/live/example.com/fullchain.pem"   # files mode
key_file = "/etc/letsencrypt/live/example.com/privkey.pem"      # PKCS#8 PEM
hosts = "billing.lan, 192.168.1.10"   # extra names for the self-signed certificate

[limits]
login_attempts_per_minute = 5
max_push_ops = 500
max_request_mb = 10
max_seed_mb = 512
access_token_minutes = 15
refresh_token_days = 90
```

Data directory: `instance.sqlite` (accounts, hashed tokens, workspaces, audit),
`workspaces/<id>/projection.sqlite` and `oplog.sqlite`, `keys/` (self-signed certificate,
mode 0600). Commands: `openbill-server serve | version | admin create <name> | audit verify`.

## Security notes

- Passwords are hashed (PBKDF2-SHA256, 600,000 iterations); tokens are stored only as hashes
  and never logged; logs carry method, path, status and time only.
- Five failed sign-ins in a minute lock the account and the address for a minute, doubling up
  to 64 minutes.
- Plain HTTP is refused except on localhost or with `allow_insecure_http`; there is no CORS.
- Keep the data directory private (the server creates it with mode 0700) and back it up.
