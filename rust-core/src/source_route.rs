//! Optional Android-owned loopback CONNECT transport. TLS, credentials and HTTP
//! remain in reqwest; only the underlying TCP/DNS route changes per source.
use crate::source::SourceError;
use reqwest::{header, Client, Url};
use std::collections::BTreeMap;
use std::sync::{Mutex, OnceLock, RwLock};
use std::time::Duration;

static ROUTES: OnceLock<RwLock<BTreeMap<String, String>>> = OnceLock::new();

#[uniffi::export]
pub fn configure_source_network(prefix: String, proxy: Option<String>) -> Result<(), SourceError> {
    if prefix.is_empty() { return Err(SourceError::InvalidRequest); }
    if let Some(value) = &proxy {
        let url = Url::parse(value).map_err(|_| SourceError::InvalidRequest)?;
        if url.scheme() != "http" || url.host_str() != Some("127.0.0.1") || url.port().is_none() {
            return Err(SourceError::InvalidRequest);
        }
    }
    let mut routes = ROUTES.get_or_init(Default::default).write().map_err(|_| SourceError::Transport)?;
    if let Some(value) = proxy { routes.insert(prefix, value); } else { routes.remove(&prefix); }
    Ok(())
}

fn route(source: &str) -> Option<String> {
    ROUTES.get()?.read().ok()?.iter().filter(|(key, _)| source.starts_with(key.as_str()))
        .max_by_key(|(key, _)| key.len()).map(|(_, value)| value.clone())
}

struct Profile { route: Option<String>, api: Client, images: Option<Client> }

pub(crate) struct SourceClients {
    headers: header::HeaderMap,
    image_agent: Option<String>,
    timeout: u32,
    manhuagui: bool,
    profile: Mutex<Option<Profile>>,
}

impl SourceClients {
    pub fn new(headers: header::HeaderMap, image_agent: Option<&str>, timeout: u32, manhuagui: bool) -> Self {
        Self { headers, image_agent: image_agent.map(str::to_owned), timeout, manhuagui, profile: Mutex::new(None) }
    }
    pub fn has_images(&self) -> bool { self.image_agent.is_some() }
    fn build(&self, headers: header::HeaderMap, agent: &str, proxy: Option<&str>) -> Result<Client, SourceError> {
        let mut builder = Client::builder().use_rustls_tls().no_proxy()
            .redirect(reqwest::redirect::Policy::none()).retry(reqwest::retry::never())
            .default_headers(headers).connect_timeout(Duration::from_millis(self.timeout.into())).user_agent(agent);
        if let Some(proxy) = proxy {
            builder = builder.proxy(reqwest::Proxy::all(proxy).map_err(|_| SourceError::Transport)?);
        } else if self.manhuagui {
            builder = builder.dns_resolver(std::sync::Arc::new(crate::manhuagui_dns::ManhuaguiDns));
        }
        builder.build().map_err(|_| SourceError::Transport)
    }
    pub fn client(&self, source: &str, anonymous: bool) -> Result<(Client, bool), SourceError> {
        let route = route(source);
        let mut profile = self.profile.lock().map_err(|_| SourceError::Transport)?;
        if profile.as_ref().is_none_or(|value| value.route != route) {
            let api = self.build(self.headers.clone(), "FluxManga/0.0.1", route.as_deref())?;
            let images = self.image_agent.as_ref().map(|agent| self.build(header::HeaderMap::new(), agent, route.as_deref())).transpose()?;
            // Switching routes drops the old idle pool. In-flight operations retain
            // their existing client and cancellation/session ownership.
            *profile = Some(Profile { route: route.clone(), api, images });
        }
        let value = profile.as_ref().ok_or(SourceError::Transport)?;
        let client = if anonymous { value.images.as_ref().ok_or(SourceError::InvalidRequest)? } else { &value.api };
        Ok((client.clone(), route.is_some()))
    }
}
