//! Session-scoped transport with explicit source-specific POST operations. No cookie jar, credential persistence or source parsing.
use crate::image_file::{ImageSink, SourceImageFile, MAX_IMAGE_BYTES};
use crate::source::{SourceContext, SourceError, SourceWorld};
use reqwest::{header, Client, Url};
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering::SeqCst},
    Arc,
};
use std::time::Duration;
use tokio::{
    runtime::{Builder, Runtime},
    sync::{watch, Mutex, Semaphore},
    task::JoinHandle,
    time::Instant,
};

#[derive(Clone, uniffi::Record)]
pub struct SourceHttpConfig {
    /// Includes permit wait, pacing, retries and reading the response body.
    pub timeout_ms: u32,
    pub max_in_flight: u32,
    pub min_interval_ms: u32,
    pub max_retries: u32,
    /// Small metadata/thumbnail transport; full reader streaming is a later task.
    pub max_body_bytes: u32,
}

#[derive(uniffi::Record)]
pub struct SourceHttpReply {
    pub context: SourceContext,
    pub body: Vec<u8>,
    pub content_type: Option<String>,
}

#[derive(uniffi::Record)]
pub struct GalleryLoginReply {
    pub body: Vec<u8>,
    pub cookies: Vec<String>,
}

#[derive(uniffi::Record)]
pub struct SourceHttpActivity {
    pub pending: u64,
    pub in_flight: u64,
    pub attempts: u64,
}

// Only documented source operations can construct a POST. Login is never retried.
pub(crate) struct RequestBody {
    pub bytes: Vec<u8>,
    pub content_type: &'static str,
    pub retryable: bool,
}

// API credentials stay separate from anonymous image headers and profiles.
#[derive(Default)]
struct HttpCredentials {
    authorization: Option<String>,
    cookie: Option<String>,
    api_key: Option<String>,
}

enum Output {
    Bytes(SourceHttpReply),
    Image(SourceImageFile),
}

struct State {
    context: SourceContext,
    base: Url,
    client: Client,
    anonymous_images: Option<Client>,
    config: SourceHttpConfig,
    permits: Semaphore,
    next_start: Mutex<Instant>,
    closed: watch::Sender<bool>,
    pending: Arc<AtomicU64>,
    in_flight: Arc<AtomicU64>,
    attempts: AtomicU64,
    preserve_forbidden: AtomicBool,
    jm_cookies: std::sync::Mutex<Vec<String>>,
    gallery_login_cookies: std::sync::Mutex<Vec<String>>,
}
struct Count(Arc<AtomicU64>);
impl Count {
    fn new(counter: &Arc<AtomicU64>) -> Self {
        counter.fetch_add(1, SeqCst);
        Self(counter.clone())
    }
}
impl Drop for Count {
    fn drop(&mut self) {
        self.0.fetch_sub(1, SeqCst);
    }
}
struct AbortOnDrop<T>(JoinHandle<T>);
impl<T> Drop for AbortOnDrop<T> {
    fn drop(&mut self) {
        self.0.abort();
    }
}

#[derive(uniffi::Object)]
pub struct SourceHttpSession {
    state: Arc<State>,
    runtime: Option<Runtime>,
}

fn transport_error(error: reqwest::Error) -> SourceError {
    // Do not carry reqwest errors: they can contain query strings / server URLs.
    if error.is_timeout() {
        SourceError::Timeout
    } else {
        SourceError::Transport
    }
}

fn eh_https(url: &Url) -> bool {
    url.as_str().len() <= 8192 && url.scheme() == "https" && url.port_or_known_default() == Some(443) &&
        url.username().is_empty() && url.password().is_none() && url.fragment().is_none() &&
        url.host_str().is_some_and(|h| h.contains('.') && h.parse::<std::net::IpAddr>().is_err() && !h.starts_with('[') && !h.ends_with(".local") && !h.ends_with(".localhost"))
}
fn gallery_protected_media(url: &Url, host: &str) -> bool {
    url.host_str() == Some(host) && (url.path() == "/fullimg.php" || (host == "exhentai.org" && url.path().starts_with("/t/")))
}
fn gallery_favorite_popup(url: &Url) -> bool {
    let pairs: Vec<_> = url.query_pairs().collect();
    url.path() == "/gallerypopups.php" && pairs.len() == 3 &&
        pairs.iter().any(|(k, v)| k == "act" && v == "addfav") &&
        pairs.iter().any(|(k, v)| k == "gid" && v.parse::<u64>().is_ok_and(|id| id > 0)) &&
        pairs.iter().any(|(k, v)| k == "t" && v.len() == 10 && v.bytes().all(|b| b.is_ascii_hexdigit()))
}
fn eh_media(url: &Url, host: &str) -> bool {
    eh_https(url) && match url.host_str() {
        Some(h) if h == host => gallery_protected_media(url, host) || url.path().starts_with("/img/"),
        Some(h) if h == "e-hentai.org" || h == "exhentai.org" || h.ends_with(".e-hentai.org") || h.ends_with(".exhentai.org") => false,
        Some(_) => true,
        None => false,
    }
}

fn retry_seconds(value: &str) -> Option<u32> {
    value.parse().ok().or_else(|| {
        httpdate::parse_http_date(value).ok().map(|date| {
            let duration = date
                .duration_since(std::time::SystemTime::now())
                .unwrap_or_default();
            duration
                .as_secs()
                .saturating_add(u64::from(duration.subsec_nanos() != 0))
                .min(u32::MAX.into()) as u32
        })
    })
}

// The Kotlin registry owns manga -> cover references. Recheck destination at the FFI boundary.
fn baozi_cover_url(base: &Url, raw: &str) -> Result<Url, SourceError> {
    let url = Url::parse(raw).map_err(|_| SourceError::InvalidResponse)?;
    let host = url.host_str().unwrap_or_default();
    let labels: Vec<_> = host.split('.').collect();
    let cdn = host == "static-tw.bzmgcn.com" || labels.len() == 3
        && !labels[0].is_empty()
        && labels[0]
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && matches!(labels[1], "baozimh" | "baozicdn")
        && labels[2] == "com";
    let local = |u: &Url| {
        u.scheme() == "http" && matches!(u.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"))
    };
    if raw.len() > 8192
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || !url.path().starts_with("/cover/")
        || url.path().contains('%')
        || !((url.scheme() == "https" && url.port_or_known_default() == Some(443) && cdn)
            || (local(base) && local(&url)))
    {
        return Err(SourceError::InvalidResponse);
    }
    if url.path().ends_with("/404.jpg") {
        return Err(SourceError::NotFound);
    }
    Ok(url)
}

fn baozi_page_url(base: &Url, raw: &str) -> Result<Url, SourceError> {
    let url = Url::parse(raw).map_err(|_| SourceError::InvalidResponse)?;
    let host = url.host_str().unwrap_or_default();
    let labels: Vec<_> = host.split('.').collect();
    let cdn = labels.len() == 3
        && !labels[0].is_empty()
        && labels[0]
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && ((labels[1] == "baozimh" && labels[2] == "com")
            || (labels[1] == "bzcdn" && labels[2] == "net"));
    let local = |u: &Url| {
        u.scheme() == "http" && matches!(u.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"))
    };
    if raw.len() > 8192
        || !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || !((url.scheme() == "https" && url.port_or_known_default() == Some(443) && cdn)
            || (local(base) && local(&url)))
    {
        return Err(SourceError::InvalidResponse);
    }
    Ok(url)
}

#[uniffi::export]
impl SourceHttpSession {
    #[uniffi::constructor]
    pub fn gallery_login(context: SourceContext) -> Result<Self, SourceError> {
        if context.world != SourceWorld::Nsfw || !matches!(context.source_id.as_str(), "ehentai-login" | "exhentai-login") {
            return Err(SourceError::InvalidRequest);
        }
        Self::build(context, "https://forums.e-hentai.org/".into(), None, None, None,
            SourceHttpConfig { timeout_ms: 30_000, max_in_flight: 1, min_interval_ms: 250, max_retries: 0, max_body_bytes: 4 * 1024 * 1024 })
    }

    pub async fn gallery_sign_in(&self, context: SourceContext, username: String, password: String) -> Result<GalleryLoginReply, SourceError> {
        if self.state.context.world != SourceWorld::Nsfw || !matches!(self.state.context.source_id.as_str(), "ehentai-login" | "exhentai-login") ||
            self.state.base.as_str() != "https://forums.e-hentai.org/" ||
            username.trim().is_empty() || password.is_empty() || username.len() > 1024 || password.len() > 4096 {
            return Err(SourceError::InvalidRequest);
        }
        self.state.gallery_login_cookies.lock().map_err(|_| SourceError::Transport)?.clear();
        let url = self.state.base.join("index.php?act=Login&CODE=01").map_err(|_| SourceError::InvalidRequest)?;
        let mut form = self.state.base.clone();
        form.query_pairs_mut().append_pair("UserName", &username).append_pair("PassWord", &password)
            .append_pair("submit", "Log me in").append_pair("CookieDate", "1").append_pair("temporary_https", "off");
        let body = RequestBody { bytes: form.query().unwrap_or_default().as_bytes().to_vec(), content_type: "application/x-www-form-urlencoded", retryable: false };
        let mut headers = header::HeaderMap::new();
        headers.insert(header::REFERER, header::HeaderValue::from_static("https://forums.e-hentai.org/index.php?act=Login&CODE=00"));
        headers.insert(header::ORIGIN, header::HeaderValue::from_static("https://forums.e-hentai.org"));
        headers.insert(header::USER_AGENT, header::HeaderValue::from_static("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"));
        let reply = match self.dispatch_headers(context, url, Some(body), None, false, headers).await? {
            Output::Bytes(reply) => reply, Output::Image(_) => unreachable!(),
        };
        let cookies = std::mem::take(&mut *self.state.gallery_login_cookies.lock().map_err(|_| SourceError::Transport)?);
        Ok(GalleryLoginReply { body: reply.body, cookies })
    }

    #[uniffi::constructor]
    pub fn eh(context: SourceContext) -> Result<Self, SourceError> {
        if context.world != SourceWorld::Nsfw || !context.source_id.starts_with("ehentai-") { return Err(SourceError::InvalidRequest); }
        Self::build(context, "https://e-hentai.org/".into(), None, None, None,
            SourceHttpConfig { timeout_ms: 30_000, max_in_flight: 2, min_interval_ms: 250, max_retries: 0, max_body_bytes: 32 * 1024 * 1024 })
    }

    #[uniffi::constructor]
    pub fn ex(context: SourceContext) -> Result<Self, SourceError> {
        if context.world != SourceWorld::Nsfw || !context.source_id.starts_with("exhentai-user-") { return Err(SourceError::InvalidRequest); }
        Self::build(context, "https://exhentai.org/".into(), None, None, None,
            SourceHttpConfig { timeout_ms: 30_000, max_in_flight: 2, min_interval_ms: 250, max_retries: 0, max_body_bytes: 32 * 1024 * 1024 })
    }

    pub async fn eh_read(&self, context: SourceContext, raw: String, cookie: String, image: bool) -> Result<SourceHttpReply, SourceError> {
        if self.state.context.world != SourceWorld::Nsfw || !(self.state.context.source_id.starts_with("ehentai-") || self.state.context.source_id.starts_with("exhentai-user-")) { return Err(SourceError::InvalidRequest); }
        let url = Url::parse(&raw).map_err(|_| SourceError::InvalidRequest)?;
        if !eh_https(&url) { return Err(SourceError::InvalidRequest); }
        let host = url.host_str().unwrap_or_default();
        let gallery_host = self.state.base.host_str().ok_or(SourceError::InvalidRequest)?;
        let parts: Vec<_> = url.path().trim_matches('/').split('/').collect();
        let metadata = (host == gallery_host && (matches!(url.path(), "/" | "/favorites.php") || gallery_favorite_popup(&url) ||
            matches!(parts.as_slice(), ["g", gid, token] if gid.parse::<u64>().is_ok() && token.len() == 10 && token.bytes().all(|b| b.is_ascii_hexdigit())) ||
            matches!(parts.as_slice(), ["s", token, page] if token.bytes().all(|b| b.is_ascii_hexdigit()) && page.split('-').count() == 2 && page.split('-').all(|s| s.parse::<u64>().is_ok())))) ||
            (host == "forums.e-hentai.org" && matches!(url.path(), "/" | "/index.php"));
        if (image && !eh_media(&url, gallery_host)) || (!image && !metadata) { return Err(SourceError::InvalidRequest); }
        let mut headers = header::HeaderMap::new();
        let cookie = if host == "forums.e-hentai.org" {
            cookie.split(';').map(str::trim).filter(|part| part.starts_with("ipb_member_id=") || part.starts_with("ipb_pass_hash=") || part.starts_with("nw=")).collect::<Vec<_>>().join("; ")
        } else { cookie };
        if !cookie.is_empty() && (!image || gallery_protected_media(&url, gallery_host)) {
            let mut value = header::HeaderValue::from_str(&cookie).map_err(|_| SourceError::InvalidRequest)?;
            value.set_sensitive(true); headers.insert(header::COOKIE, value);
        }
        headers.insert(header::USER_AGENT, header::HeaderValue::from_static("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"));
        headers.insert(header::REFERER, header::HeaderValue::from_str(self.state.base.as_str()).map_err(|_| SourceError::InvalidRequest)?);
        match self.dispatch_headers(context, url, None, None, false, headers).await? {
            Output::Bytes(reply) => Ok(reply), Output::Image(_) => unreachable!(),
        }
    }

    /// One explicit website favorite write; no generic POST, redirects or automatic retry.
    pub async fn eh_favorite(&self, context: SourceContext, gid: u64, token: String,
        category: i32, note: String, cookie: String) -> Result<SourceHttpReply, SourceError> {
        if self.state.context.world != SourceWorld::Nsfw ||
            !(self.state.context.source_id.starts_with("ehentai-user-") || self.state.context.source_id.starts_with("exhentai-user-")) ||
            gid == 0 || token.len() != 10 || !token.bytes().all(|b| b.is_ascii_hexdigit()) ||
            !(-1..=9).contains(&category) || note.chars().count() > 250 || cookie.is_empty() {
            return Err(SourceError::InvalidRequest);
        }
        let mut url = self.state.base.join("gallerypopups.php").map_err(|_| SourceError::InvalidRequest)?;
        url.query_pairs_mut().append_pair("gid", &gid.to_string()).append_pair("t", &token).append_pair("act", "addfav");
        let mut form = self.state.base.clone();
        form.query_pairs_mut().append_pair("favcat", &if category == -1 { "favdel".into() } else { category.to_string() })
            .append_pair("favnote", &note).append_pair("submit", "Apply Changes").append_pair("update", "1");
        let body = RequestBody { bytes: form.query().unwrap_or_default().as_bytes().to_vec(),
            content_type: "application/x-www-form-urlencoded", retryable: false };
        let mut headers = header::HeaderMap::new();
        let mut credential = header::HeaderValue::from_str(&cookie).map_err(|_| SourceError::InvalidRequest)?;
        credential.set_sensitive(true); headers.insert(header::COOKIE, credential);
        let origin = self.state.base.origin().ascii_serialization();
        for (name, value) in [(header::REFERER, url.as_str()), (header::ORIGIN, origin.as_str())] {
            headers.insert(name, header::HeaderValue::from_str(value).map_err(|_| SourceError::InvalidRequest)?);
        }
        headers.insert(header::USER_AGENT, header::HeaderValue::from_static("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"));
        match self.dispatch_headers(context, url, Some(body), None, false, headers).await? {
            Output::Bytes(reply) => Ok(reply), Output::Image(_) => unreachable!(),
        }
    }

    pub async fn eh_comment(&self, context: SourceContext, gid: u64, token: String,
        comment_id: Option<u64>, text: String, cookie: String) -> Result<SourceHttpReply, SourceError> {
        self.gallery_action_identity(gid, &token)?;
        if text.trim().is_empty() || text.chars().count() > 10_000 || comment_id == Some(0) {
            return Err(SourceError::InvalidRequest);
        }
        let mut url = self.state.base.join(&format!("g/{gid}/{token}/")).map_err(|_| SourceError::InvalidRequest)?;
        url.query_pairs_mut().append_pair("hc", "1");
        let mut form = self.state.base.clone();
        if let Some(id) = comment_id {
            form.query_pairs_mut().append_pair("commenttext_edit", &text).append_pair("edit_comment", &id.to_string());
        } else { form.query_pairs_mut().append_pair("commenttext_new", &text); }
        let body = RequestBody { bytes: form.query().unwrap_or_default().as_bytes().to_vec(),
            content_type: "application/x-www-form-urlencoded", retryable: false };
        self.gallery_action_post(context, url.clone(), url, body, cookie).await
    }

    pub async fn eh_comment_api(&self, context: SourceContext, gid: u64, token: String,
        api_uid: u64, api_key: String, comment_id: u64, method: String, vote: i32, cookie: String) -> Result<SourceHttpReply, SourceError> {
        self.gallery_action_identity(gid, &token)?;
        let account_id = api_uid.to_string();
        if api_uid == 0 || comment_id == 0 || self.state.context.source_id.rsplit('-').next() != Some(account_id.as_str()) ||
            api_key.is_empty() || api_key.len() > 256 || !api_key.bytes().all(|b| b.is_ascii_hexdigit()) ||
            !((method == "geteditcomment" && vote == 0) || (method == "votecomment" && matches!(vote, -1 | 1))) {
            return Err(SourceError::InvalidRequest);
        }
        let mut json = serde_json::json!({"method":method,"apiuid":api_uid,"apikey":api_key,"gid":gid,"token":token,"comment_id":comment_id});
        if vote != 0 { json["comment_vote"] = serde_json::json!(vote); }
        let url = self.state.base.join("api.php").map_err(|_| SourceError::InvalidRequest)?;
        let referer = self.state.base.join(&format!("g/{gid}/{token}/")).map_err(|_| SourceError::InvalidRequest)?;
        let body = RequestBody { bytes: json.to_string().into_bytes(), content_type: "application/json; charset=UTF-8", retryable: false };
        self.gallery_action_post(context, url, referer, body, cookie).await
    }

    #[uniffi::constructor]
    pub fn pica(context: SourceContext) -> Result<Self, SourceError> {
        if context.world != SourceWorld::Nsfw || !context.source_id.starts_with("picacg-") {
            return Err(SourceError::InvalidRequest);
        }
        Self::build(context, "https://picaapi.picacomic.com/".into(), None, None, None,
            SourceHttpConfig { timeout_ms: 30_000, max_in_flight: 2, min_interval_ms: 150, max_retries: 0, max_body_bytes: 32 * 1024 * 1024 })
    }

    pub async fn pica_request(&self, context: SourceContext, path: String, token: String,
        time: String, nonce: String, signature: String, body: Option<String>) -> Result<SourceHttpReply, SourceError> {
        if self.state.context.world != SourceWorld::Nsfw || !self.state.context.source_id.starts_with("picacg-") {
            return Err(SourceError::InvalidRequest);
        }
        let url = self.state.base.join(&path).map_err(|_| SourceError::InvalidRequest)?;
        let parts: Vec<_> = url.path_segments().ok_or(SourceError::InvalidRequest)?.collect();
        let id = |s: &str| s.len() == 24 && s.bytes().all(|b| b.is_ascii_hexdigit());
        let allowed = match parts.as_slice() {
            ["auth", "sign-in"] | ["comics", "advanced-search"] => body.is_some(),
            ["users", "profile" | "favourite"] | ["categories"] | ["collections"] | ["comics"] | ["comics", "leaderboard"] => body.is_none(),
            ["comics", comic] | ["comics", comic, "eps"] => id(comic) && body.is_none(),
            ["comics", comic, "order", order, "pages"] => id(comic) && order.parse::<u32>().is_ok_and(|n| n > 0) && body.is_none(),
            _ => false,
        };
        if !allowed || url.origin() != self.state.base.origin() || !url.username().is_empty() || url.password().is_some() || url.fragment().is_some()
            || body.as_ref().is_some_and(|b| b.len() > 16384) { return Err(SourceError::InvalidRequest); }
        let mut headers = header::HeaderMap::new();
        for (name, value) in [("authorization", token), ("time", time), ("nonce", nonce), ("signature", signature)] {
            if !value.is_empty() {
                let mut value = header::HeaderValue::from_str(&value).map_err(|_| SourceError::InvalidRequest)?;
                value.set_sensitive(true); headers.insert(header::HeaderName::from_static(name), value);
            }
        }
        for (name, value) in [("api-key", "C69BAF41DA5ABD1FFEDC6D2FEA56B"), ("app-channel", "1"),
            ("app-version", "2.2.1.3.3.4"), ("app-uuid", "defaultUuid"), ("app-platform", "android"),
            ("app-build-version", "44"), ("user-agent", "okhttp/3.8.1"), ("accept", "application/vnd.picacomic.com.v1+json"),
            ("image-quality", "original"), ("content-type", "application/json; charset=UTF-8")] {
            headers.insert(header::HeaderName::from_static(name), header::HeaderValue::from_static(value));
        }
        let body = body.map(|s| RequestBody { bytes: s.into_bytes(), content_type: "application/json; charset=UTF-8", retryable: false });
        match self.dispatch_headers(context, url, body, None, false, headers).await? {
            Output::Bytes(reply) => Ok(reply), Output::Image(_) => unreachable!(),
        }
    }

    pub async fn pica_image(&self, context: SourceContext, image_url: String) -> Result<SourceHttpReply, SourceError> {
        if self.state.context.world != SourceWorld::Nsfw || !self.state.context.source_id.starts_with("picacg-") {
            return Err(SourceError::InvalidRequest);
        }
        let url = Url::parse(&image_url).map_err(|_| SourceError::InvalidRequest)?;
        let public_host = url.host_str().is_some_and(|h| h.parse::<std::net::IpAddr>().is_err() && !h.starts_with('['));
        if !public_host || url.host_str().is_none_or(|h| !h.contains('.') || h.ends_with(".localhost") || h.ends_with(".local")) ||
            url.scheme() != "https" || url.port_or_known_default() != Some(443) || !url.username().is_empty() || url.password().is_some() ||
            url.fragment().is_some() || url.query().is_some() || !url.path().starts_with("/static/") { return Err(SourceError::InvalidRequest); }
        let mut headers = header::HeaderMap::new();
        headers.insert(header::USER_AGENT, header::HeaderValue::from_static("okhttp/3.8.1"));
        match self.dispatch_headers(context, url, None, None, false, headers).await? {
            Output::Bytes(reply) => Ok(reply), Output::Image(_) => unreachable!(),
        }
    }

    /// Admitted JM profile, separate from the PicACG request contract above.
    #[uniffi::constructor]
    pub fn jm(context: SourceContext, base_url: String, config: SourceHttpConfig) -> Result<Self, SourceError> {
        let base = Url::parse(&base_url).map_err(|_| SourceError::InvalidRequest)?;
        if context.world != SourceWorld::Nsfw || !context.source_id.starts_with("jmcomic-") ||
            base.scheme() != "https" || base.path() != "/" || base.port_or_known_default() != Some(443) ||
            !matches!(base.host_str(), Some("www.cdnhjk.net" | "www.cdnutc.cc" | "www.cdnutc.me" | "www.cdngwc.cc" | "www.cdngwc.net" | "www.cdngwc.club" |
                "cdn-msp.jmapiproxy1.cc" | "cdn-msp.jmapiproxy2.cc" | "cdn-msp2.jmapiproxy2.cc" |
                "cdn-msp3.jmapiproxy2.cc" | "cdn-msp.jmapinodeudzn.net" | "cdn-msp3.jmapinodeudzn.net")) {
            return Err(SourceError::InvalidRequest);
        }
        Self::build(context, base_url, None, None, None, config)
    }

    pub fn jm_cookies(&self) -> Result<Vec<String>, SourceError> {
        self.state.jm_cookies.lock().map(|v| v.clone()).map_err(|_| SourceError::Transport)
    }

    pub async fn jm_request(&self, context: SourceContext, path: String, token: String,
        tokenparam: String, cookie: String, form: Option<String>) -> Result<SourceHttpReply, SourceError> {
        if self.state.context.world != SourceWorld::Nsfw || !self.state.context.source_id.starts_with("jmcomic-") {
            return Err(SourceError::InvalidRequest);
        }
        let url = self.state.base.join(&path).map_err(|_| SourceError::InvalidRequest)?;
        let endpoint = url.path();
        let image = self.state.base.host_str().is_some_and(|s| s.starts_with("cdn-"));
        let account_profile = endpoint.strip_prefix("/useredit/").is_some_and(|id|
            !id.is_empty() && id.len() <= 15 && !id.starts_with('0') && id.bytes().all(|b| b.is_ascii_digit()));
        if url.origin() != self.state.base.origin() || !url.username().is_empty() || url.password().is_some() || url.fragment().is_some() ||
            (image && (!endpoint.starts_with("/media/") || !cookie.is_empty() || form.is_some())) ||
            (!image && !account_profile && !matches!(endpoint, "/setting" | "/login" | "/search" | "/promote" | "/categories" | "/categories/filter" | "/album" | "/chapter" | "/chapter_view_template" | "/favorite")) ||
            (form.is_some() && (endpoint != "/login" || form.as_ref().is_some_and(|s| s.len() > 16384))) {
            return Err(SourceError::InvalidRequest);
        }
        let mut headers = header::HeaderMap::new();
        for (name, value) in [("token", token), ("tokenparam", tokenparam), ("cookie", cookie)] {
            if !value.is_empty() {
                let mut value = header::HeaderValue::from_str(&value).map_err(|_| SourceError::InvalidRequest)?;
                value.set_sensitive(true); headers.insert(header::HeaderName::from_static(name), value);
            }
        }
        headers.insert(header::USER_AGENT, header::HeaderValue::from_static("Mozilla/5.0 (Linux; Android 9; wv) AppleWebKit/537.36 Chrome/91.0.4472.114 Mobile Safari/537.36"));
        if image {
            headers.insert("x-requested-with", header::HeaderValue::from_static("com.JMComic3.app"));
            headers.insert(header::REFERER, header::HeaderValue::from_static("https://www.cdnhjk.net/"));
        }
        let body = form.map(|s| RequestBody { bytes: s.into_bytes(), content_type: "application/x-www-form-urlencoded", retryable: false });
        match self.dispatch_headers(context, url, body, None, false, headers).await? {
            Output::Bytes(reply) => Ok(reply), Output::Image(_) => unreachable!(),
        }
    }
    #[uniffi::constructor]
    pub fn new(
        context: SourceContext,
        base_url: String,
        authorization: Option<String>,
        cookie: Option<String>,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        if context.world != SourceWorld::Sfw { return Err(SourceError::InvalidRequest); }
        Self::build(context, base_url, authorization, cookie, None, config)
    }

    /// Anonymous Baozi metadata + cover profile, sharing one limiter/runtime.
    #[uniffi::constructor]
    pub fn baozi(
        context: SourceContext,
        base_url: String,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        if context.source_id != "baozimh-cn-v1" || context.auth_ref.is_some() {
            return Err(SourceError::InvalidRequest);
        }
        Self::build_with_headers(
            context,
            base_url,
            HttpCredentials::default(),
            config,
            header::HeaderMap::new(),
            Some("FluxManga/0.0.1"),
        )
    }

    pub async fn baozi_cover_file(
        &self,
        context: SourceContext,
        url: String,
        directory: String,
    ) -> Result<SourceImageFile, SourceError> {
        if self.state.context.source_id != "baozimh-cn-v1" || self.state.context.auth_ref.is_some()
        {
            return Err(SourceError::InvalidRequest);
        }
        let url = baozi_cover_url(&self.state.base, &url)?;
        self.anonymous_image_file(context, url, directory).await
    }

    /// Public HTML may require the issuing site's browser verification, but never TSID.
    /// The image and chapter channels remain credential-free.
    pub async fn baozi_public_page(&self, context: SourceContext, relative_path: String,
        cookie: String, user_agent: String) -> Result<SourceHttpReply, SourceError> {
        if self.state.context.source_id != "baozimh-cn-v1" || self.state.context.auth_ref.is_some()
            || !matches!(self.state.base.as_str(), "https://cn.baozimh.com/" | "https://www.baozimh.com/" | "https://cn.bzmgcn.com/")
            || cookie.len() > 16384 || user_agent.is_empty() || user_agent.len() > 1024 {
            return Err(SourceError::InvalidRequest);
        }
        let url = self.state.base.join(&relative_path).map_err(|_| SourceError::InvalidRequest)?;
        if url.origin() != self.state.base.origin() || !url.username().is_empty()
            || url.password().is_some() || url.fragment().is_some()
            || !(matches!(url.path(), "/" | "/classify" | "/search" | "/list/new")
                || url.path().starts_with("/comic/")) {
            return Err(SourceError::InvalidRequest);
        }
        let mut headers = header::HeaderMap::new();
        if !cookie.is_empty() {
            for part in cookie.split(';').map(str::trim) {
                let (name, value) = part.split_once('=').ok_or(SourceError::InvalidRequest)?;
                if !name.starts_with("__Host-gk_") || name.len() <= 10
                    || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
                    || value.is_empty() || !value.bytes().all(|b| (0x21..=0x7e).contains(&b) && b != b',') {
                    return Err(SourceError::InvalidRequest);
                }
            }
            let mut value = header::HeaderValue::from_str(&cookie).map_err(|_| SourceError::InvalidRequest)?;
            value.set_sensitive(true); headers.insert(header::COOKIE, value);
        }
        headers.insert(header::USER_AGENT, header::HeaderValue::from_str(&user_agent).map_err(|_| SourceError::InvalidRequest)?);
        headers.insert(header::REFERER, header::HeaderValue::from_str(self.state.base.as_str()).map_err(|_| SourceError::InvalidRequest)?);
        match self.dispatch_headers(context, url, None, None, false, headers).await? {
            Output::Bytes(reply) => Ok(reply), Output::Image(_) => unreachable!(),
        }
    }

    pub async fn baozi_chapter(
        &self,
        context: SourceContext,
        manga_id: String,
        chapter_id: String,
    ) -> Result<SourceHttpReply, SourceError> {
        if self.state.context.source_id != "baozimh-cn-v1"
            || self.state.context.auth_ref.is_some()
            || manga_id.is_empty()
            || manga_id.len() > 512
            || !manga_id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
            || chapter_id.split('_').count() != 2
            || !chapter_id
                .split('_')
                .all(|v| !v.is_empty() && v.parse::<u32>().is_ok())
        {
            return Err(SourceError::InvalidRequest);
        }
        let base = if self.state.base.scheme() == "http" {
            self.state.base.clone()
        } else {
            Url::parse("https://appcn.baozimh.com/").unwrap()
        };
        let url = base
            .join(&format!(
                "baozimhapp/comic/chapter/{manga_id}/{chapter_id}.html"
            ))
            .map_err(|_| SourceError::InvalidRequest)?;
        match self.dispatch(context, url, None, None, true).await? {
            Output::Bytes(reply) => Ok(reply),
            Output::Image(_) => unreachable!(),
        }
    }

    pub async fn baozi_page_file(
        &self,
        context: SourceContext,
        raw: String,
        directory: String,
    ) -> Result<SourceImageFile, SourceError> {
        if self.state.context.source_id != "baozimh-cn-v1" || self.state.context.auth_ref.is_some()
        {
            return Err(SourceError::InvalidRequest);
        }
        let url = baozi_page_url(&self.state.base, &raw)?;
        self.anonymous_image_file(context, url, directory).await
    }

    pub fn invalidate(&self) {
        self.state.closed.send_replace(true);
    }
    pub fn activity(&self) -> SourceHttpActivity {
        SourceHttpActivity {
            pending: self.state.pending.load(SeqCst),
            in_flight: self.state.in_flight.load(SeqCst),
            attempts: self.state.attempts.load(SeqCst),
        }
    }
    pub async fn get(
        &self,
        context: SourceContext,
        relative_path: String,
    ) -> Result<SourceHttpReply, SourceError> {
        if self.state.context.world != SourceWorld::Sfw { return Err(SourceError::InvalidRequest); }
        match self.request(context, relative_path, None, None).await? {
            Output::Bytes(reply) => Ok(reply),
            Output::Image(_) => unreachable!(),
        }
    }
}

impl SourceHttpSession {
    fn gallery_action_identity(&self, gid: u64, token: &str) -> Result<(), SourceError> {
        if self.state.context.world != SourceWorld::Nsfw ||
            !(self.state.context.source_id.starts_with("ehentai-user-") || self.state.context.source_id.starts_with("exhentai-user-")) ||
            gid == 0 || token.len() != 10 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(SourceError::InvalidRequest);
        }
        Ok(())
    }
    async fn gallery_action_post(&self, context: SourceContext, url: Url, referer: Url,
        body: RequestBody, cookie: String) -> Result<SourceHttpReply, SourceError> {
        if cookie.is_empty() { return Err(SourceError::AuthenticationRequired); }
        let mut headers = header::HeaderMap::new();
        let mut credential = header::HeaderValue::from_str(&cookie).map_err(|_| SourceError::InvalidRequest)?;
        credential.set_sensitive(true); headers.insert(header::COOKIE, credential);
        let origin = self.state.base.origin().ascii_serialization();
        for (name, value) in [(header::REFERER, referer.as_str()), (header::ORIGIN, origin.as_str())] {
            headers.insert(name, header::HeaderValue::from_str(value).map_err(|_| SourceError::InvalidRequest)?);
        }
        headers.insert(header::USER_AGENT, header::HeaderValue::from_static("Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"));
        match self.dispatch_headers(context, url, Some(body), None, false, headers).await? {
            Output::Bytes(reply) => Ok(reply), Output::Image(_) => unreachable!(),
        }
    }
    pub(crate) fn preserve_http_forbidden(self) -> Self {
        self.state.preserve_forbidden.store(true, SeqCst);
        self
    }
    pub(crate) fn build(
        context: SourceContext,
        base_url: String,
        authorization: Option<String>,
        cookie: Option<String>,
        api_key: Option<String>,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        Self::build_with_headers(
            context,
            base_url,
            HttpCredentials {
                authorization,
                cookie,
                api_key,
            },
            config,
            header::HeaderMap::new(),
            None,
        )
    }

    // Fixed anonymous CopyManga profile, scoped to this source client only.
    pub(crate) fn copy_manga(
        context: SourceContext,
        base_url: String,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        Self::copy_manga_account(context, base_url, None, config)
    }
    pub(crate) fn copy_manga_account(
        context: SourceContext,
        base_url: String,
        token: Option<String>,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        let official = base_url == "https://mapi.copy-manga.com/";
        let web = base_url == "https://api.copy4000.com/";
        let mut headers = header::HeaderMap::new();
        for (name, value) in [
            ("platform", if web { "1" } else { "3" }),
            ("version", if web { "2026.10.16" } else if official { "3.0.9" } else { "2024.4.28" }),
            ("webp", "1"),
            ("accept", "application/json"),
            ("x-requested-with", if official { "com.copymanga.app" } else { "com.manga2020.app" }),
            ("authorization", "Token"),
        ] {
            headers.insert(
                header::HeaderName::from_static(name),
                header::HeaderValue::from_static(value),
            );
        }
        if official {
            headers.insert(header::REFERER, header::HeaderValue::from_static("com.copymanga.app-3.0.9"));
            headers.insert("source", header::HeaderValue::from_static("copyApp"));
            headers.insert("region", header::HeaderValue::from_static("1"));
        }
        if web {
            headers.remove("x-requested-with");
            headers.insert(header::ORIGIN, header::HeaderValue::from_static("https://mangacopy.com"));
            headers.insert(header::REFERER, header::HeaderValue::from_static("https://mangacopy.com/"));
        }
        Self::build_with_headers(
            context,
            base_url,
            HttpCredentials {
                authorization: token.map(|t| format!("Token {t}")),
                ..HttpCredentials::default()
            },
            config,
            headers,
            Some("Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 Edg/141.0.0.0"))
    }

    fn build_with_headers(
        context: SourceContext,
        base_url: String,
        credentials: HttpCredentials,
        config: SourceHttpConfig,
        mut headers: header::HeaderMap,
        image_user_agent: Option<&str>,
    ) -> Result<Self, SourceError> {
        let HttpCredentials {
            authorization,
            cookie,
            api_key,
        } = credentials;
        let private = context.world == SourceWorld::Nsfw && (context.source_id.starts_with("jmcomic-") || context.source_id.starts_with("picacg-") || context.source_id.starts_with("ehentai-") || context.source_id.starts_with("exhentai-user-"));
        let max_body = if private { 32 * 1024 * 1024 } else { 4 * 1024 * 1024 };
        if (context.world != SourceWorld::Sfw && !private)
            || context.source_id.is_empty()
            || context.process_session.is_empty()
            || !(100..=120_000).contains(&config.timeout_ms)
            || !(1..=4).contains(&config.max_in_flight)
            || config.min_interval_ms > 60_000
            || config.max_retries > 2
            || !(1..=max_body).contains(&config.max_body_bytes)
            || ((authorization.is_some() || cookie.is_some() || api_key.is_some())
                && context.auth_ref.as_ref().is_none_or(|s| s.is_empty()))
        {
            return Err(SourceError::InvalidRequest);
        }
        let base = Url::parse(&base_url).map_err(|_| SourceError::InvalidRequest)?;
        // Cleartext is only for an on-device/local fixture. Future LAN support needs
        // a deliberate user-facing policy; Android NetworkSecurityConfig does not gate Rust sockets.
        let local = matches!(base.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"));
        if !(base.scheme() == "https" || base.scheme() == "http" && local)
            || !base.username().is_empty()
            || base.password().is_some()
            || base.query().is_some()
            || base.fragment().is_some()
            || !base.path().ends_with('/')
        {
            return Err(SourceError::InvalidRequest);
        }
        for (name, value) in [
            (header::AUTHORIZATION, authorization),
            (header::COOKIE, cookie),
            (header::HeaderName::from_static("x-api-key"), api_key),
        ] {
            if let Some(value) = value {
                let mut value = header::HeaderValue::from_str(&value)
                    .map_err(|_| SourceError::InvalidRequest)?;
                value.set_sensitive(true);
                headers.insert(name, value);
            }
        }
        let client = Client::builder()
            .use_rustls_tls()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .retry(reqwest::retry::never())
            .default_headers(headers)
            .connect_timeout(Duration::from_millis(config.timeout_ms.into()))
            .user_agent("FluxManga/0.0.1")
            .build()
            .map_err(transport_error)?;
        // Never reuse API default headers on a CDN, including same-origin cover URLs.
        let anonymous_images = if let Some(user_agent) = image_user_agent {
            Some(
                Client::builder()
                    .use_rustls_tls()
                    .no_proxy()
                    .redirect(reqwest::redirect::Policy::none())
                    .retry(reqwest::retry::never())
                    .connect_timeout(Duration::from_millis(config.timeout_ms.into()))
                    .user_agent(user_agent)
                    .build()
                    .map_err(transport_error)?,
            )
        } else {
            None
        };
        let runtime = Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("flux-source-http")
            .enable_all()
            .build()
            .map_err(|_| SourceError::Transport)?;
        Ok(Self {
            state: Arc::new(State {
                context,
                base,
                client,
                anonymous_images,
                permits: Semaphore::new(config.max_in_flight as usize),
                config,
                next_start: Mutex::new(Instant::now()),
                closed: watch::channel(false).0,
                pending: Arc::new(AtomicU64::new(0)),
                in_flight: Arc::new(AtomicU64::new(0)),
                attempts: AtomicU64::new(0),
                preserve_forbidden: AtomicBool::new(false),
                jm_cookies: std::sync::Mutex::new(Vec::new()),
                gallery_login_cookies: std::sync::Mutex::new(Vec::new()),
            }),
            runtime: Some(runtime),
        })
    }

    pub(crate) async fn copy_sign_in(
        &self,
        context: SourceContext,
        body: RequestBody,
    ) -> Result<SourceHttpReply, SourceError> {
        let endpoint = if self.state.base.as_str() == "https://mapi.copy-manga.com/" {
            "api/v3/login"
        } else { "api/v3/login?platform=3" };
        match self
            .request(context, endpoint.into(), Some(body), None)
            .await?
        {
            Output::Bytes(reply) => Ok(reply),
            Output::Image(_) => unreachable!(),
        }
    }

    // Only Komga's documented read-only list queries use POST. No generic write API.
    pub(crate) async fn komga_list(
        &self,
        context: SourceContext,
        path: String,
        body: Vec<u8>,
    ) -> Result<SourceHttpReply, SourceError> {
        let endpoint = path.split('?').next().unwrap_or_default();
        if !matches!(endpoint, "api/v1/series/list" | "api/v1/books/list") || body.len() > 16_384 {
            return Err(SourceError::InvalidRequest);
        }
        match self
            .request(
                context,
                path,
                Some(RequestBody {
                    bytes: body,
                    content_type: "application/json",
                    retryable: true,
                }),
                None,
            )
            .await?
        {
            Output::Bytes(reply) => Ok(reply),
            Output::Image(_) => unreachable!(),
        }
    }

    // Keep the official browser's UA and origin for its scoped account cookies.
    pub(crate) async fn baozi_browser_bookshelf(&self, context: SourceContext,
        user_agent: &str) -> Result<SourceHttpReply, SourceError> {
        let mut headers = header::HeaderMap::new();
        headers.insert(header::USER_AGENT, header::HeaderValue::from_str(user_agent)
            .map_err(|_| SourceError::InvalidRequest)?);
        headers.insert(header::REFERER, header::HeaderValue::from_str(self.state.base.as_str())
            .map_err(|_| SourceError::InvalidRequest)?);
        let url = self.state.base.join("user/my_bookshelf").map_err(|_| SourceError::InvalidRequest)?;
        match self.dispatch_headers(context, url, None, None, false, headers).await? {
            Output::Bytes(reply) => Ok(reply),
            Output::Image(_) => unreachable!(),
        }
    }

    // Fixed legacy Baozi sign-in path; no arbitrary write endpoint is exposed through FFI.
    pub(crate) async fn baozi_sign_in(
        &self,
        context: SourceContext,
        body: RequestBody,
    ) -> Result<SourceHttpReply, SourceError> {
        match self
            .request(context, "api/bui/signin".into(), Some(body), None)
            .await?
        {
            Output::Bytes(reply) => Ok(reply),
            Output::Image(_) => unreachable!(),
        }
    }

    pub(crate) async fn image_file(
        &self,
        context: SourceContext,
        path: String,
        directory: String,
    ) -> Result<SourceImageFile, SourceError> {
        match self.request(context, path, None, Some(directory)).await? {
            Output::Image(file) => Ok(file),
            Output::Bytes(_) => unreachable!(),
        }
    }

    // The legacy Copy API still serves recommendations, but newest now returns 404.
    // Use the website's public feed without forwarding any account credentials.
    pub(crate) async fn copy_updates(&self, context: SourceContext, offset: u32, limit: u32) -> Result<SourceHttpReply, SourceError> {
        let url = Url::parse(&format!(
            "https://api.copy4000.com/api/v3/update/newest?limit={limit}&offset={offset}&platform=1"
        )).map_err(|_| SourceError::InvalidRequest)?;
        let mut headers = header::HeaderMap::new();
        headers.insert(header::ACCEPT, header::HeaderValue::from_static("application/json"));
        headers.insert("platform", header::HeaderValue::from_static("1"));
        headers.insert("version", header::HeaderValue::from_static("2026.10.16"));
        headers.insert(header::ORIGIN, header::HeaderValue::from_static("https://mangacopy.com"));
        headers.insert(header::REFERER, header::HeaderValue::from_static("https://mangacopy.com/"));
        match self.dispatch_headers(context, url, None, None, true, headers).await? {
            Output::Bytes(reply) => Ok(reply),
            Output::Image(_) => unreachable!(),
        }
    }

    async fn request(
        &self,
        context: SourceContext,
        relative_path: String,
        body: Option<RequestBody>,
        directory: Option<String>,
    ) -> Result<Output, SourceError> {
        let state = self.state.clone();
        if context != state.context || *state.closed.borrow() {
            return Err(SourceError::SessionMismatch);
        }
        let url = state
            .base
            .join(&relative_path)
            .map_err(|_| SourceError::InvalidRequest)?;
        if relative_path.starts_with('/')
            || relative_path.contains('\\')
            || url.origin() != state.base.origin()
            || !url.path().starts_with(state.base.path())
            || !url.username().is_empty()
            || url.password().is_some()
            || url.fragment().is_some()
        {
            return Err(SourceError::InvalidRequest);
        }
        self.dispatch(context, url, body, directory, false).await
    }

    // Source adapters validate URLs before entering this credential-free image channel.
    pub(crate) async fn anonymous_image_file(
        &self,
        context: SourceContext,
        url: Url,
        directory: String,
    ) -> Result<SourceImageFile, SourceError> {
        if self.state.anonymous_images.is_none() {
            return Err(SourceError::InvalidRequest);
        }
        match self
            .dispatch(context, url, None, Some(directory), true)
            .await?
        {
            Output::Image(file) => Ok(file),
            Output::Bytes(_) => unreachable!(),
        }
    }

    async fn dispatch(
        &self,
        context: SourceContext,
        url: Url,
        body: Option<RequestBody>,
        directory: Option<String>,
        anonymous: bool,
    ) -> Result<Output, SourceError> {
        self.dispatch_headers(context, url, body, directory, anonymous, header::HeaderMap::new()).await
    }
    async fn dispatch_headers(&self, context: SourceContext, url: Url, body: Option<RequestBody>,
        directory: Option<String>, anonymous: bool, headers: header::HeaderMap) -> Result<Output, SourceError> {
        let state = self.state.clone();
        if context != state.context || *state.closed.borrow() {
            return Err(SourceError::SessionMismatch);
        }
        let count = Count::new(&state.pending);
        let mut closed = state.closed.subscribe();
        let mut task = AbortOnDrop(self.runtime.as_ref().expect("live HTTP session").spawn(async move {
            let _count = count;
            if *closed.borrow() { return Err(SourceError::SessionMismatch); }
            tokio::select! {
                biased;
                _ = closed.changed() => Err(SourceError::SessionMismatch),
                result = tokio::time::timeout(Duration::from_millis(state.config.timeout_ms.into()), state.get(url, body, directory, anonymous, headers)) => result.unwrap_or(Err(SourceError::Timeout)),
            }
        }));
        (&mut task.0).await.map_err(|_| SourceError::Transport)?
    }
}

impl State {
    async fn get(
        &self,
        url: Url,
        body: Option<RequestBody>,
        directory: Option<String>,
        anonymous: bool,
        headers: header::HeaderMap,
    ) -> Result<Output, SourceError> {
        let _permit = self
            .permits
            .acquire()
            .await
            .map_err(|_| SourceError::SessionMismatch)?;
        let _in_flight = Count::new(&self.in_flight);
        let retries = if body.as_ref().is_some_and(|body| !body.retryable) {
            0
        } else {
            self.config.max_retries
        };
        for attempt in 0..=retries {
            {
                let mut next = self.next_start.lock().await;
                tokio::time::sleep_until(*next).await;
                *next = Instant::now() + Duration::from_millis(self.config.min_interval_ms.into());
            }
            self.attempts.fetch_add(1, SeqCst);
            // Retry explicit transient statuses on GET / read-only Komga list POST. Transport,
            // TLS, auth, decoding and body-read failures are not blindly retried.
            let client = if anonymous {
                self.anonymous_images
                    .as_ref()
                    .ok_or(SourceError::InvalidRequest)?
            } else {
                &self.client
            };
            let request = if let Some(body) = &body {
                client
                    .post(url.clone())
                    .header(header::CONTENT_TYPE, body.content_type)
                    .body(body.bytes.clone())
            } else {
                client.get(url.clone())
            };
            let mut request = request.headers(headers.clone());
            if !anonymous && self.base.as_str() == "https://api.copy4000.com/" {
                request = request.header(header::USER_AGENT,
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36");
            }
            if !anonymous && self.base.as_str() == "https://mapi.copy-manga.com/"
                && self.context.source_id.starts_with("copymanga-account-") {
                // Public mobile protocol: sign the current request time, never the user's password.
                // Generated after pacing, and never attached to the anonymous image client.
                let timestamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
                    .map_err(|_| SourceError::InvalidRequest)?.as_secs().to_string();
                let key = ring::hmac::Key::new(ring::hmac::HMAC_SHA256, b"3af08590311032efe0660550a0563a53");
                let signature = ring::hmac::sign(&key, timestamp.as_bytes()).as_ref().iter()
                    .map(|byte| format!("{byte:02x}")).collect::<String>();
                request = request.header(header::USER_AGENT, "COPY/3.0.9")
                    .header("x-auth-timestamp", timestamp).header("x-auth-signature", signature);
            }
            let mut response = request.send().await.map_err(transport_error)?;
            // Only EH/EX media follows redirects. Recheck destinations and remove Cookie
            // on leaving protected same-site media; never forward credentials to H@H/CDNs.
            let gallery_source = self.context.source_id.starts_with("ehentai-") || self.context.source_id.starts_with("exhentai-user-");
            let gallery_host = self.base.host_str().unwrap_or_default();
            let eh_image = gallery_source && eh_media(&url, gallery_host);
            if eh_image {
                let mut current = url.clone();
                let mut redirected_headers = headers.clone();
                let mut hops = 0;
                while response.status().is_redirection() {
                    if hops == 3 { return Err(SourceError::InvalidResponse); }
                    let location = response.headers().get(header::LOCATION).and_then(|v| v.to_str().ok()).ok_or(SourceError::InvalidResponse)?;
                    let next = current.join(location).map_err(|_| SourceError::InvalidResponse)?;
                    if !eh_media(&next, gallery_host) { return Err(SourceError::InvalidResponse); }
                    if next.origin() != current.origin() || !gallery_protected_media(&next, gallery_host) { redirected_headers.remove(header::COOKIE); }
                    drop(response);
                    {
                        let mut start = self.next_start.lock().await;
                        tokio::time::sleep_until(*start).await;
                        *start = Instant::now() + Duration::from_millis(self.config.min_interval_ms.into());
                    }
                    self.attempts.fetch_add(1, SeqCst);
                    response = client.get(next.clone()).headers(redirected_headers.clone()).send().await.map_err(transport_error)?;
                    current = next; hops += 1;
                }
            }
            let status = response.status().as_u16();
            if gallery_source && response.headers().get(header::CONTENT_DISPOSITION).and_then(|v| v.to_str().ok()).is_some_and(|v| v.contains("sadpanda.jpg")) {
                return Err(SourceError::AuthenticationRequired);
            }
            let retry_after = response
                .headers()
                .get(header::RETRY_AFTER)
                .and_then(|v| v.to_str().ok())
                .and_then(retry_seconds);
            if status == 429 || matches!(status, 502..=504) && retry_after.is_some() {
                let delay = Duration::from_secs(retry_after.unwrap_or(0).into())
                    .max(Duration::from_millis(100));
                let mut next = self.next_start.lock().await;
                *next = (*next).max(Instant::now() + delay);
            }
            if matches!(status, 429 | 502 | 503 | 504) && attempt < retries {
                let delay = Duration::from_secs(retry_after.unwrap_or(0).into())
                    .max(Duration::from_millis(100 * (1 << attempt)));
                drop(response);
                // No capped-early Retry-After: total deadline can end a longer wait.
                tokio::time::sleep(delay).await;
                continue;
            }
            match status {
                403 if self.preserve_forbidden.load(SeqCst) => {
                    return Err(SourceError::HttpStatus { status: 403 })
                }
                401 | 403 => return Err(SourceError::AuthenticationRequired),
                404 => return Err(SourceError::NotFound),
                429 => {
                    return Err(SourceError::RateLimited {
                        retry_after_seconds: retry_after,
                    })
                }
                200..=299 => {}
                _ => return Err(SourceError::HttpStatus { status }),
            }
            let limit = if (gallery_source && !eh_image) || (self.context.source_id.starts_with("picacg-") && url.origin() == self.base.origin() && !url.path().starts_with("/static/")) {
                4 * 1024 * 1024
            } else if directory.is_some() {
                MAX_IMAGE_BYTES
            } else {
                self.config.max_body_bytes as usize
            };
            if response.content_length().is_some_and(|n| n > limit as u64) {
                return Err(SourceError::InvalidResponse);
            }
            let content_type = response
                .headers()
                .get(header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned);
            if self.context.world == SourceWorld::Nsfw && self.context.source_id.starts_with("jmcomic-") && matches!(url.path(), "/login" | "/setting") {
                let cookies = response.headers().get_all(header::SET_COOKIE).iter()
                    .filter_map(|value| value.to_str().ok().map(str::to_owned)).collect();
                *self.jm_cookies.lock().map_err(|_| SourceError::Transport)? = cookies;
            }
            if self.context.world == SourceWorld::Nsfw && matches!(self.context.source_id.as_str(), "ehentai-login" | "exhentai-login") &&
                url.host_str() == Some("forums.e-hentai.org") && url.path() == "/index.php" && url.query() == Some("act=Login&CODE=01") {
                let cookies = response.headers().get_all(header::SET_COOKIE).iter()
                    .filter_map(|value| value.to_str().ok().map(str::to_owned)).collect();
                *self.gallery_login_cookies.lock().map_err(|_| SourceError::Transport)? = cookies;
            }
            if let Some(directory) = &directory {
                if status != 200
                    || !content_type.as_deref().is_some_and(|value| {
                        let mime = value.split(';').next().unwrap_or_default().trim().to_ascii_lowercase();
                        mime.starts_with("image/") ||
                            (self.context.source_id.starts_with("copymanga-") &&
                             matches!(mime.as_str(), "binary/octet-stream" | "application/octet-stream"))
                    })
                {
                    return Err(SourceError::InvalidResponse);
                }
                let mut sink = ImageSink::create(directory)?;
                while let Some(chunk) = response.chunk().await.map_err(transport_error)? {
                    // Bounded chunks go straight to the private output, never a full-image Vec.
                    sink.write(&chunk)?;
                }
                return Ok(Output::Image(sink.finish()?));
            }
            let mut body = Vec::new();
            while let Some(chunk) = response.chunk().await.map_err(transport_error)? {
                if chunk.len() > limit - body.len() {
                    return Err(SourceError::InvalidResponse);
                }
                body.extend_from_slice(&chunk);
            }
            return Ok(Output::Bytes(SourceHttpReply {
                context: self.context.clone(),
                body,
                content_type,
            }));
        }
        unreachable!("bounded retry loop always returns")
    }
}
impl Drop for SourceHttpSession {
    fn drop(&mut self) {
        self.invalidate();
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn context() -> SourceContext {
        SourceContext {
            world: SourceWorld::Sfw,
            source_id: "test".into(),
            process_session: "process".into(),
            generation: 1,
            auth_ref: None,
        }
    }
    fn config() -> SourceHttpConfig {
        SourceHttpConfig {
            timeout_ms: 1000,
            max_in_flight: 1,
            min_interval_ms: 0,
            max_retries: 0,
            max_body_bytes: 1024,
        }
    }

    #[test]
    fn transport_policy_rejects_unscoped_credentials_and_cleartext_remote() {
        for base in [
            "http://example.com/",
            "https://user:password@example.com/",
            "https://example.com/?token=secret",
            "https://example.com/no-trailing-slash",
        ] {
            assert!(matches!(
                SourceHttpSession::new(context(), base.into(), None, None, config()),
                Err(SourceError::InvalidRequest)
            ));
        }
        assert!(matches!(
            SourceHttpSession::new(
                context(),
                "https://example.com/".into(),
                Some("secret".into()),
                None,
                config()
            ),
            Err(SourceError::InvalidRequest)
        ));
        let mut private = context();
        private.world = SourceWorld::Nsfw;
        assert!(matches!(
            SourceHttpSession::new(private, "https://example.com/".into(), None, None, config()),
            Err(SourceError::InvalidRequest)
        ));
    }

    #[test]
    fn retry_after_supports_seconds_dates_and_invalid_values() {
        assert_eq!(retry_seconds("3"), Some(3));
        assert_eq!(retry_seconds("invalid"), None);
        let future = httpdate::fmt_http_date(std::time::SystemTime::now() + Duration::from_secs(5));
        assert!((4..=5).contains(&retry_seconds(&future).unwrap()));
        assert_eq!(retry_seconds("Thu, 01 Jan 1970 00:00:00 GMT"), Some(0));
    }

    #[test]
    fn real_http_body_and_origin_boundary() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/api/", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut request = [0; 2048];
            let n = socket.read(&mut request).unwrap();
            assert!(String::from_utf8_lossy(&request[..n]).starts_with("GET /api/page?number=1 "));
            socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}").unwrap();
        });
        let session = SourceHttpSession::new(context(), base, None, None, config()).unwrap();
        let runtime = Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            for path in [
                "../escape",
                "https://example.com/",
                "/outside",
                "page#fragment",
            ] {
                assert!(matches!(
                    session.get(context(), path.into()).await,
                    Err(SourceError::InvalidRequest)
                ));
            }
            let reply = session
                .get(context(), "page?number=1".into())
                .await
                .unwrap();
            assert_eq!(reply.body, b"{}");
            assert_eq!(reply.context, context());
            assert_eq!(session.activity().attempts, 1);
            session.invalidate();
            assert!(matches!(
                session.get(context(), "page".into()).await,
                Err(SourceError::SessionMismatch)
            ));
        });
        server.join().unwrap();
    }

    #[test]
    fn streamed_image_auth_mime_and_cancellation_cleanup() {
        use std::io::{Read, Write};
        let directory = std::env::temp_dir().join(format!(
            "flux-stream-test-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", listener.local_addr().unwrap());
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let server = std::thread::spawn(move || {
            for kind in 0..3 {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let mut request = Vec::new();
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0];
                    socket.read_exact(&mut byte).unwrap();
                    request.push(byte[0]);
                }
                assert!(String::from_utf8_lossy(&request)
                    .to_ascii_lowercase()
                    .contains("x-api-key: synthetic"));
                match kind {
                    0 => {
                        // Larger than the 1 KiB metadata bound, with no full-image buffer in transport.
                        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 131072\r\nConnection: close\r\n\r\n").unwrap();
                        for _ in 0..32 {
                            socket.write_all(&[7; 4096]).unwrap();
                        }
                    }
                    1 => {
                        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 4\r\nConnection: close\r\n\r\noops").unwrap();
                    }
                    _ => {
                        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 131072\r\nConnection: close\r\n\r\npartial").unwrap();
                        started_tx.send(()).unwrap();
                        let _ = socket.read(&mut [0]);
                    }
                }
            }
        });
        let mut context = context();
        context.auth_ref = Some("auth".into());
        let session = SourceHttpSession::build(
            context.clone(),
            base,
            None,
            None,
            Some("synthetic".into()),
            config(),
        )
        .unwrap();
        let runtime = Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            let owner = session
                .image_file(
                    context.clone(),
                    "image".into(),
                    directory.to_string_lossy().into(),
                )
                .await
                .unwrap();
            assert_eq!(std::fs::metadata(owner.path()).unwrap().len(), 131072);
            drop(owner);
            assert!(matches!(
                session
                    .image_file(
                        context.clone(),
                        "bad".into(),
                        directory.to_string_lossy().into()
                    )
                    .await,
                Err(SourceError::InvalidResponse)
            ));
            let future = session.image_file(
                context.clone(),
                "slow".into(),
                directory.to_string_lossy().into(),
            );
            tokio::pin!(future);
            loop {
                tokio::select! {
                    _ = &mut future => panic!("slow image unexpectedly completed"),
                    _ = tokio::time::sleep(Duration::from_millis(5)) => {
                        if started_rx.try_recv().is_ok() { break; }
                    }
                }
            }
            session.invalidate();
            assert!(matches!(future.await, Err(SourceError::SessionMismatch)));
            assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 0);
        });
        server.join().unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
    #[test]
    fn baozi_cover_policy_is_source_scoped_and_rejects_foreign_destinations() {
        let base = Url::parse("https://cn.baozimh.com/").unwrap();
        for url in [
            "https://static-tw.baozimh.com/cover/a.jpg",
            "https://static-tw.baozicdn.com/cover/a.jpg",
        ] {
            assert!(baozi_cover_url(&base, url).is_ok());
        }
        for url in [
            "http://static-tw.baozimh.com/cover/a.jpg",
            "https://evil.test/cover/a.jpg",
            "https://static-tw.baozimh.com.evil.test/cover/a.jpg",
            "https://user:pass@static-tw.baozimh.com/cover/a.jpg",
            "https://static-tw.baozimh.com/cover/a.jpg#fragment",
            "http://127.0.0.1/cover/a.jpg",
            "https://static-tw.baozimh.com/comic/a.jpg",
        ] {
            assert!(baozi_cover_url(&base, url).is_err());
        }
        assert!(matches!(
            baozi_cover_url(&base, "https://static-tw.baozimh.com/cover/404.jpg"),
            Err(SourceError::NotFound)
        ));
        assert!(SourceHttpSession::baozi(context(), base.to_string(), config()).is_err());
        let mut auth = context();
        auth.source_id = "baozimh-cn-v1".into();
        auth.auth_ref = Some("secret".into());
        assert!(SourceHttpSession::baozi(auth, base.to_string(), config()).is_err());
    }

    #[test]
    fn baozi_anonymous_cover_stream_preserves_files_and_cancels_partial_outputs() {
        use std::io::{Read, Write};
        let directory = std::env::temp_dir().join(format!(
            "flux-baozi-test-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        let original = directory.join("original.cbz");
        std::fs::write(&original, b"keep").unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", listener.local_addr().unwrap());
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let server = std::thread::spawn(move || {
            for kind in 0..4 {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let mut request = Vec::new();
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0];
                    socket.read_exact(&mut byte).unwrap();
                    request.push(byte[0]);
                }
                let text = String::from_utf8_lossy(&request).to_ascii_lowercase();
                assert!(text.starts_with("get /cover/"));
                assert!(text.contains("user-agent: fluxmanga/0.0.1"));
                for forbidden in [
                    "authorization:",
                    "cookie:",
                    "x-api-key:",
                    "x-requested-with:",
                ] {
                    assert!(!text.contains(forbidden));
                }
                match kind {
                    0 => {
                        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 4096\r\nConnection: close\r\n\r\n").unwrap();
                        socket.write_all(&[7; 4096]).unwrap();
                    }
                    1 => {
                        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 4\r\nConnection: close\r\n\r\noops").unwrap();
                    }
                    2 => {
                        socket.write_all(b"HTTP/1.1 302 Found\r\nLocation: https://static-tw.baozimh.com/cover/404.jpg\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").unwrap();
                    }
                    _ => {
                        socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 131072\r\nConnection: close\r\n\r\npartial").unwrap();
                        started_tx.send(()).unwrap();
                        let _ = socket.read(&mut [0]);
                    }
                }
            }
        });
        let mut ctx = context();
        ctx.source_id = "baozimh-cn-v1".into();
        let session = SourceHttpSession::baozi(ctx.clone(), base.clone(), config()).unwrap();
        let runtime = Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            assert!(matches!(session.baozi_cover_file(context(), format!("{base}cover/a.jpg"), directory.to_string_lossy().into()).await, Err(SourceError::SessionMismatch)));
            let owner = session.baozi_cover_file(ctx.clone(), format!("{base}cover/a.jpg"), directory.to_string_lossy().into()).await.unwrap();
            assert_eq!(std::fs::metadata(owner.path()).unwrap().len(),4096);
            let path = owner.path(); drop(owner); assert!(!std::path::Path::new(&path).exists());
            assert!(matches!(session.baozi_cover_file(ctx.clone(), format!("{base}cover/b.jpg"), directory.to_string_lossy().into()).await, Err(SourceError::InvalidResponse)));
            assert!(matches!(session.baozi_cover_file(ctx.clone(), format!("{base}cover/c.jpg"), directory.to_string_lossy().into()).await, Err(SourceError::HttpStatus {status:302})));
            let future = session.baozi_cover_file(ctx.clone(), format!("{base}cover/slow.jpg"), directory.to_string_lossy().into());
            tokio::pin!(future);
            loop {
                tokio::select! {
                    _ = &mut future => panic!("slow image unexpectedly completed"),
                    _ = tokio::time::sleep(Duration::from_millis(5)) => { if started_rx.try_recv().is_ok() { break; } }
                }
            }
            session.invalidate(); assert!(matches!(future.await, Err(SourceError::SessionMismatch)));
            assert_eq!(std::fs::read_dir(&directory).unwrap().count(),1);
        });
        server.join().unwrap();
        assert_eq!(std::fs::read(&original).unwrap(), b"keep");
        std::fs::remove_file(original).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
    #[test]
    fn baozi_reader_uses_fixed_chapter_route_anonymous_images_and_context() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            for path in [
                "/baozimhapp/comic/chapter/sample/0_1.html",
                "/comic/sample/1.jpg",
            ] {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(3)))
                    .unwrap();
                let mut request = Vec::new();
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0];
                    socket.read_exact(&mut byte).unwrap();
                    request.push(byte[0]);
                }
                let request = String::from_utf8_lossy(&request).to_ascii_lowercase();
                assert!(request.starts_with(&format!("get {path} ")));
                assert!(!request.contains("cookie:"));
                assert!(!request.contains("authorization:"));
                let mime = if path.ends_with("html") {
                    "text/html"
                } else {
                    "image/jpeg"
                };
                socket.write_all(format!("HTTP/1.1 200 OK\r\nContent-Type: {mime}\r\nContent-Length: 4\r\nConnection: close\r\n\r\ndata").as_bytes()).unwrap();
            }
        });
        let mut ctx = context();
        ctx.source_id = "baozimh-cn-v1".into();
        let session = SourceHttpSession::baozi(ctx.clone(), base.clone(), config()).unwrap();
        let directory = std::env::temp_dir().join(format!(
            "flux-baozi-reader-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        let runtime = Builder::new_current_thread().enable_all().build().unwrap();
        runtime.block_on(async {
            assert!(matches!(
                session
                    .baozi_chapter(ctx.clone(), "../bad".into(), "0_1".into())
                    .await,
                Err(SourceError::InvalidRequest)
            ));
            assert!(matches!(
                session
                    .baozi_chapter(context(), "sample".into(), "0_1".into())
                    .await,
                Err(SourceError::SessionMismatch)
            ));
            let reply = session
                .baozi_chapter(ctx.clone(), "sample".into(), "0_1".into())
                .await
                .unwrap();
            assert_eq!(reply.context, ctx);
            assert_eq!(reply.body, b"data");
            let file = session
                .baozi_page_file(
                    ctx,
                    format!("{base}comic/sample/1.jpg"),
                    directory.to_string_lossy().into(),
                )
                .await
                .unwrap();
            assert_eq!(std::fs::read(file.path()).unwrap(), b"data");
            drop(file);
            assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 0);
        });
        server.join().unwrap();
        std::fs::remove_dir(directory).unwrap();
        let production = Url::parse("https://cn.baozimh.com/").unwrap();
        assert!(baozi_page_url(&production, "https://s1.baozimh.com/comic/1.jpg").is_ok());
        assert!(baozi_page_url(&production, "https://s1.bzcdn.net/comic/1.jpg").is_ok());
        for url in [
            "https://evil.test/1.jpg",
            "http://127.0.0.1/1.jpg",
            "https://u:p@s1.baozimh.com/1.jpg",
        ] {
            assert!(baozi_page_url(&production, url).is_err());
        }
    }
}
