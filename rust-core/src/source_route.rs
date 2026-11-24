//! Optional Android-owned loopback CONNECT transport. TLS, credentials and HTTP
//! remain in reqwest; only the underlying TCP/DNS route changes per source.
use crate::source::SourceError;
use reqwest::{header, Client, Url};
use std::collections::{BTreeMap, VecDeque};
use std::sync::{Mutex, OnceLock, RwLock};
use std::time::{Duration, Instant};

static ROUTES: OnceLock<RwLock<BTreeMap<String, SourceNetwork>>> = OnceLock::new();

#[derive(Clone, Default, PartialEq, Eq)]
pub(crate) enum SourceNetwork {
    #[default]
    System,
    Direct(String),
    Auto(String),
}

#[uniffi::export]
pub fn configure_source_network(prefix: String, proxy: Option<String>, fallback_only: bool) -> Result<(), SourceError> {
    if prefix.is_empty() { return Err(SourceError::InvalidRequest); }
    if let Some(value) = &proxy {
        let url = Url::parse(value).map_err(|_| SourceError::InvalidRequest)?;
        if url.scheme() != "http" || url.host_str() != Some("127.0.0.1") || url.port().is_none() {
            return Err(SourceError::InvalidRequest);
        }
    }
    let mut routes = ROUTES.get_or_init(Default::default).write().map_err(|_| SourceError::Transport)?;
    match proxy {
        Some(value) => { routes.insert(prefix, if fallback_only { SourceNetwork::Auto(value) } else { SourceNetwork::Direct(value) }); }
        None => { routes.remove(&prefix); }
    }
    Ok(())
}

pub(crate) fn route(source: &str) -> SourceNetwork {
    fn lookup(source: &str) -> Option<SourceNetwork> {
        ROUTES.get()?.read().ok()?.iter().filter(|(key, _)| source.starts_with(key.as_str()))
            .max_by_key(|(key, _)| key.len()).map(|(_, value)| value.clone())
    }
    lookup(source).unwrap_or_default()
}

struct Profile { api: Client, images: Option<Client> }

#[derive(Default)]
struct Profiles {
    route: SourceNetwork,
    system: Option<Profile>,
    direct: Option<Profile>,
    recent_direct: VecDeque<(String, Instant)>,
}

pub(crate) struct SourceClients {
    headers: header::HeaderMap,
    image_agent: Option<String>,
    timeout: u32,
    manhuagui: bool,
    profiles: Mutex<Profiles>,
}

impl SourceClients {
    pub fn new(headers: header::HeaderMap, image_agent: Option<&str>, timeout: u32, manhuagui: bool) -> Self {
        Self { headers, image_agent: image_agent.map(str::to_owned), timeout, manhuagui, profiles: Mutex::new(Profiles::default()) }
    }
    pub fn has_images(&self) -> bool { self.image_agent.is_some() }
    pub fn prefer_direct(&self, route: &SourceNetwork, url: &Url) -> bool {
        let origin = url.origin().ascii_serialization();
        self.profiles.lock().is_ok_and(|profiles| profiles.route == *route &&
            profiles.recent_direct.iter().any(|(key, until)| *key == origin && *until > Instant::now()))
    }

    pub fn remember_direct(&self, route: &SourceNetwork, url: &Url, direct: bool) {
        let origin = url.origin().ascii_serialization();
        if let Ok(mut profiles) = self.profiles.lock() {
            if profiles.route != *route { return; }
            profiles.recent_direct.retain(|(key, _)| *key != origin);
            if direct {
                if profiles.recent_direct.len() == 8 { profiles.recent_direct.pop_front(); }
                profiles.recent_direct.push_back((origin, Instant::now() + Duration::from_secs(60)));
            }
        }
    }
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
    pub fn client(&self, route: &SourceNetwork, direct: bool, anonymous: bool) -> Result<Client, SourceError> {
        let proxy = if direct {
            match route {
                SourceNetwork::Direct(proxy) | SourceNetwork::Auto(proxy) => Some(proxy.as_str()),
                SourceNetwork::System => return Err(SourceError::InvalidRequest),
            }
        } else { None };
        let mut profiles = self.profiles.lock().map_err(|_| SourceError::Transport)?;
        if profiles.route != *route {
            *profiles = Profiles { route: route.clone(), ..Profiles::default() };
        }
        let slot = if direct { &mut profiles.direct } else { &mut profiles.system };
        if slot.is_none() {
            let api = self.build(self.headers.clone(), "FluxManga/0.0.1", proxy)?;
            let images = self.image_agent.as_ref().map(|agent| self.build(header::HeaderMap::new(), agent, proxy)).transpose()?;
            *slot = Some(Profile { api, images });
        }
        let profile = slot.as_ref().ok_or(SourceError::Transport)?;
        let client = if anonymous { profile.images.as_ref().ok_or(SourceError::InvalidRequest)? } else { &profile.api };
        Ok(client.clone())
    }
}
