//! Account protocol adapted from pinned fumiama/copymanga (GPL-3.0); see copymanga-NOTICE.txt.
use super::*;
use crate::transport::RequestBody;
#[derive(Deserialize, uniffi::Record)]
pub struct CopyAccountIdentity {
    #[serde(deserialize_with = "account_id")]
    pub user_id: String,
    pub username: String,
}
#[derive(Deserialize, uniffi::Record)]
pub struct CopyLoginCandidate {
    #[serde(deserialize_with = "account_id")]
    pub user_id: String,
    pub username: String,
    pub token: String,
}

// The upstream Gson String adapter accepts both JSON strings and integer IDs.
// Keep the same canonical account key without accepting floats, null or objects.
fn account_id<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<String, D::Error> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Id { Text(String), Number(u64) }
    Ok(match Id::deserialize(deserializer)? {
        Id::Text(value) => value,
        Id::Number(value) => value.to_string(),
    })
}
#[derive(Deserialize)]
struct Collected {
    uuid: u64,
    #[serde(default)]
    b_folder: bool,
    comic: Comic,
}
#[derive(Clone, uniffi::Record)]
pub struct CopyCollectedEntry {
    pub manga_id: String,
    pub title: String,
    pub collection_id: String,
    pub folder: bool,
}
#[derive(uniffi::Record)]
pub struct CopyCollectionPage {
    pub context: SourceContext,
    pub items: Vec<CopyCollectedEntry>,
    pub offset: u32,
    pub total: u32,
    pub next_offset: Option<u32>,
}
fn valid_token(token: &str) -> bool {
    !token.is_empty() && token.len() <= 4096 && token.bytes().all(|b| b.is_ascii_graphic())
}
fn valid_identity(id: &str, name: &str) -> bool {
    valid_id(id) && !name.trim().is_empty() && name.len() <= 256
}
fn parse_login(reply: SourceHttpReply) -> Result<CopyLoginCandidate, SourceError> {
    // Copy returns business rejections with HTTP 210, including a missing account.
    // Classify before decoding the success DTO; do not expose arbitrary response text.
    if reply.content_type.as_deref().is_some_and(|s|
        s.split(';').next().unwrap_or_default().trim().eq_ignore_ascii_case("application/json")) {
        let envelope: serde_json::Value = serde_json::from_slice(&reply.body)
            .map_err(|_| SourceError::InvalidResponse)?;
        if let Some(code) = envelope.get("code").and_then(|v| v.as_u64()).and_then(|n| u32::try_from(n).ok()) {
            if code != 200 {
                let message = envelope.get("message").and_then(|v| v.as_str()).unwrap_or_default();
                let reason = if ["MSG_ACCOUNT_NOT_FOUND", "賬戶不存在", "帳戶不存在", "账户不存在", "帳號不存在", "账号不存在", "用戶不存在", "用户不存在"].iter().any(|s| message.contains(s)) {
                    "account_missing"
                } else if ["密码错误", "密碼錯誤", "密码不正确", "密碼不正確"].iter().any(|s| message.contains(s)) {
                    "password_incorrect"
                } else if ["更新最新APP", "安裝正版", "安装正版"].iter().any(|s| message.contains(s)) {
                    "client_restricted"
                } else if message.contains("驗證碼") || message.contains("验证码") {
                    "verification_required"
                } else { "rejected" };
                return Err(SourceError::LoginRejected { code, reason: reason.into() });
            }
        }
    }
    parse(reply)
}
fn login_body(username: &str, encoded: &str, salt: u32, official: bool) -> Result<RequestBody, SourceError> {
    if username.trim().is_empty()
        || username.len() > 256
        || encoded.is_empty()
        || encoded.len() > 8192
        || salt >= 10000
        || !encoded
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"+/=".contains(&b))
    {
        return Err(SourceError::InvalidRequest);
    }
    let mut url = Url::parse("https://localhost/").unwrap();
    url.query_pairs_mut().append_pair("username", username)
        .append_pair("password", encoded).append_pair("salt", &salt.to_string());
    if official {
        url.query_pairs_mut().append_pair("authorization", "Token ");
    } else {
        url.query_pairs_mut().append_pair("platform", "3")
            .append_pair("version", "2024.4.28").append_pair("source", "Official");
    }
    Ok(RequestBody {
        bytes: url.query().unwrap().as_bytes().to_vec(),
        content_type: "application/x-www-form-urlencoded;charset=utf-8",
        retryable: false,
    })
}
impl CopyMangaSource {
    fn account_at(
        context: SourceContext,
        base_url: String,
        token: Option<String>,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        let suffix = context
            .source_id
            .strip_prefix("copymanga-account-")
            .unwrap_or_default();
        let base = Url::parse(&base_url).map_err(|_| SourceError::InvalidRequest)?;
        let local = base.scheme() == "http"
            && matches!(base.host_str(), Some("127.0.0.1" | "localhost" | "[::1]"));
        if context.world != SourceWorld::Sfw
            || context.auth_ref.as_ref().is_none_or(|r| r.is_empty())
            || suffix.len() != 36
            || !suffix.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
            || (!local && !matches!(base.as_str(), "https://api.2024manga.com/" | "https://mapi.copy-manga.com/" | "https://api.copy4000.com/"))
            || token.as_ref().is_some_and(|t| !valid_token(t))
        {
            return Err(SourceError::InvalidRequest);
        }
        let authenticated = token.is_some();
        let http = SourceHttpSession::copy_manga_account(context.clone(), base_url, token, config)?;
        Ok(Self {
            context,
            http,
            base,
            covers: Mutex::new(VecDeque::new()),
            images: Mutex::new(VecDeque::new()),
            closed: AtomicBool::new(false),
            authenticated,
        })
    }
    fn account_check(&self, context: &SourceContext) -> Result<(), SourceError> {
        self.check(context)?;
        if !self.authenticated {
            return Err(SourceError::AuthenticationRequired);
        }
        Ok(())
    }
}
#[uniffi::export]
impl CopyMangaSource {
    #[uniffi::constructor]
    pub fn with_account(
        context: SourceContext,
        token: Option<String>,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        Self::account_at(context, "https://api.2024manga.com/".into(), token, config)
    }
    /// Official 3.0.9 account service; never retry credentials against the legacy service.
    #[uniffi::constructor]
    pub fn with_official_account(context: SourceContext, token: Option<String>, config: SourceHttpConfig) -> Result<Self, SourceError> {
        // Official network2 route, explicitly approved by the user on 2026-10-26.
        // Canonical account identity remains unchanged across this routing update.
        Self::account_at(context, "https://mapi.copy-manga.com/".into(), token, config)
    }
    /// Fixed API used by mangacopy.com's mobile website; explicitly approved on 2026-10-26.
    /// Web tokens stay on that service and are not tried on the mobile-app API.
    #[uniffi::constructor]
    pub fn with_web_account(context: SourceContext, token: String, config: SourceHttpConfig) -> Result<Self, SourceError> {
        Self::account_at(context, "https://api.copy4000.com/".into(), Some(token), config)
    }
    pub async fn sign_in(
        &self,
        context: SourceContext,
        username: String,
        encoded_password: String,
        salt: u32,
    ) -> Result<CopyLoginCandidate, SourceError> {
        self.check(&context)?;
        if self.authenticated || self.context.auth_ref.is_none() {
            return Err(SourceError::InvalidRequest);
        }
        let candidate = parse_login(
            self.http
                .copy_sign_in(
                    context.clone(),
                    login_body(&username, &encoded_password, salt, self.base.host_str() == Some("mapi.copy-manga.com"))?,
                )
                .await?,
        )?;
        if !valid_token(&candidate.token)
            || !valid_identity(&candidate.user_id, &candidate.username)
        {
            return Err(SourceError::InvalidResponse);
        }
        self.check(&context)?;
        Ok(candidate)
    }
    pub async fn account_info(
        &self,
        context: SourceContext,
    ) -> Result<CopyAccountIdentity, SourceError> {
        self.account_check(&context)?;
        let platform = if self.base.host_str() == Some("api.copy4000.com") { 1 } else { 3 };
        let result: CopyAccountIdentity = parse(
            self.http
                .get(context.clone(), format!("api/v3/member/info?platform={platform}"))
                .await?,
        )?;
        if !valid_identity(&result.user_id, &result.username) {
            return Err(SourceError::InvalidResponse);
        }
        self.check(&context)?;
        Ok(result)
    }
    pub async fn cloud_collection(
        &self,
        context: SourceContext,
        offset: u32,
    ) -> Result<CopyCollectionPage, SourceError> {
        self.account_check(&context)?;
        if !offset.is_multiple_of(LIMIT) {
            return Err(SourceError::InvalidRequest);
        }
        let platform = if self.base.host_str() == Some("api.copy4000.com") { 1 } else { 3 };
        let page:Page<Collected>=parse(self.http.get(context.clone(),format!("api/v3/member/collect/comics?limit={LIMIT}&offset={offset}&free_type=1&ordering=-datetime_modifier&platform={platform}")).await?)?;
        let next = continuation(&page, offset)?;
        let next_offset = match next {
            SourceContinuation::End => None,
            _ => Some(
                offset
                    .checked_add(LIMIT)
                    .ok_or(SourceError::InvalidResponse)?,
            ),
        };
        let mut items = Vec::with_capacity(page.list.len());
        for item in page.list {
            if item.comic.name.len() > 4096 {
                return Err(SourceError::InvalidResponse);
            }
            let manga = self.manga(item.comic)?;
            items.push(CopyCollectedEntry {
                manga_id: manga.key.manga_id,
                title: manga.title,
                collection_id: item.uuid.to_string(),
                folder: item.b_folder,
            });
        }
        self.check(&context)?;
        Ok(CopyCollectionPage {
            context,
            items,
            offset,
            total: page.total,
            next_offset,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::io::{Read, Write};
    fn context() -> SourceContext {
        SourceContext {
            world: SourceWorld::Sfw,
            source_id: "copymanga-account-00000000-0000-0000-0000-000000000001".into(),
            process_session: "synthetic".into(),
            generation: 1,
            auth_ref: Some("opaque".into()),
        }
    }
    fn source(base: String, token: Option<&str>, retries: u32) -> CopyMangaSource {
        CopyMangaSource::account_at(
            context(),
            base,
            token.map(str::to_owned),
            SourceHttpConfig {
                timeout_ms: 2000,
                max_in_flight: 1,
                min_interval_ms: 0,
                max_retries: retries,
                max_body_bytes: 65536,
            },
        )
        .unwrap()
    }
    fn run<T>(future: impl std::future::Future<Output = T>) -> T {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(future)
    }
    fn server(
        rows: Vec<(String, u16, serde_json::Value)>,
        token: bool,
    ) -> (String, std::thread::JoinHandle<()>) {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", listener.local_addr().unwrap());
        let job = std::thread::spawn(move || {
            for (path, status, body) in rows {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                    .unwrap();
                let mut bytes = Vec::new();
                while !bytes.ends_with(b"\r\n\r\n") {
                    let mut b = [0];
                    socket.read_exact(&mut b).unwrap();
                    bytes.push(b[0]);
                }
                let header = String::from_utf8(bytes).unwrap();
                let lower = header.to_lowercase();
                assert!(header.lines().next().unwrap().contains(&path), "{header}");
                assert!(lower.contains(if token {
                    "authorization: token fixture-token\r\n"
                } else {
                    "authorization: token\r\n"
                }));
                assert!(!lower.contains("cookie:"));
                assert!(!lower.contains("x-api-key:"));
                assert!(lower.contains("platform: 3"));
                assert!(lower.contains("version: 2024.4.28"));
                let n = lower
                    .lines()
                    .find_map(|l| {
                        l.strip_prefix("content-length: ")
                            .map(|n| n.parse::<usize>().unwrap())
                    })
                    .unwrap_or(0);
                let mut data = vec![0; n];
                socket.read_exact(&mut data).unwrap();
                if path.contains("login?") {
                    assert!(header.starts_with("POST "));
                    let text = String::from_utf8(data).unwrap();
                    let form = Url::parse(&format!("https://fixture.invalid/?{text}"))
                        .unwrap()
                        .query_pairs()
                        .into_owned()
                        .collect::<std::collections::HashMap<_, _>>();
                    assert_eq!(form["username"], "u+&名");
                    assert_eq!(form["password"], "+w==");
                    assert_eq!(form["salt"], "9");
                    assert_eq!(form["platform"], "3");
                    assert_eq!(form["version"], "2024.4.28");
                    assert_eq!(form["source"], "Official");
                }
                let body = body.to_string();
                write!(socket,"HTTP/1.1 {status} Test\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).unwrap();
            }
        });
        (base, job)
    }
    fn page(offset: u32, total: u32, list: serde_json::Value) -> serde_json::Value {
        json!({"code":200,"results":{"limit":21,"offset":offset,"total":total,"list":list}})
    }
    fn path(offset: u32) -> String {
        format!("/api/v3/member/collect/comics?limit=21&offset={offset}&free_type=1&ordering=-datetime_modifier&platform=3")
    }
    fn entry(id: u64) -> serde_json::Value {
        json!({"uuid":id,"b_folder":true,"comic":{"path_word":"comic-path","name":"合成作品"},"last_browse":{"chapter_id":"ignored","page":7}})
    }
    #[test]
    fn login_form_preserves_plus_unicode_and_does_not_retry_post() {
        let (base, job) = server(
            vec![(
                "/api/v3/login?platform=3".into(),
                200,
                json!({"code":200,"results":{"user_id":"u1","username":"合成","token":"fixture-token"}}),
            )],
            false,
        );
        let candidate =
            run(source(base, None, 2).sign_in(context(), "u+&名".into(), "+w==".into(), 9))
                .unwrap();
        assert_eq!(candidate.user_id, "u1");
        assert_eq!(candidate.token, "fixture-token");
        job.join().unwrap();
        let (base, job) = server(
            vec![("/api/v3/login?platform=3".into(), 503, json!({}))],
            false,
        );
        assert!(matches!(
            run(source(base, None, 2).sign_in(context(), "u+&名".into(), "+w==".into(), 9)),
            Err(SourceError::HttpStatus { status: 503 })
        ));
        job.join().unwrap();
        assert!(login_body("u", "YWJj", 10000, false).is_err());
        assert!(login_body("u", "bad\n", 1, false).is_err());
    }
    #[test]
    fn identity_and_filtered_collection_keep_membership_and_short_empty_page_offsets() {
        let (base, job) = server(
            vec![
                (
                    "/api/v3/member/info?platform=3".into(),
                    200,
                    json!({"code":200,"results":{"user_id":"u1","username":"合成"}}),
                ),
                (path(0), 200, page(0, 43, json!([entry(9)]))),
                (path(21), 200, page(21, 43, json!([]))),
                (path(42), 200, page(42, 43, json!([entry(10)]))),
            ],
            true,
        );
        let source = source(base, Some("fixture-token"), 0);
        run(async {
            assert_eq!(source.account_info(context()).await.unwrap().user_id, "u1");
            let first = source.cloud_collection(context(), 0).await.unwrap();
            assert_eq!(first.next_offset, Some(21));
            assert_eq!(first.items[0].manga_id, "comic-path");
            assert_eq!(first.items[0].collection_id, "9");
            assert!(first.items[0].folder);
            assert_eq!(
                source
                    .cloud_collection(context(), 21)
                    .await
                    .unwrap()
                    .next_offset,
                Some(42)
            );
            assert!(source
                .cloud_collection(context(), 42)
                .await
                .unwrap()
                .next_offset
                .is_none());
        });
        job.join().unwrap();
    }
    #[test]
    fn malformed_page_missing_comic_and_auth_failure_are_not_empty_success() {
        for body in [
            json!({"code":401}),
            page(0, 1, json!([{"uuid":1}])),
            page(21, 1, json!([])),
            json!({"code":200,"results":{"limit":30,"offset":0,"total":0,"list":[]}}),
        ] {
            let (base, job) = server(vec![(path(0), 200, body)], true);
            assert!(
                run(source(base, Some("fixture-token"), 0).cloud_collection(context(), 0)).is_err()
            );
            job.join().unwrap();
        }
        let (base, job) = server(vec![(path(0), 200, page(0, 0, json!([])))], true);
        let result =
            run(source(base, Some("fixture-token"), 0).cloud_collection(context(), 0)).unwrap();
        assert!(result.items.is_empty() && result.next_offset.is_none());
        job.join().unwrap();
    }
    #[test]
    fn account_constructor_and_requests_reject_wrong_scope_generation_and_closed_client() {
        let source = source("http://127.0.0.1:1/".into(), Some("fixture-token"), 0);
        let mut stale = context();
        stale.generation += 1;
        assert!(matches!(
            run(source.cloud_collection(stale, 0)),
            Err(SourceError::SessionMismatch)
        ));
        assert!(matches!(
            run(source.cloud_collection(context(), 1)),
            Err(SourceError::InvalidRequest)
        ));
        source.invalidate();
        assert!(run(source.account_info(context())).is_err());
        let anonymous = source_without_token();
        assert!(matches!(
            run(anonymous.account_info(context())),
            Err(SourceError::AuthenticationRequired)
        ));
    }
    #[test]
    fn account_cover_uses_anonymous_cdn_client_and_releases_only_owned_file() {
        let cdn = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let url = format!("http://{}/cover.png", cdn.local_addr().unwrap());
        let cdn_job = std::thread::spawn(move || {
            let (mut socket, _) = cdn.accept().unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                .unwrap();
            let mut bytes = Vec::new();
            while !bytes.ends_with(b"\r\n\r\n") {
                let mut b = [0];
                socket.read_exact(&mut b).unwrap();
                bytes.push(b[0]);
            }
            let header = String::from_utf8(bytes).unwrap().to_lowercase();
            for name in [
                "authorization:",
                "cookie:",
                "x-api-key:",
                "platform:",
                "version:",
                "x-requested-with:",
            ] {
                assert!(!header.contains(name));
            }
            socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 9\r\nConnection: close\r\n\r\nsynthetic").unwrap();
        });
        let mut item = entry(1);
        item["comic"]["cover"] = json!(url);
        let (base, job) = server(vec![(path(0), 200, page(0, 1, json!([item])))], true);
        let source = source(base, Some("fixture-token"), 0);
        let directory = std::env::temp_dir().join(format!(
            "flux-copy-account-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        let original = directory.join("original.cbz");
        std::fs::write(&original, b"preserved").unwrap();
        run(async {
            let entries = source.cloud_collection(context(), 0).await.unwrap();
            let image = source
                .cover_file(
                    context(),
                    SourceMangaKey {
                        world: SourceWorld::Sfw,
                        source_id: context().source_id,
                        manga_id: entries.items[0].manga_id.clone(),
                    },
                    directory.to_str().unwrap().into(),
                )
                .await
                .unwrap();
            let path = image.path();
            assert_eq!(std::fs::read(&path).unwrap(), b"synthetic");
            drop(image);
            assert!(!std::path::Path::new(&path).exists());
            assert_eq!(std::fs::read(&original).unwrap(), b"preserved");
        });
        job.join().unwrap();
        cdn_job.join().unwrap();
        std::fs::remove_file(original).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
    fn source_without_token() -> CopyMangaSource {
        source("http://127.0.0.1:1/".into(), None, 0)
    }
}
