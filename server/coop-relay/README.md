# PokeDaisy co-op relay

A small WebSocket relay for the co-op link. Two phones open an outgoing `wss://` connection to the same room
(`/v1/rooms/<32 lowercase hex characters>`); the relay pairs them and forwards their messages unchanged. The phones
encrypt end to end, so the relay sees ciphertext and sizes only. It logs counts and close codes, never payloads, room
ids or full IP addresses (IPv4 is cut to a /24, IPv6 to a /48).

## Protocol

Binary messages only; byte 0 is the kind.

- Client to server: `0x01` DATA + bytes (sent on unchanged to the other peer as `0x01` + the same bytes).
- Server to client: `0x01` DATA (forwarded), `0x10` PEER_JOINED (to both when the room reaches two), `0x11` PEER_LEFT.
- `GET /healthz` on the health port answers `200 ok`.
- Close codes: 4400 bad request (text message, unknown kind), 4408 expired / idle, 4409 room full,
  4413 message too large, 4429 rate or limit. A bad path (4400) and a per-IP limit (4429) are refused in the HTTP
  handshake, so a client sees a failed connect (1006), not a close code.

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

A small always-on VPS (Hetzner CX22, or a Fly.io machine). Not Cloud Run: it bills an instance for every open
WebSocket. The relay needs about 100 MB of memory.

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
