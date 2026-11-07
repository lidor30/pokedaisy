# Spike: iroh as a co-op transport (2026-11-01)

**Question.** Should co-op's connection (`RelayCoopTransport`: our WebSocket relay, plus direct TCP / NSD on the same Wi-Fi)
move to [iroh](https://github.com/n0-computer/iroh) (n0's QUIC library: dial a peer by its public key, punch through NATs for
a direct path, fall back to a relay)?

**Verdict: not now.** iroh works on Android, binds fast, finds direct paths and self-hosts fine. But it costs about **+8.5 MB
of APK (+33%)**, **a Rust toolchain in our build** and **a rewrite of the transport and its peer ids**. All that buys lower
latency for players who'd otherwise go through the relay. Joined battles are Emerald's turn-based link blocks and stand ~100 ms
without trouble. Revisit when one of these comes true:

- players report lag in joined battles or the partner's walking, through the relay;
- the relay's traffic or bill grows (a direct path takes most traffic off it: iroh's own estimate is ~90% of connections);
- co-op gets something real-time (both players moving in one battle, shared overworld in lockstep).

The lobby (`/v1/lobby`) treats an invite as opaque text, so ROOMS would carry an iroh address unchanged if we switch later.

## What was tried

Everything ran on the user's Mac (Apple silicon) and a headless `Pixel_3a_API_32_arm64-v8a` emulator. The Mac ran a small Rust
peer on the same iroh release, and both talked through n0's public relay (euc1, Frankfurt: fine for a test, not for players) or
a local `iroh-relay --dev`. The sources are in [`coop-iroh-spike/`](coop-iroh-spike/): `peer/` (Mac: echo, or `dial`),
`harness/` (one Android activity: bind, then dial a peer or listen, log to the screen and `irohspike`), `run.sh` / `listen.sh`.

**Not measured, and it matters most:** two devices on two different networks (home Wi-Fi vs mobile data, CGNAT). The emulator
and the Mac share one host and one router, so "direct" here proves the mechanism, not the success rate. n0 calls their direct
rate "around 90%", anecdotally; the one published measurement of a similar system (libp2p's DCUtR) found 70% ± 7.1%
([arXiv 2604.12484](https://arxiv.org/pdf/2604.12484)). Run it: install the harness on two phones, `listen.sh n0` on one,
`run.sh` from the other with its `PEER` line (the screen shows it too).

## Results

### It works on Android

`computer.iroh:iroh-android:1.2.0` (Maven Central, iroh-ffi 1.2.0 = iroh 1.4.1, published 2026-10-22) loads, and the async
calls return: the report that UniFFI-over-JNA's `Endpoint.bind` never completes on Android (Azula-App/iroh-kmp) didn't happen on
API 32. Call `IrohAndroid.installAndroidContext(context)` first (iroh's DNS reads Android's link properties through JNI).

| Step | Time |
| --- | --- |
| `Endpoint.bind` | 523 / 850 ms first launch, ~100 ms after |
| `online()` (relay connected) | 0.9 - 1.4 s on n0, 3.6 s on the local dev relay |
| `connect`, peer's addresses known | 152 - 193 ms |
| `connect`, only id + relay (what a lobby invite would give) | 177, 946, 878 ms |

### Latency (64-byte ping-pong on one bidi stream, one every 16 ms, 200 a run)

| Run | p50 | p90 | max |
| --- | --- | --- | --- |
| Emulator dials Mac, addresses known | 6.4 ms | 19.5 ms | 665 ms (start-up) |
| Emulator dials Mac, id + relay only (3 runs) | 2.0 - 3.5 ms | 3.9 - 7.9 ms | 151 - 369 ms |
| Same, our own size-tuned library | 1.9 ms | 4.7 ms | 162 ms |
| Mac dials the emulator through its NAT, id + relay only | 2.3 ms | 3.0 ms | 160 ms |
| Through the local `iroh-relay --dev` | 1.9 ms | 2.7 ms | 15 ms |

In every run the direct path was selected before the first ping (the dev relay carried exactly one). The relay path's own RTT,
as QUIC measured it alongside, was **98 - 137 ms** steady through Frankfurt (spiking to 490 - 700 ms in its first seconds).
Our WebSocket relay pays the same two legs to wherever it's hosted. iroh's gain is the direct path, not a faster relay.

### Size

| arm64 `libiroh_ffi.so` | Bytes |
| --- | --- |
| Published AAR | 12,831,624 |
| Built from source, release | 17,242,736 (12,999,080 stripped) |
| `opt-level = "s"`, `codegen-units = 1`, `strip = true` | 9,030,704 |
| `opt-level = "z"`, same | **7,980,584** |

Our v1.3.3 APK is 26.1 MB, arm64 only, its `.so` files stored uncompressed (they count in full). Adding iroh: the `.so`, JNA's
`libjnidispatch.so` (168 KB) and the Kotlin API's classes. That's **about +8.5 MB with our own opt-z build**, +13.3 MB with the
published one. **The published AAR as is adds ~11 MB more**: it pulls in the desktop JAR, which carries macOS / Windows / Linux
libraries as Java resources (`darwin-aarch64/libiroh_ffi.dylib`, `win32-x86-64/iroh_ffi.dll`, ...) that land in the APK. Exclude
them (`packaging.resources.excludes += "darwin-*/**", "win32-*/**", "linux-*/**"`). Harness APK: 25.4 MB as is, 14.3 MB
excluded, 9.4 MB with our own library.

### Idle cost

A listening endpoint, 30 s idle: 8 CPU ticks (0.08 CPU-seconds, ~0.3% of a core), native heap ~16 MB, 24 threads (tokio).

### It fits our toolchain, with work

- The published Kotlin API is compiled by **Kotlin 2.2.21 to Java 21 bytecode**. Our app is Kotlin 1.9.24 on JDK 17 (Paparazzi
  pins it), so it can't take the JAR. But the binding UniFFI generates (`iroh_ffi.kt`, 15,281 lines) **compiles as is with
  Kotlin 1.9.24, JDK 17 and our coroutines 1.8.1**: vendor that source, ship our own `.so`, and add `IrohAndroid` (one JNI
  method; it must keep the package `computer.iroh`, since the symbol is `Java_computer_iroh_IrohAndroid_installAndroidContext`).
- Generate the binding from the **unstripped** library: `strip = true` removes the `UNIFFI_META_*` symbols the generator reads.
- iroh-ffi needs **Rust 1.91+** (Homebrew's is 1.84): rustup, `rustup target add aarch64-linux-android`, the NDK's clang as the
  linker. Its `.cargo/config.toml` already sets 16 KB page alignment for Android. A release build takes 1.75 - 2.5 minutes on this
  Mac. CI would need the same, or we'd keep built `.so` files somewhere other than this public repo.
- The API covers what a transport needs: custom relay maps (`RelayMode.customFromUrls`), bidi / uni streams, datagrams,
  `Connection.paths()` (relay or direct, RTT), path-change callbacks, `Endpoint.secretKey` from our own 32 bytes.

### Self-hosting the relay

`cargo install iroh-relay@1.4.1 --features server` gives an 11.3 MB server. `iroh-relay --dev` (plain HTTP on 3340) worked as a
custom relay for both sides. In production it serves HTTPS on 443 with ACME built in, HTTP on 80, and QUIC address discovery
on **UDP 7842**, which Cloudflare's proxy doesn't carry (`server/coop-relay`'s Cloudflare setup wouldn't apply as is). Like our
relay, it can't see inside the traffic.

## If we switch: the shape of it

Not built in this spike: none of the numbers depend on it, and building it would force the decisions above first.

1. **Keys.** iroh's ids are Ed25519, ours (`CoopPeerId`) X25519. Least change: keep `CoopChannel`'s Noise IK on top of one iroh
   bidi stream, as on TCP today (iroh is then just the pipe; trust and pairing stay as they are; a 16-byte tag a message on top of QUIC's own). Cleaner later:
   iroh's TLS as the channel and `EndpointId` as the peer id, which means migrating `pairs.json`.
2. **Ticket.** `CoopTicket` v2 carrying the `EndpointId` (and the relay URL) beside the X25519 key; the lobby invite needs no
   change.
3. **Transport.** An `IrohCoopTransport` behind the same `CoopTransport` interface: `connect` = iroh connect + a HELLO, the
   pair room becomes "dial the paired peer's `EndpointId`", `poll()` fed by coroutines reading the streams. With internet,
   same Wi-Fi comes free (iroh tries the LAN addresses it learns through the relay); without it NSD would still be needed (an
   mDNS lookup in iroh-ffi wasn't checked). BY IP stays possible as a direct address.
4. **Relay.** `iroh-relay` replaces the WebSocket rooms; the lobby (`/v1/lobby`) stays ours, beside it.
5. Roughly the size of `RelayCoopTransport` (800 lines) plus its tests, plus the build: a Rust step or a fetched prebuilt library.

## Reproducing

```sh
# Rust 1.91+ with the Android target, no PATH changes (RUSTUP_HOME / CARGO_HOME anywhere):
rustup-init -y --no-modify-path --profile minimal --target aarch64-linux-android
git clone --depth 1 --branch v1.2.0 https://github.com/n0-computer/iroh-ffi.git && cd iroh-ffi
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang
export CC_aarch64_linux_android=$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER AR_aarch64_linux_android=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar
cargo build --release --lib --target aarch64-linux-android      # unstripped: for the binding
cargo run --bin uniffi-bindgen generate --language kotlin --out-dir gen --config uniffi.toml \
  --library target/aarch64-linux-android/release/libiroh_ffi.so
CARGO_PROFILE_RELEASE_OPT_LEVEL=z CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1 CARGO_PROFILE_RELEASE_STRIP=true \
  CARGO_TARGET_DIR=target-z cargo build --release --lib --target aarch64-linux-android   # the one to ship
```

The harness builds with the published AAR (`./gradlew :app:assembleRelease`, AGP 8.13 / Kotlin 2.2.21, Gradle 8.14) or, with
`-Pown`, with our library: put it at `app/own/jniLibs/arm64-v8a/libiroh_ffi.so` and the AAR's `classes.jar` (just `IrohAndroid`)
at `app/own/libs/iroh-android-classes.jar`. The peer: `cargo build --release` in `peer/`, then `iroh-peer n0 > peer.log` and
`DEVICE=<serial> ./run.sh peer.log n0 ""` (an empty last argument dials by id + relay only), or `DEVICE=<serial> ./listen.sh n0`
and `iroh-peer n0 dial <id> <relay>` from its `PEER` line.
