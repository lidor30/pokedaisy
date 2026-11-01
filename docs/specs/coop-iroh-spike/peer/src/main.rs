//! Spike peer: an iroh endpoint on the Mac that echoes every bidi stream on ALPN `pokedaisy-coop/1`.
//! `iroh-peer [n0 | none | <relay url>]`. Prints `PEER <id> <relay|-> <addr,addr,...>` once online,
//! then a `PATH` line per connection every 2 s (which path is selected, relay or direct, its RTT).
//! `iroh-peer <mode> dial <id> <relay|-> [addr,addr]` dials instead: 200 64-byte pings, then `RTT` percentiles.
use std::{str::FromStr, time::Duration};

use iroh::{
    Endpoint, RelayMode, RelayUrl,
    endpoint::{Builder, presets, presets::Preset as _},
};

const ALPN: &[u8] = b"pokedaisy-coop/1";

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let mode = std::env::args().nth(1).unwrap_or_else(|| "n0".into());
    let mut b = presets::N0.apply(Builder::empty()).alpns(vec![ALPN.to_vec()]);
    match mode.as_str() {
        "n0" => {}
        "none" => b = b.relay_mode(RelayMode::Disabled),
        url => b = b.relay_mode(RelayMode::custom([RelayUrl::from_str(url)?])),
    }
    let ep: Endpoint = b.bind().await?;
    if mode != "none" {
        tokio::time::timeout(Duration::from_secs(15), ep.online()).await.ok();
    }
    let addr = ep.addr();
    let mut relay = "-".to_string();
    let mut ips = Vec::new();
    for a in &addr.addrs {
        match a {
            iroh::TransportAddr::Relay(u) => relay = u.to_string(),
            iroh::TransportAddr::Ip(s) => ips.push(s.to_string()),
            _ => {}
        }
    }
    println!("PEER {} {} {}", ep.id(), relay, ips.join(","));
    let args: Vec<String> = std::env::args().collect();
    if args.get(2).map(|s| s.as_str()) == Some("dial") {
        let id = iroh::EndpointId::from_str(&args[3])?;
        let mut addrs = std::collections::BTreeSet::new();
        if args[4] != "-" { addrs.insert(iroh::TransportAddr::Relay(RelayUrl::from_str(&args[4])?)); }
        for a in args.get(5).map(|s| s.split(',').filter(|s| !s.is_empty()).collect::<Vec<_>>()).unwrap_or_default() {
            addrs.insert(iroh::TransportAddr::Ip(a.parse()?));
        }
        let t = std::time::Instant::now();
        let conn = ep.connect(iroh::EndpointAddr { id, addrs }, ALPN).await?;
        println!("CONNECT in {} ms", t.elapsed().as_millis());
        let (mut send, mut recv) = conn.open_bi().await?;
        let mut rtts = Vec::new();
        let mut buf = [0u8; 64];
        for i in 0..200u32 {
            buf[0] = i as u8;
            let t = std::time::Instant::now();
            send.write_all(&buf).await?;
            recv.read_exact(&mut buf).await?;
            rtts.push(t.elapsed().as_secs_f64() * 1000.0);
            tokio::time::sleep(Duration::from_millis(16)).await;
        }
        rtts.sort_by(|a, b| a.partial_cmp(b).unwrap());
        let p = |q: f64| rtts[((rtts.len() - 1) as f64 * q) as usize];
        println!("RTT n={} p50={:.1} p90={:.1} max={:.1} ms", rtts.len(), p(0.5), p(0.9), rtts[rtts.len() - 1]);
        for p in conn.paths().iter() {
            println!("PATH selected={} relay={} rtt={}ms", p.is_selected(), p.is_relay(), p.rtt().as_millis());
        }
        conn.close(0u32.into(), b"");
        ep.close().await;
        return Ok(());
    }
    while let Some(incoming) = ep.accept().await {
        tokio::spawn(async move {
            let conn = match incoming.accept() {
                Ok(a) => match a.await { Ok(c) => c, Err(e) => { eprintln!("accept: {e}"); return; } },
                Err(e) => { eprintln!("accept: {e}"); return; }
            };
            println!("CONN {}", conn.remote_id());
            let watch = conn.clone();
            tokio::spawn(async move {
                loop {
                    tokio::time::sleep(Duration::from_secs(2)).await;
                    if watch.close_reason().is_some() { break; }
                    for p in watch.paths().iter() {
                        println!("PATH selected={} relay={} rtt={}ms {:?}", p.is_selected(), p.is_relay(), p.rtt().as_millis(), p.remote_addr());
                    }
                }
            });
            while let Ok((mut send, mut recv)) = conn.accept_bi().await {
                tokio::spawn(async move {
                    let mut buf = vec![0u8; 65536];
                    loop {
                        match recv.read(&mut buf).await {
                            Ok(Some(n)) => { if send.write_all(&buf[..n]).await.is_err() { break; } }
                            _ => { let _ = send.finish(); break; }
                        }
                    }
                });
            }
            println!("CLOSED {:?}", conn.close_reason());
        });
    }
    Ok(())
}
