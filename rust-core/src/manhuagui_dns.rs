//! Source-local route for the observed Manhuagui DNS failure (2026-11-11).
//! Its public www.mhgui.com alias resolves to the working origin on the affected
//! network. Only the socket address changes: HTTPS still verifies the requested
//! manhuagui.com hostname, and Host/SNI and source identity remain unchanged.
use reqwest::dns::{Addrs, Name, Resolve, Resolving};
use std::time::Duration;

pub(crate) struct ManhuaguiDns;

impl Resolve for ManhuaguiDns {
    fn resolve(&self, name: Name) -> Resolving {
        let host = name.as_str().to_owned();
        Box::pin(async move {
            if matches!(host.as_str(), "www.manhuagui.com" | "tw.manhuagui.com") {
                if let Ok(Ok(addresses)) = tokio::time::timeout(
                    Duration::from_secs(3),
                    tokio::net::lookup_host(("www.mhgui.com", 0)),
                ).await {
                    let addresses: Vec<_> = addresses.collect();
                    // A proxy's Fake-IP identifies the alias, not a real server.
                    // Keep the original domain's mapping instead of substituting
                    // another name's 198.18.0.0/15 address underneath its TLS SNI.
                    let proxy_mapping = addresses.iter().any(|address| match address.ip() {
                        std::net::IpAddr::V4(ip) => {
                            let octets = ip.octets();
                            octets[0] == 198 && (octets[1] == 18 || octets[1] == 19)
                        }
                        std::net::IpAddr::V6(_) => false,
                    });
                    if !addresses.is_empty() && !proxy_mapping {
                        return Ok(Box::new(addresses.into_iter()) as Addrs);
                    }
                }
            }
            // CDN hosts and failures to resolve the alias retain system DNS.
            let addresses: Vec<_> = tokio::net::lookup_host((host.as_str(), 0)).await?.collect();
            Ok(Box::new(addresses.into_iter()) as Addrs)
        })
    }
}
