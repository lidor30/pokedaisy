# PokeDaisy co-op relay

A small WebSocket relay for the co-op link. Two phones open an outgoing `wss://` connection to the same room
(`/v1/rooms/<32 lowercase hex characters>`); the relay pairs them and forwards their messages unchanged. The phones
encrypt end to end, so the relay sees ciphertext and sizes only. It logs counts and close codes, never payloads, room
ids, lobby codes, names or full IP addresses (IPv4 is cut to a /24, IPv6 to a /48).

## Protocol

Binary messages only; byte 0 is the kind.

- Client to server: `0x01` DATA + bytes (sent on unchanged to the other peer as `0x01` + the same bytes).
- Server to client: `0x01` DATA (forwarded), `0x10` PEER_JOINED (to both when the room reaches two), `0x11` PEER_LEFT.
- `GET /healthz` on the health port answers `200 ok`.
- Close codes: 4400 bad request (text message, unknown kind), 4408 expired / idle, 4409 room full,
  4413 message too large, 4429 rate or limit. A bad path (4400) and a per-IP limit (4429) are refused in the HTTP
  handshake, so a client sees a failed connect (1006), not a close code.

## Lobby (room codes and open rooms)

`/v1/lobby` on the same port is a second kind of socket: it hands a host's invite to whoever has its short code, and lists
the rooms their hosts chose to list. It never sees the connection that follows (that goes through `/v1/rooms/` as before),
and the invite is opaque to it: the app sends its invite ticket without LAN addresses.

Binary messages, byte 0 the kind, then fields in order. A string is a u8 byte count and that many UTF-8 bytes; numbers are
big-endian. Every request but UNHOST gets exactly one answer, in order.

| Kind | Name | Direction | Fields |
| --- | --- | --- | --- |
| `0x20` | HOST | to server | u8 flags (bit 0 = listed), invite, game, name: opens this socket's room or updates it (same code) |
| `0x21` | UNHOST | to server | none: the room goes (no answer) |
| `0x22` | FIND | to server | code (any case; spaces and `-` ignored) |
| `0x23` | LIST | to server | game |
| `0x30` | HOSTED | to client | code |
| `0x31` | FOUND | to client | invite |
| `0x32` | ROOMS | to client | u8 count, then per room: code, name, u16 minutes since it opened (newest first) |
| `0x3F` | ERROR | to client | u8 the request's kind, u8 reason: 1 not found, 2 bad request, 3 limit, 4 lobby full |

- A code is 6 characters of `23456789ABCDEFGHJKMNPQRSTVWXYZ`. A room lives as long as the socket that hosted it, until UNHOST,
  or `LOBBY_ROOM_TIMEOUT_SEC` after its last HOST.
- `game` is 16 lowercase hex digits; LIST returns only listed rooms of that game, never the asker's own.
- `invite` is 8 to 255 printable ASCII characters without spaces. A listed room's `name` is 1 to 16 letters (any script),
  digits, spaces and `.'!?&♂♀-`; an unlisted room's name is ignored.
- A malformed message or a text message closes the socket with 4400, one over 770 bytes with 4413; a bad field is an ERROR.
  A per-address socket limit is refused in the handshake (a failed connect).

## Limits

All settable by environment variable (defaults shown).

| Variable | Default | Meaning |
| --- | --- | --- |
| `PORT` | 8080 | WebSocket port |
| `HEALTH_PORT` | 8081 | `/healthz` port |
| `MAX_ROOMS` | 10000 | rooms at once |
| `MAX_SOCKETS_PER_IP` | 16 | open sockets per client address |
| `MAX_NEW_SOCKETS_PER_IP_PER_MIN` | 30 | new sockets per address per minute |
| `MAX_MESSAGE_BYTES` | 70000 | largest message |
| `MAX_MESSAGES_PER_SEC` | 500 | per socket (over it: closed 4429) |
| `MAX_BYTES_PER_SEC` | 524288 | per socket (over it: closed 4429) |
| `LONE_TIMEOUT_SEC` | 1800 | a lone peer waits at most this long |
| `CONNECTION_LOST_TIMEOUT_SEC` | 15 | a socket with no pong is dropped after 15-30 s, freeing its pair room for a rejoin |
| `SILENT_TIMEOUT_SEC` | 120 | a pair silent this long is closed |
| `MAX_BUFFERED_BYTES` | 1048576 | unsent data queued for one socket; over it that socket (a reader that stopped) is closed 4429 |
| `TRUST_PROXY` | none | where the client address comes from: `none`, `forwarded` or `cloudflare` (below) |
| `TRUSTED_PROXIES` | loopback + private ranges | CIDR list (comma separated); proxy headers are read only from a socket inside it |
| `CLOUDFLARE_RANGES_FILE` | `cloudflare-ips.txt` | Cloudflare's ranges, one CIDR per line (the image ships `/app/cloudflare-ips.txt`) |
| `LOBBY_MAX_ROOMS` | 10000 | lobby rooms at once (over it: ERROR full) |
| `LOBBY_MAX_SOCKETS_PER_IP` | 8 | open lobby sockets per client address |
| `LOBBY_MAX_NEW_SOCKETS_PER_IP_PER_MIN` | 30 | new lobby sockets per address per minute |
| `LOBBY_MAX_ROOMS_PER_IP` | 4 | rooms hosted per address |
| `LOBBY_MAX_HOSTS_PER_IP_PER_MIN` | 20 | HOSTs per address per minute |
| `LOBBY_MAX_FINDS_PER_IP_PER_MIN` | 10 | FINDs per address per minute, found or not: what keeps guessing codes slow |
| `LOBBY_MAX_LISTS_PER_IP_PER_MIN` | 30 | LISTs per address per minute |
| `LOBBY_MAX_LISTED` | 50 | rooms in one ROOMS answer |
| `LOBBY_ROOM_TIMEOUT_SEC` | 1800 | a room not refreshed by a HOST this long is dropped |
| `LOBBY_IDLE_TIMEOUT_SEC` | 120 | a lobby socket with no room, silent this long, is closed 4408 |

### Client address modes (`TRUST_PROXY`)

Per-address limits need the real client address. Proxy headers are read only when the socket's peer address is inside
`TRUSTED_PROXIES`; from any other peer they are ignored, whatever the mode.

| Mode | Client address | Use it |
| --- | --- | --- |
| `none` (also `0`; default) | the socket's address | the relay is reachable directly |
| `forwarded` (also `1`) | `X-Real-IP`, else the last `X-Forwarded-For` entry | behind one proxy you run (the bundled Caddy; Nginx Proxy Manager without Cloudflare) |
| `cloudflare` | as `forwarded`, then `CF-Connecting-IP` only when that address is inside Cloudflare's ranges, else that address itself | Cloudflare in front of a proxy in front of the relay |

`cloudflare` ignores a forged `CF-Connecting-IP` sent straight to the origin, because the proxy's own client address is then not Cloudflare's. Cloudflare's ranges change rarely; refresh `cloudflare-ips.txt` from https://www.cloudflare.com/ips-v4 and https://www.cloudflare.com/ips-v6 (or mount a newer file and set `CLOUDFLARE_RANGES_FILE`).


## Where to run it

A small always-on VM: a Google Cloud e2-micro (below, deployed by GitHub Actions), a Hetzner CX22, a Fly.io machine.
Not Cloud Run: it bills an instance for every open WebSocket, cuts them after an hour, and rooms live in one
process's memory. The relay needs about 100 MB of memory.

## Deploy to Google Cloud (GitHub Actions)

`deploy/gcp/` runs the relay on a Compute Engine VM (an e2-micro by default, in Google Cloud's free tier in
us-west1 / us-central1 / us-east1): the relay's image from Artifact Registry, Caddy in front of it for HTTPS, both
under Docker Compose in `/opt/coop-relay`. `.github/workflows/coop-relay.yml` tests the relay on pull requests that
touch it, and on a push to main (or a manual run) builds the image and rolls it out.

1. Once, signed in to `gcloud` as a project owner and to `gh`:

   ```sh
   PROJECT=my-project RELAY_HOST=relay.example.com server/coop-relay/deploy/gcp/setup.sh
   ```

   It makes the repository, the VM with a static address, the firewall rules (80 / 443 open, 22 from IAP only), a
   service account for the VM and one for deploys, and keyless sign-in (Workload Identity Federation) for this
   GitHub repository's `coop-relay` environment, which it creates (main only) with the variables the workflow
   reads. `REGION`, `ZONE`, `VM`, `MACHINE` and `REPOSITORY` override the defaults (`deploy/gcp/config.sh`). It is
   safe to run again.
2. Point a DNS A record for `RELAY_HOST` at the address it prints, DNS only (no Cloudflare proxy: Caddy gets the
   certificate itself and strips `CF-Connecting-IP`).
3. Deploy: run the "Co-op relay" workflow, or from this machine
   `PROJECT=my-project RELAY_HOST=relay.example.com server/coop-relay/deploy/gcp/deploy.sh` (Docker running; it
   builds for linux/amd64). It waits until `https://RELAY_HOST/healthz` says `ok`.

Every later push to main that changes `server/coop-relay/` deploys again. To roll back, re-run an older run of the
workflow: it builds and deploys that commit. Artifact Registry keeps the ten newest images. On the VM:
`gcloud compute ssh coop-relay --tunnel-through-iap`, then `cd /opt/coop-relay && sudo docker compose logs`.

## Deploy with Caddy (automatic HTTPS)

1. Point a DNS A (and AAAA) record for your domain, say `relay.example.com`, at the server.
2. Open the firewall for 22, 80 and 443 only.
3. On the server:

   ```sh
   git clone <this repository>
   cd pokedaisy/server/coop-relay
   RELAY_HOST=relay.example.com docker compose up -d --build
   ```

4. Check it: `curl https://relay.example.com/healthz` prints `ok`.
5. Give the app the URL: `coop.relayUrl=https://relay.example.com` in `local.properties`, or the `COOP_RELAY_URL`
   Actions secret for release builds.

Update with `git pull && docker compose up -d --build`.

## Behind an existing reverse proxy (e.g. Nginx Proxy Manager)

When the host already has a proxy on 80 / 443, run the relay alone, with no published ports, on a Docker network the
proxy shares.

1. Create the network once and connect the proxy container to it:

   ```sh
   docker network create my_proxy_network
   docker network connect my_proxy_network <your proxy container>
   ```

2. Start the relay on that network (the container name defaults to `coop-relay`):

   ```sh
   PROXY_NETWORK=my_proxy_network docker compose -f docker-compose.proxy.yml up -d --build
   ```

   Set `RELAY_CONTAINER` to name the container something else.
3. In the proxy add a host: `https://relay.example.com` forwards to `http://coop-relay:8080` with WebSockets
   support on. Add a custom location `/healthz` forwarding to `http://coop-relay:8081`.
4. Check it: `curl https://relay.example.com/healthz` prints `ok`, then set the app's relay URL as above.

Behind Cloudflare an idle WebSocket is cut after about 100 seconds; the app pings every 20 seconds at the WebSocket
level and the relay pings every 15 seconds, so a live connection never idles that long.

The relay trusts proxy headers only from a socket inside `TRUSTED_PROXIES` (private ranges by default). The compose
file sets `TRUST_PROXY=forwarded`; behind Cloudflare + a proxy run it with `TRUST_PROXY=cloudflare` (e.g.
`TRUST_PROXY=cloudflare PROXY_NETWORK=my_proxy_network docker compose -f docker-compose.proxy.yml up -d --build`).
The proxy in front must overwrite `X-Real-IP` with the address it sees (Nginx Proxy Manager does; the bundled Caddyfile
does), never pass a client's value through, or a client could forge its address.

Update with `git pull && docker compose -f docker-compose.proxy.yml up -d --build`.

## Running it without Docker

`./gradlew --no-daemon -p server/coop-relay installDist`, then `build/install/coop-relay/bin/coop-relay`
(Java 17). Tests: `./gradlew --no-daemon -p server/coop-relay test`.
