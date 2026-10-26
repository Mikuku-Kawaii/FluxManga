//! Baozi account protocol only; credential persistence and verified import belong to Kotlin.
//! Adapted from skepsun/kototoro-parsers a94b6704, GPL-3.0.
//! See assets/licenses/baozi-account-NOTICE.txt. No remote collection writes.
use crate::source::{SourceContext, SourceError};
use crate::transport::{RequestBody, SourceHttpConfig, SourceHttpReply, SourceHttpSession};
use reqwest::Url;

const CONTENT_TYPE: &str = "multipart/form-data; boundary=FluxMangaBaoziAccountBoundary";
const BOUNDARY: &str = "FluxMangaBaoziAccountBoundary";

fn valid_token(token: &str) -> bool {
    !token.is_empty()
        && token.len() <= 4096
        && token
            .bytes()
            .all(|b| matches!(b, 0x21 | 0x23..=0x2b | 0x2d..=0x3a | 0x3c..=0x5b | 0x5d..=0x7e))
}

fn credentials(username: &str, password: &str) -> Result<RequestBody, SourceError> {
    if username.trim().is_empty()
        || username.len() > 256
        || password.is_empty()
        || password.len() > 4096
        || [username, password]
            .iter()
            .any(|value| value.contains(BOUNDARY) || value.contains('\0'))
    {
        return Err(SourceError::InvalidRequest);
    }
    let mut bytes = Vec::new();
    for (name, value) in [("username", username), ("password", password)] {
        bytes.extend_from_slice(
            format!("--{BOUNDARY}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n")
                .as_bytes(),
        );
        bytes.extend_from_slice(value.as_bytes());
        bytes.extend_from_slice(b"\r\n");
    }
    bytes.extend_from_slice(format!("--{BOUNDARY}--\r\n").as_bytes());
    Ok(RequestBody {
        bytes,
        content_type: CONTENT_TYPE,
        retryable: false,
    })
}

// A token is only a sign-in candidate: the shelf still needs independent validation.
fn sign_in_token(reply: SourceHttpReply) -> Result<String, SourceError> {
    if !reply.content_type.as_deref().is_some_and(|value| {
        value
            .split(';')
            .next()
            .unwrap_or_default()
            .trim()
            .eq_ignore_ascii_case("application/json")
    }) {
        return Err(SourceError::InvalidResponse);
    }
    let json: serde_json::Value =
        serde_json::from_slice(&reply.body).map_err(|_| SourceError::InvalidResponse)?;
    if !json.is_object() {
        return Err(SourceError::InvalidResponse);
    }
    match json.get("data") {
        Some(serde_json::Value::String(token)) if valid_token(token) => Ok(token.clone()),
        None | Some(serde_json::Value::Null) => Err(SourceError::AuthenticationRequired),
        Some(serde_json::Value::String(token)) if token.is_empty() => {
            Err(SourceError::AuthenticationRequired)
        }
        _ => Err(SourceError::InvalidResponse),
    }
}

#[derive(uniffi::Object)]
pub struct BaoziAccountClient {
    transport: SourceHttpSession,
    authenticated: bool,
    browser_user_agent: Option<String>,
}

#[uniffi::export]
impl BaoziAccountClient {
    /// Context identifies an account instance, never the anonymous source or an actual password.
    /// Only cn.baozimh.com is enabled; mirror account interoperability is not assumed.
    #[uniffi::constructor]
    pub fn new(
        context: SourceContext,
        token: Option<String>,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        Self::at(context, "https://cn.baozimh.com/".into(), token, config)
    }

    /// Official routes observed 2026-10-26 differ between host and phone networks.
    /// Use the exact origin that issued this browser session, never forward old cookies.
    #[uniffi::constructor]
    pub fn with_browser_session(context: SourceContext, token: String, cookie: String,
        user_agent: String, origin: String, config: SourceHttpConfig) -> Result<Self, SourceError> {
        if !matches!(origin.as_str(), "https://cn.bzmgcn.com/" | "https://cn.baozimh.com/" | "https://www.baozimh.com/") {
            return Err(SourceError::InvalidRequest);
        }
        if !valid_token(&token) || cookie.len() > 16384 || user_agent.is_empty()
            || user_agent.len() > 1024 || user_agent.contains(['\r', '\n']) {
            return Err(SourceError::InvalidRequest);
        }
        let parts: Vec<_> = cookie.split(';').map(str::trim).collect();
        if parts.iter().filter(|p| p.starts_with("TSID=")).count() != 1
            || !parts.contains(&format!("TSID={token}").as_str())
            || parts.iter().any(|part| {
                let Some((name, value)) = part.split_once('=') else { return true; };
                let allowed = name == "TSID" || name.strip_prefix("__Host-gk_")
                    .is_some_and(|suffix| !suffix.is_empty() && suffix.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_'));
                !allowed || !valid_token(value)
            }) {
            return Err(SourceError::InvalidRequest);
        }
        Self::validate_context(&context)?;
        let transport = SourceHttpSession::new(context, origin,
            None, Some(cookie), config)?.preserve_http_forbidden();
        Ok(Self { transport, authenticated: true, browser_user_agent: Some(user_agent) })
    }

    /// Does not persist the token, mark the account verified, or automatically retry sign-in.
    pub async fn sign_in(
        &self,
        context: SourceContext,
        username: String,
        password: String,
    ) -> Result<String, SourceError> {
        if self.authenticated {
            return Err(SourceError::InvalidRequest);
        }
        sign_in_token(
            self.transport
                .baozi_sign_in(context, credentials(&username, &password)?)
                .await?,
        )
    }

    /// Upstream provides one HTML snapshot; it does not prove complete large-library pagination.
    pub async fn bookshelf(&self, context: SourceContext) -> Result<SourceHttpReply, SourceError> {
        if !self.authenticated {
            return Err(SourceError::AuthenticationRequired);
        }
        if let Some(user_agent) = &self.browser_user_agent {
            return self.transport.baozi_browser_bookshelf(context, user_agent).await;
        }
        self.transport
            .get(context, "user/my_bookshelf".into())
            .await
    }

    pub fn invalidate(&self) {
        self.transport.invalidate();
    }
}

impl BaoziAccountClient {
    fn validate_context(context: &SourceContext) -> Result<(), SourceError> {
        let instance = context
            .source_id
            .strip_prefix("baozimh-account-")
            .unwrap_or_default();
        if instance.len() != 36
            || !instance.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
            || context
                .auth_ref
                .as_ref()
                .is_none_or(|value| value.is_empty())
        {
            return Err(SourceError::InvalidRequest);
        }
        Ok(())
    }

    fn at(
        context: SourceContext,
        base: String,
        token: Option<String>,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        Self::validate_context(&context)?;
        let url = Url::parse(&base).map_err(|_| SourceError::InvalidRequest)?;
        let production = base == "https://cn.baozimh.com/";
        let fixture = cfg!(test)
            && url.scheme() == "http"
            && matches!(url.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"));
        if !production && !fixture {
            return Err(SourceError::InvalidRequest);
        }
        if token.as_deref().is_some_and(|value| !valid_token(value)) {
            return Err(SourceError::InvalidRequest);
        }
        let authenticated = token.is_some();
        let transport = SourceHttpSession::new(
            context,
            base,
            None,
            token.map(|value| format!("TSID={value}")),
            config,
        )?
        .preserve_http_forbidden();
        Ok(Self {
            transport,
            authenticated,
            browser_user_agent: None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::source::SourceWorld;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::sync::mpsc;
    use std::time::Duration;

    fn context() -> SourceContext {
        SourceContext {
            world: SourceWorld::Sfw,
            source_id: "baozimh-account-11111111-2222-3333-4444-555555555555".into(),
            process_session: "fixture".into(),
            generation: 1,
            auth_ref: Some("opaque-account-instance".into()),
        }
    }
    fn config() -> SourceHttpConfig {
        SourceHttpConfig {
            timeout_ms: 1500,
            max_in_flight: 1,
            min_interval_ms: 0,
            max_retries: 2,
            max_body_bytes: 4096,
        }
    }
    fn runtime() -> tokio::runtime::Runtime {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
    }
    fn response(status: &str, content_type: &str, body: &str) -> String {
        format!("HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len())
    }
    // Test fixture reader: TCP can split both headers and body across reads.
    fn read_request(mut input: impl Read) -> Vec<u8> {
        let mut request = Vec::new();
        let mut bytes = [0; 8192];
        loop {
            let size = input.read(&mut bytes).unwrap();
            assert_ne!(size, 0, "fixture request ended before headers/body completed");
            request.extend_from_slice(&bytes[..size]);
            if let Some(end) = request.windows(4).position(|w| w == b"\r\n\r\n") {
                let header = String::from_utf8_lossy(&request[..end]);
                let length: usize = header
                    .lines()
                    .find_map(|line| {
                        line.to_lowercase()
                            .strip_prefix("content-length:")
                            .map(|v| v.trim().parse().unwrap())
                    })
                    .unwrap_or(0);
                if request.len() >= end + 4 + length {
                    break;
                }
            }
        }
        request
    }
    fn fixture(reply: String) -> (String, mpsc::Receiver<String>, std::thread::JoinHandle<()>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/", listener.local_addr().unwrap());
        let (tx, rx) = mpsc::channel();
        let task = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let request = read_request(&mut socket);
            tx.send(String::from_utf8(request).unwrap()).unwrap();
            socket.write_all(reply.as_bytes()).unwrap();
        });
        (url, rx, task)
    }

    #[test]
    fn fixture_reader_handles_fragmented_headers_and_body() {
        struct Fragmented<'a>(&'a [u8]);
        impl Read for Fragmented<'_> {
            fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
                let count = buffer.len().min(3).min(self.0.len());
                buffer[..count].copy_from_slice(&self.0[..count]);
                self.0 = &self.0[count..];
                Ok(count)
            }
        }
        let request = b"POST /fixture HTTP/1.1\r\nContent-Length: 6\r\n\r\nabcdef";
        assert_eq!(read_request(Fragmented(request)), request);
    }

    #[test]
    #[should_panic(expected = "fixture request ended before headers/body completed")]
    fn fixture_reader_rejects_truncated_body() {
        read_request(&b"POST /fixture HTTP/1.1\r\nContent-Length: 6\r\n\r\nabc"[..]);
    }

    #[test]
    fn account_profile_rejects_anonymous_private_world_and_foreign_hosts() {
        for ctx in [
            SourceContext {
                source_id: "baozimh-cn-v1".into(),
                ..context()
            },
            SourceContext {
                auth_ref: None,
                ..context()
            },
            SourceContext {
                world: SourceWorld::Nsfw,
                ..context()
            },
        ] {
            assert!(matches!(
                BaoziAccountClient::new(ctx, None, config()),
                Err(SourceError::InvalidRequest)
            ));
        }
        for host in [
            "https://cn.bzmgcn.com/",
            "https://cn.baozimh.com.evil.test/",
            "https://u:p@cn.baozimh.com/",
        ] {
            assert!(matches!(
                BaoziAccountClient::at(context(), host.into(), None, config()),
                Err(SourceError::InvalidRequest)
            ));
        }
        for token in ["", "a; second=token", "a\r\nHeader: value", "a\\b", "中文"] {
            assert!(matches!(
                BaoziAccountClient::new(context(), Some(token.into()), config()),
                Err(SourceError::InvalidRequest)
            ));
        }
    }

    #[test]
    fn signin_posts_multipart_without_cookie_and_returns_only_a_candidate_token() {
        let (url, request, server) = fixture(response(
            "200 OK",
            "application/json; charset=utf-8",
            r#"{"data":"synthetic-token_123"}"#,
        ));
        let client = BaoziAccountClient::at(context(), url, None, config()).unwrap();
        assert_eq!(
            runtime()
                .block_on(client.sign_in(context(), "合成账号".into(), "p&=+中文".into()))
                .unwrap(),
            "synthetic-token_123"
        );
        let request = request.recv_timeout(Duration::from_secs(3)).unwrap();
        assert!(request.starts_with("POST /api/bui/signin HTTP/1.1\r\n"));
        assert!(request.contains(CONTENT_TYPE));
        assert!(request.contains("name=\"username\"\r\n\r\n合成账号"));
        assert!(request.contains("name=\"password\"\r\n\r\np&=+中文"));
        let headers = request.split("\r\n\r\n").next().unwrap().to_lowercase();
        assert!(!headers.contains("cookie:") && !headers.contains("authorization:"));
        assert_eq!(client.transport.activity().attempts, 1);
        server.join().unwrap();
    }

    #[test]
    fn sign_in_is_not_retried_and_server_text_is_not_in_errors() {
        for (status, expected) in [
            (
                "503 Service Unavailable",
                SourceError::HttpStatus { status: 503 },
            ),
            (
                "429 Too Many Requests",
                SourceError::RateLimited {
                    retry_after_seconds: None,
                },
            ),
            ("401 Unauthorized", SourceError::AuthenticationRequired),
        ] {
            let (url, request, server) =
                fixture(response(status, "text/plain", "private-server-body"));
            let client = BaoziAccountClient::at(context(), url, None, config()).unwrap();
            let error = runtime()
                .block_on(client.sign_in(context(), "fixture".into(), "password".into()))
                .unwrap_err();
            assert_eq!(error, expected);
            assert!(!error.to_string().contains("private-server-body"));
            assert_eq!(client.transport.activity().attempts, 1);
            request.recv_timeout(Duration::from_secs(3)).unwrap();
            server.join().unwrap();
        }
    }

    #[test]
    fn shelf_sends_tsid_only_to_fixed_route_and_rejects_redirects() {
        for (reply, success) in [(response("200 OK", "text/html", "<div class='bookshelf-items'></div>"), true),
            ("HTTP/1.1 302 Found\r\nLocation: https://elsewhere.test/shelf\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".into(), false)] {
            let (url, request, server) = fixture(reply);
            let client = BaoziAccountClient::at(context(), url, Some("synthetic-tsid".into()), config()).unwrap();
            let result = runtime().block_on(client.bookshelf(context()));
            if success { assert_eq!(result.unwrap().context, context()); }
            else { assert!(matches!(result, Err(SourceError::HttpStatus {status: 302}))); }
            let request = request.recv_timeout(Duration::from_secs(3)).unwrap().to_lowercase();
            assert!(request.starts_with("get /user/my_bookshelf http/1.1\r\n"));
            assert!(request.contains("cookie: tsid=synthetic-tsid\r\n"));
            assert!(!request.contains("authorization:"));
            assert_eq!(client.transport.activity().attempts, 1);
            server.join().unwrap();
        }
    }

    #[test]
    fn token_shape_content_type_input_and_context_are_checked() {
        for (body, content_type, expected) in [
            (
                r#"{"data":""}"#,
                "application/json",
                SourceError::AuthenticationRequired,
            ),
            (
                r#"{"data":null}"#,
                "application/json",
                SourceError::AuthenticationRequired,
            ),
            (
                r#"{"data":{"token":"x"}}"#,
                "application/json",
                SourceError::InvalidResponse,
            ),
            (
                r#"{"data":"x;other=value"}"#,
                "application/json",
                SourceError::InvalidResponse,
            ),
            (r#"{"data":"x"}"#, "text/html", SourceError::InvalidResponse),
            ("[]", "application/json", SourceError::InvalidResponse),
        ] {
            assert_eq!(
                sign_in_token(SourceHttpReply {
                    context: context(),
                    body: body.as_bytes().to_vec(),
                    content_type: Some(content_type.into())
                })
                .unwrap_err(),
                expected
            );
        }
        assert!(credentials("", "x").is_err());
        assert!(credentials("x", &"p".repeat(4097)).is_err());
        assert!(credentials("x", BOUNDARY).is_err());
        let client = BaoziAccountClient::new(context(), None, config()).unwrap();
        runtime().block_on(async {
            assert!(matches!(
                client.bookshelf(context()).await,
                Err(SourceError::AuthenticationRequired)
            ));
            let mut stale = context();
            stale.generation += 1;
            assert!(matches!(
                client.sign_in(stale, "x".into(), "y".into()).await,
                Err(SourceError::SessionMismatch)
            ));
            client.invalidate();
            assert!(matches!(
                client.sign_in(context(), "x".into(), "y".into()).await,
                Err(SourceError::SessionMismatch)
            ));
        });
        assert_eq!(client.transport.activity().attempts, 0);
    }

    #[test]
    fn invalidating_account_cancels_response_and_releases_request() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/", listener.local_addr().unwrap());
        let (started, ready) = tokio::sync::oneshot::channel();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut buffer = [0; 8192];
            let request = read_request(&mut socket);
            assert!(request.starts_with(b"GET /user/my_bookshelf HTTP/1.1\r\n"));
            socket
                .write_all(
                    b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 1000\r\n\r\nx",
                )
                .unwrap();
            started.send(()).unwrap();
            assert_eq!(socket.read(&mut buffer).unwrap(), 0);
        });
        let client =
            BaoziAccountClient::at(context(), url, Some("synthetic".into()), config()).unwrap();
        runtime().block_on(async {
            let cancel = async {
                ready.await.unwrap();
                client.invalidate();
            };
            let (result, _) = tokio::join!(client.bookshelf(context()), cancel);
            assert!(matches!(result, Err(SourceError::SessionMismatch)));
        });
        server.join().unwrap();
        assert_eq!(client.transport.activity().pending, 0);
        assert_eq!(client.transport.activity().in_flight, 0);
    }
}
