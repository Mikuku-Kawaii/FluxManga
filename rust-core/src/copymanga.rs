//! CopyManga metadata foundation. GPL-3.0 protocol adaptation from fumiama/copymanga
//! 0fdd3f4489c8ec02f41b4a570aba3b11acfb889b; modified 2026-10-21 for FluxManga.
//! See app/src/main/assets/licenses/copymanga-{NOTICE.txt,GPL-3.0.txt}.
use crate::image_file::SourceImageFile;
use crate::{
    source::*,
    transport::{SourceHttpConfig, SourceHttpReply, SourceHttpSession},
};
use reqwest::Url;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, Ordering::SeqCst};
use std::{
    collections::{BTreeMap, VecDeque},
    sync::Mutex,
};

const LIMIT: u32 = 21;
#[derive(Deserialize)]
struct Envelope<T> {
    code: u32,
    results: Option<T>,
}
#[derive(Deserialize)]
struct Comic {
    path_word: String,
    name: String,
    brief: Option<String>,
    cover: Option<String>,
    reclass: Option<serde_json::Value>,
    theme: Option<Vec<CopyTheme>>,
}
#[derive(Clone, Debug, Deserialize, uniffi::Record)]
pub struct CopyTheme {
    pub name: String,
    pub path_word: String,
}
#[derive(Deserialize)]
struct Themes {
    theme: Vec<CopyTheme>,
}
fn checked_themes(items: Vec<CopyTheme>) -> Result<Vec<CopyTheme>, SourceError> {
    if items.len() > 1024
        || items.iter().any(|t| {
            t.name.trim().is_empty()
                || t.name.len() > 512
                || t.path_word.is_empty()
                || t.path_word.len() > 512
        })
    {
        return Err(SourceError::InvalidResponse);
    }
    let mut seen = std::collections::HashSet::new();
    Ok(items
        .into_iter()
        .filter(|t| seen.insert(t.path_word.clone()))
        .collect())
}
#[derive(Deserialize)]
struct ComicEntry {
    comic: Comic,
}
// Optional update-list information must not make an otherwise readable card fail.
#[derive(Deserialize)]
struct UpdateComic {
    #[serde(flatten)]
    base: Comic,
    #[serde(default)]
    datetime_updated: serde_json::Value,
}
fn update_text(value: &serde_json::Value) -> Option<String> {
    value.as_str().map(str::trim).filter(|s| !s.is_empty() && s.len() <= 512).map(str::to_owned)
}
#[derive(Deserialize)]
struct Page<T> {
    total: u32,
    limit: u32,
    offset: u32,
    list: Vec<T>,
}
#[derive(Deserialize)]
struct DetailComic {
    #[serde(flatten)]
    base: Comic,
    #[serde(default)]
    author: serde_json::Value,
    #[serde(default)]
    status: serde_json::Value,
    #[serde(default)]
    datetime_updated: serde_json::Value,
}
#[derive(Deserialize)]
struct Detail {
    comic: DetailComic,
    groups: Option<BTreeMap<String, Group>>,
}
#[derive(Deserialize)]
struct Group {
    path_word: String,
    name: String,
    count: Option<u32>,
}
#[derive(Deserialize)]
struct Chapter {
    uuid: String,
    name: Option<String>,
    comic_path_word: String,
    group_path_word: String,
}
#[derive(Deserialize)]
struct ChapterDetail {
    comic: Comic,
}
#[derive(Deserialize)]
struct ChapterBody {
    comic: Comic,
    chapter: ChapterContent,
}
#[derive(Deserialize)]
struct ChapterContent {
    uuid: String,
    comic_path_word: String,
    contents: Vec<ImageContent>,
    words: Option<Vec<usize>>,
}
#[derive(Clone, Deserialize)]
struct ImageContent {
    uuid: Option<String>,
    url: String,
}
struct ChapterImages {
    manga: String,
    chapter: String,
    pages: Vec<(String, Url)>,
}

fn ordered_images(
    base: &Url,
    key: &SourceMangaKey,
    chapter: &str,
    body: ChapterBody,
) -> Result<Vec<(String, Url)>, SourceError> {
    let content = body.chapter;
    if body.comic.path_word != key.manga_id
        || content.comic_path_word != key.manga_id
        || content.uuid != chapter
    {
        return Err(SourceError::InvalidResponse);
    }
    let count = content.contents.len();
    if count == 0 || count > 4096 {
        return Err(SourceError::InvalidResponse);
    }
    let words = content.words.unwrap_or_else(|| (0..count).collect());
    if words.len() != count {
        return Err(SourceError::InvalidResponse);
    }
    let mut ordered = vec![None; count];
    let mut ids = std::collections::HashSet::new();
    for (image, ordinal) in content.contents.into_iter().zip(words) {
        if ordinal >= count || ordered[ordinal].is_some() {
            return Err(SourceError::InvalidResponse);
        }
        let id = match image.uuid.filter(|s| !s.is_empty()) {
            Some(id) if valid_id(&id) => format!("{chapter}:uuid:{id}"),
            Some(_) => return Err(SourceError::InvalidResponse),
            None => format!("{chapter}:page:{}", ordinal + 1),
        };
        if !ids.insert(id.clone()) {
            return Err(SourceError::InvalidResponse);
        }
        ordered[ordinal] = Some((id, cover_url(base, &image.url)?));
    }
    ordered
        .into_iter()
        .map(|p| p.ok_or(SourceError::InvalidResponse))
        .collect()
}

#[derive(Clone, uniffi::Record)]
pub struct CopyChapterGroup {
    pub manga: SourceMangaKey,
    pub id: String,
    pub title: String,
    pub chapter_count: Option<u32>,
}
#[derive(uniffi::Record)]
pub struct CopyMangaBook {
    pub detail: SourceDetail,
    pub groups: Option<Vec<CopyChapterGroup>>,
    pub themes: Option<Vec<CopyTheme>>,
}
#[derive(Serialize, Deserialize)]
struct ChapterCursor {
    manga: String,
    group: String,
    number: u32,
}
#[derive(Clone)]
enum CoverLocation {
    Missing,
    Invalid,
    Url(Url),
}

fn cover_url(base: &Url, raw: &str) -> Result<Url, SourceError> {
    if raw.len() > 8192 {
        return Err(SourceError::InvalidResponse);
    }
    let url = Url::parse(raw).map_err(|_| SourceError::InvalidResponse)?;
    if !url.username().is_empty() || url.password().is_some() || url.fragment().is_some() {
        return Err(SourceError::InvalidResponse);
    }
    let host = url.host_str().unwrap_or_default();
    let parts: Vec<_> = host.split('.').collect();
    let cdn = parts.len() == 3
        && !parts[0].is_empty()
        && parts[0]
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
        && parts[1]
            .strip_prefix("mangafun")
            .is_some_and(|s| s.len() == 1 && s.as_bytes()[0].is_ascii_lowercase())
        && matches!(parts[2], "xyz" | "fun");
    let local = |u: &Url| {
        u.scheme() == "http" && matches!(u.host_str(), Some("127.0.0.1" | "[::1]" | "localhost"))
    };
    if (url.scheme() == "https"
        && url.port_or_known_default() == Some(443)
        && (cdn || url.origin() == base.origin()))
        || (local(base) && local(&url))
    {
        Ok(url)
    } else {
        Err(SourceError::InvalidResponse)
    }
}

fn parse<T: DeserializeOwned>(reply: SourceHttpReply) -> Result<T, SourceError> {
    if !reply.content_type.as_deref().is_some_and(|s| {
        s.split(';')
            .next()
            .unwrap_or_default()
            .trim()
            .eq_ignore_ascii_case("application/json")
    }) {
        return Err(SourceError::InvalidResponse);
    }
    // Decode business status before results: errors may use a different results shape.
    #[derive(Deserialize)]
    struct Status {
        code: u32,
        #[serde(default)]
        message: String,
    }
    let status: Status =
        serde_json::from_slice(&reply.body).map_err(|_| SourceError::InvalidResponse)?;
    match status.code {
        200 => {}
        210 if ["更新最新APP", "安裝正版", "安装正版"].iter().any(|text| status.message.contains(text)) => {
            return Err(SourceError::LoginRejected { code: 210, reason: "client_restricted".into() });
        }
        401 | 403 | 449 => return Err(SourceError::AuthenticationRequired),
        404 => return Err(SourceError::NotFound),
        429 => {
            return Err(SourceError::RateLimited {
                retry_after_seconds: None,
            })
        }
        _ => return Err(SourceError::InvalidResponse),
    }
    let value: Envelope<T> =
        serde_json::from_slice(&reply.body).map_err(|_| SourceError::InvalidResponse)?;
    if value.code != 200 {
        return Err(SourceError::InvalidResponse);
    }
    value.results.ok_or(SourceError::InvalidResponse)
}
fn offset(page: SourcePageRequest) -> Result<u32, SourceError> {
    offset_limit(page, LIMIT)
}
fn offset_limit(page: SourcePageRequest, size: u32) -> Result<u32, SourceError> {
    match page {
        SourcePageRequest::Start => Ok(0),
        SourcePageRequest::Page { number } if number > 0 => (number - 1)
            .checked_mul(size)
            .ok_or(SourceError::InvalidRequest),
        _ => Err(SourceError::InvalidRequest),
    }
}
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 256
        && id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
}
fn continuation<T>(page: &Page<T>, expected: u32) -> Result<SourceContinuation, SourceError> {
    continuation_limit(page, expected, LIMIT)
}
fn continuation_limit<T>(
    page: &Page<T>,
    expected: u32,
    size: u32,
) -> Result<SourceContinuation, SourceError> {
    if page.offset != expected
        || page.limit != size
        || page.list.len() > size as usize
        || (!page.list.is_empty()
            && page.offset as u64 + page.list.len() as u64 > page.total as u64)
    {
        return Err(SourceError::InvalidResponse);
    }
    if expected as u64 + size as u64 >= page.total as u64 {
        Ok(SourceContinuation::End)
    } else {
        Ok(SourceContinuation::Next {
            request: SourcePageRequest::Page {
                number: expected / size + 2,
            },
        })
    }
}

#[derive(uniffi::Object)]
pub struct CopyMangaSource {
    context: SourceContext,
    http: SourceHttpSession,
    base: Url,
    covers: Mutex<VecDeque<(String, CoverLocation)>>,
    images: Mutex<VecDeque<ChapterImages>>,
    closed: AtomicBool,
    authenticated: bool,
}
impl CopyMangaSource {
    fn platform(&self) -> u8 {
        if self.base.host_str() == Some("api.copy4000.com") { 1 } else { 3 }
    }
    fn check(&self, context: &SourceContext) -> Result<(), SourceError> {
        if context != &self.context || self.closed.load(SeqCst) {
            Err(SourceError::SessionMismatch)
        } else {
            Ok(())
        }
    }
    fn manga(&self, comic: Comic) -> Result<SourceManga, SourceError> {
        if !valid_id(&comic.path_word) || comic.name.trim().is_empty() {
            return Err(SourceError::InvalidResponse);
        }
        let location = match comic.cover.as_deref().filter(|s| !s.is_empty()) {
            None => CoverLocation::Missing,
            Some(raw) => cover_url(&self.base, raw)
                .map(CoverLocation::Url)
                .unwrap_or(CoverLocation::Invalid),
        };
        self.remember_cover(&comic.path_word, location)?;
        Ok(SourceManga {
            key: SourceMangaKey {
                world: self.context.world.clone(),
                source_id: self.context.source_id.clone(),
                manga_id: comic.path_word,
            },
            title: comic.name,
            page_count: None,
            updated_at: None,
            latest_chapter: None,
        })
    }
    fn key_check(&self, context: &SourceContext, key: &SourceMangaKey) -> Result<(), SourceError> {
        self.check(context)?;
        if key.world != context.world || key.source_id != context.source_id {
            return Err(SourceError::SessionMismatch);
        }
        if !valid_id(&key.manga_id) {
            return Err(SourceError::InvalidRequest);
        }
        Ok(())
    }
    fn remember_cover(&self, id: &str, location: CoverLocation) -> Result<(), SourceError> {
        let mut covers = self.covers.lock().map_err(|_| SourceError::Transport)?;
        self.check(&self.context)?;
        covers.retain(|(key, _)| key != id);
        if covers.len() == 128 {
            covers.pop_front();
        }
        covers.push_back((id.to_owned(), location));
        Ok(())
    }
    fn known_cover(&self, id: &str) -> Result<Option<CoverLocation>, SourceError> {
        let covers = self.covers.lock().map_err(|_| SourceError::Transport)?;
        Ok(covers
            .iter()
            .find(|(key, _)| key == id)
            .map(|(_, value)| value.clone()))
    }
    fn page(
        &self,
        context: SourceContext,
        page: Page<Comic>,
        expected: u32,
    ) -> Result<SourceSearchPage, SourceError> {
        self.check(&context)?;
        let continuation = continuation(&page, expected)?;
        let items = page
            .list
            .into_iter()
            .map(|c| self.manga(c))
            .collect::<Result<Vec<_>, _>>()?;
        Ok(SourceSearchPage {
            context,
            items,
            continuation,
        })
    }
}
#[uniffi::export]
impl CopyMangaSource {
    #[uniffi::constructor]
    pub fn new(
        context: SourceContext,
        base_url: String,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        // Public anonymous endpoint only; no account or Komga credential scope is accepted.
        if context.auth_ref.is_some() {
            return Err(SourceError::InvalidRequest);
        }
        let base = Url::parse(&base_url).map_err(|_| SourceError::InvalidRequest)?;
        let http = SourceHttpSession::copy_manga(context.clone(), base_url, config)?;
        Ok(Self {
            context,
            http,
            base,
            covers: Mutex::new(VecDeque::new()),
            images: Mutex::new(VecDeque::new()),
            closed: AtomicBool::new(false),
            authenticated: false,
        })
    }
    pub fn invalidate(&self) {
        self.closed.store(true, SeqCst);
        self.http.invalidate();
        if let Ok(mut images) = self.images.lock() {
            images.clear();
        }
        if let Ok(mut covers) = self.covers.lock() {
            covers.clear();
        }
    }
    pub fn capabilities(&self) -> SourceCapabilities {
        SourceCapabilities {
            search: true,
            recommendations: true,
            updates: true,
            tags: true,
            detail: true,
            chapters: true,
            pages: true,
            pagination: SourcePagination::PageNumber,
        }
    }
    pub async fn recommendations(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        self.check(&context)?;
        let expected = offset(page)?;
        let page: Page<ComicEntry> = parse(
            self.http
                .get(
                    context.clone(),
                    format!("api/v3/recs?pos=3200102&limit={LIMIT}&offset={expected}&platform={}", self.platform()),
                )
                .await?,
        )?;
        self.page(
            context,
            Page {
                total: page.total,
                limit: page.limit,
                offset: page.offset,
                list: page.list.into_iter().map(|r| r.comic).collect(),
            },
            expected,
        )
    }
    pub async fn updates(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        self.check(&context)?;
        let expected = offset(page)?;
        // Use the reading API's update ordering; the separate website feed may be blocked.
        let page: Page<UpdateComic> = parse(
            self.http.get(context.clone(), format!(
                "api/v3/comics?limit={LIMIT}&offset={expected}&ordering=-datetime_updated&platform={}",
                self.platform()
            )).await?
        )?;
        let continuation = continuation(&page, expected)?;
        let items = page.list.into_iter().map(|entry| {
            let mut manga = self.manga(entry.base)?;
            manga.updated_at = update_text(&entry.datetime_updated);
            Ok(manga)
        }).collect::<Result<Vec<_>, SourceError>>()?;
        self.check(&context)?;
        Ok(SourceSearchPage { context, items, continuation })
    }
    pub async fn search(
        &self,
        context: SourceContext,
        query: String,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        self.check(&context)?;
        if query.trim().is_empty() || query.len() > 4096 {
            return Err(SourceError::InvalidRequest);
        }
        let expected = offset(page)?;
        let (term, query_type) = match query.strip_prefix("author:") {
            Some(author) if !author.trim().is_empty() => (author.trim(), "author"),
            _ => (query.as_str(), ""),
        };
        let query =
            reqwest::Url::parse_with_params("https://query.invalid/", &[("q", term)])
                .map_err(|_| SourceError::InvalidRequest)?;
        let page: Page<Comic> = parse(
            self.http
                .get(
                    context.clone(),
                    format!(
                        "api/v3/search/comic?limit={LIMIT}&offset={expected}&{}&q_type={query_type}&platform={}",
                        query.query().unwrap_or_default(), self.platform()
                    ),
                )
                .await?,
        )?;
        self.page(context, page, expected)
    }
    pub async fn themes(&self, context: SourceContext) -> Result<Vec<CopyTheme>, SourceError> {
        self.check(&context)?;
        let result: Themes = parse(
            self.http
                .get(
                    context.clone(),
                    format!("api/v3/h5/filterIndex/comic/tags?type=1&platform={}", self.platform()),
                )
                .await?,
        )?;
        let themes = checked_themes(result.theme)?;
        self.check(&context)?;
        Ok(themes)
    }
    pub async fn themed(
        &self,
        context: SourceContext,
        theme: String,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        self.check(&context)?;
        if theme.is_empty() || theme.len() > 512 {
            return Err(SourceError::InvalidRequest);
        }
        let expected = offset(page)?;
        let query = Url::parse_with_params("https://query.invalid/", &[("theme", theme.as_str())])
            .map_err(|_| SourceError::InvalidRequest)?;
        let result: Page<Comic> = parse(self.http.get(context.clone(), format!("api/v3/comics?limit={LIMIT}&offset={expected}&ordering=-datetime_updated&{}&platform={}", query.query().unwrap_or_default(), self.platform())).await?)?;
        self.page(context, result, expected)
    }
    pub async fn detail(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
    ) -> Result<SourceDetail, SourceError> {
        Ok(self.book(context, key).await?.detail)
    }
    pub async fn book(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
    ) -> Result<CopyMangaBook, SourceError> {
        self.key_check(&context, &key)?;
        let detail: Detail = parse(
            self.http
                .get(
                    context.clone(),
                    format!("api/v3/comic2/{}?platform={}", key.manga_id, self.platform()),
                )
                .await?,
        )?;
        if detail.comic.base.path_word != key.manga_id {
            return Err(SourceError::InvalidResponse);
        }
        let themes = detail.comic.base.theme.clone().map(checked_themes).transpose()?;
        let tags = themes
            .as_ref()
            .map(|items| items.iter().map(|t| t.name.clone()).collect());
        let description = detail.comic.base.brief.clone().filter(|s| !s.trim().is_empty());
        let groups = detail
            .groups
            .map(|map| {
                let mut groups = Vec::new();
                for value in map.into_values() {
                    if !valid_id(&value.path_word)
                        || value.name.trim().is_empty()
                        || groups
                            .iter()
                            .any(|g: &CopyChapterGroup| g.id == value.path_word)
                    {
                        return Err(SourceError::InvalidResponse);
                    }
                    groups.push(CopyChapterGroup {
                        manga: key.clone(),
                        id: value.path_word,
                        title: value.name,
                        chapter_count: value.count,
                    });
                }
                groups.sort_by(|a, b| a.id.cmp(&b.id));
                Ok(groups)
            })
            .transpose()?;
        let metadata = crate::detail_metadata::copy_metadata(&detail.comic.author, &detail.comic.status, &detail.comic.datetime_updated);
        let manga = self.manga(detail.comic.base)?;
        self.check(&context)?;
        Ok(CopyMangaBook {
            detail: SourceDetail {
                context,
                manga,
                description,
                tags,
                metadata,
            },
            groups,
            themes,
        })
    }
    pub async fn group_chapters(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        group_id: String,
        page: SourcePageRequest,
    ) -> Result<SourceChapterPage, SourceError> {
        self.key_check(&context, &key)?;
        if !valid_id(&group_id) {
            return Err(SourceError::InvalidRequest);
        }
        let expected = offset_limit(page, 100)?;
        let page: Page<Chapter> = parse(self.http.get(context.clone(), format!("api/v3/comic/{}/group/{group_id}/chapters?limit=100&offset={expected}&platform={}", key.manga_id, self.platform())).await?)?;
        let continuation = continuation_limit(&page, expected, 100)?;
        let mut items = Vec::new();
        for (index, chapter) in page.list.into_iter().enumerate() {
            if !valid_id(&chapter.uuid)
                || chapter.comic_path_word != key.manga_id
                || chapter.group_path_word != group_id
                || items.iter().any(|c: &SourceChapter| c.id == chapter.uuid)
            {
                return Err(SourceError::InvalidResponse);
            }
            items.push(SourceChapter {
                manga: key.clone(),
                id: chapter.uuid,
                title: chapter.name.unwrap_or_default(),
                ordinal: expected
                    .checked_add(index as u32 + 1)
                    .ok_or(SourceError::InvalidResponse)?,
                page_count: None,
            });
        }
        self.check(&context)?;
        Ok(SourceChapterPage {
            context,
            items,
            continuation,
            total: Some(page.total),
        })
    }
    pub async fn chapters(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        page: SourcePageRequest,
    ) -> Result<SourceChapterPage, SourceError> {
        MangaSource::chapters(self, context, key, page).await
    }
    pub async fn pages(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        chapter: String,
        page: SourcePageRequest,
    ) -> Result<SourcePages, SourceError> {
        self.key_check(&context, &key)?;
        if !valid_id(&chapter)
            || !matches!(
                page,
                SourcePageRequest::Start | SourcePageRequest::Page { number: 1 }
            )
        {
            return Err(SourceError::InvalidRequest);
        }
        // Upstream Book.version selects the legacy endpoint for reclassified works.
        let detail: ChapterDetail = parse(
            self.http
                .get(
                    context.clone(),
                    format!("api/v3/comic2/{}?platform={}", key.manga_id, self.platform()),
                )
                .await?,
        )?;
        if detail.comic.path_word != key.manga_id {
            return Err(SourceError::InvalidResponse);
        }
        let suffix = if detail.comic.reclass.is_some() {
            ""
        } else {
            "2"
        };
        let body: ChapterBody = parse(
            self.http
                .get(
                    context.clone(),
                    format!(
                        "api/v3/comic/{}/chapter{suffix}/{chapter}?platform={}",
                        key.manga_id, self.platform()
                    ),
                )
                .await?,
        )?;
        let pages = ordered_images(&self.base, &key, &chapter, body)?;
        self.check(&context)?;
        let items = pages
            .iter()
            .enumerate()
            .map(|(index, (id, _))| SourcePageResource {
                manga: key.clone(),
                chapter_id: chapter.clone(),
                id: id.clone(),
                ordinal: index as u32 + 1,
                uri: format!("copymanga-page:{id}"),
                mime_type: None,
                width: None,
                height: None,
                context: context.clone(),
            })
            .collect();
        let mut images = self.images.lock().map_err(|_| SourceError::Transport)?;
        self.check(&context)?;
        images.retain(|c| c.manga != key.manga_id || c.chapter != chapter);
        if images.len() >= 2 {
            images.pop_front();
        }
        images.push_back(ChapterImages {
            manga: key.manga_id,
            chapter,
            pages,
        });
        Ok(SourcePages {
            context,
            items,
            continuation: SourceContinuation::End,
        })
    }
    pub async fn page_file(
        &self,
        context: SourceContext,
        page: SourcePageResource,
        directory: String,
    ) -> Result<SourceImageFile, SourceError> {
        self.key_check(&context, &page.manga)?;
        if page.context != context {
            return Err(SourceError::SessionMismatch);
        }
        let url = {
            let images = self.images.lock().map_err(|_| SourceError::Transport)?;
            let chapter = images
                .iter()
                .find(|c| c.manga == page.manga.manga_id && c.chapter == page.chapter_id)
                .ok_or(SourceError::InvalidRequest)?;
            let (id, url) = chapter
                .pages
                .get(
                    page.ordinal
                        .checked_sub(1)
                        .ok_or(SourceError::InvalidRequest)? as usize,
                )
                .ok_or(SourceError::InvalidRequest)?;
            if id != &page.id || page.uri != format!("copymanga-page:{id}") {
                return Err(SourceError::InvalidRequest);
            }
            url.clone()
        };
        let file = self
            .http
            .anonymous_image_file(context.clone(), url, directory)
            .await?;
        self.check(&context)?;
        Ok(file)
    }
    pub async fn cover_file(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        directory: String,
    ) -> Result<SourceImageFile, SourceError> {
        self.key_check(&context, &key)?;
        let location = match self.known_cover(&key.manga_id)? {
            Some(value) => value,
            None => {
                self.book(context.clone(), key.clone()).await?;
                self.known_cover(&key.manga_id)?
                    .ok_or(SourceError::InvalidResponse)?
            }
        };
        let url = match location {
            CoverLocation::Url(url) => url,
            CoverLocation::Missing => return Err(SourceError::NotFound),
            CoverLocation::Invalid => return Err(SourceError::InvalidResponse),
        };
        let file = self
            .http
            .anonymous_image_file(context.clone(), url, directory)
            .await?;
        self.check(&context)?;
        Ok(file)
    }
}

impl MangaSource for CopyMangaSource {
    fn capabilities(&self) -> SourceCapabilities {
        CopyMangaSource::capabilities(self)
    }
    fn recommendations(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage> {
        Box::pin(CopyMangaSource::recommendations(self, context, page))
    }
    fn search(
        &self,
        context: SourceContext,
        query: String,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage> {
        Box::pin(CopyMangaSource::search(self, context, query, page))
    }
    fn detail(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
    ) -> SourceFuture<'_, SourceDetail> {
        Box::pin(CopyMangaSource::detail(self, context, key))
    }
    fn updates(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage> {
        Box::pin(CopyMangaSource::updates(self, context, page))
    }
    // Generic protocol walks groups with an opaque cursor. The UI uses group_chapters
    // with its explicit selected group, avoiding repeated group metadata requests.
    fn chapters(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceChapterPage> {
        Box::pin(async move {
            self.key_check(&context, &key)?;
            let groups = self
                .book(context.clone(), key.clone())
                .await?
                .groups
                .ok_or(SourceError::InvalidResponse)?;
            let (group_index, number) = match page {
                SourcePageRequest::Start | SourcePageRequest::Page { number: 1 } => (0, 1),
                SourcePageRequest::Cursor { token } if token.len() <= 1024 => {
                    let cursor: ChapterCursor =
                        serde_json::from_str(&token).map_err(|_| SourceError::InvalidRequest)?;
                    if cursor.manga != key.manga_id || cursor.number == 0 {
                        return Err(SourceError::InvalidRequest);
                    }
                    (
                        groups
                            .iter()
                            .position(|g| g.id == cursor.group)
                            .ok_or(SourceError::InvalidRequest)?,
                        cursor.number,
                    )
                }
                _ => return Err(SourceError::InvalidRequest),
            };
            let Some(group) = groups.get(group_index) else {
                return Ok(SourceChapterPage {
                    context,
                    items: vec![],
                    continuation: SourceContinuation::End,
                    total: Some(0),
                });
            };
            let mut result = self
                .group_chapters(
                    context,
                    key.clone(),
                    group.id.clone(),
                    SourcePageRequest::Page { number },
                )
                .await?;
            // This endpoint joins all groups; its count must use the same directory scope.
            result.total = groups.iter().try_fold(0u32, |sum, group| sum.checked_add(group.chapter_count?));
            let next = match result.continuation {
                SourceContinuation::Next {
                    request: SourcePageRequest::Page { number },
                } => Some((group.id.clone(), number)),
                _ => groups.get(group_index + 1).map(|g| (g.id.clone(), 1)),
            };
            result.continuation = match next {
                Some((group, number)) => SourceContinuation::Next {
                    request: SourcePageRequest::Cursor {
                        token: serde_json::to_string(&ChapterCursor {
                            manga: key.manga_id,
                            group,
                            number,
                        })
                        .map_err(|_| SourceError::InvalidResponse)?,
                    },
                },
                None => SourceContinuation::End,
            };
            Ok(result)
        })
    }
    fn pages(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        chapter: String,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourcePages> {
        Box::pin(CopyMangaSource::pages(self, context, key, chapter, page))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::{
        io::{Read, Write},
        net::TcpListener,
        thread,
        time::{Duration, Instant},
    };
    fn context() -> SourceContext {
        SourceContext {
            world: SourceWorld::Sfw,
            source_id: "copy-fixture".into(),
            process_session: "synthetic".into(),
            generation: 2,
            auth_ref: None,
        }
    }
    fn source(base: String) -> CopyMangaSource {
        CopyMangaSource::new(
            context(),
            base,
            SourceHttpConfig {
                timeout_ms: 3000,
                max_in_flight: 2,
                min_interval_ms: 0,
                max_retries: 0,
                max_body_bytes: 4 * 1024 * 1024,
            },
        )
        .unwrap()
    }
    fn reply(body: serde_json::Value) -> SourceHttpReply {
        SourceHttpReply {
            context: context(),
            content_type: Some("application/json".into()),
            body: serde_json::to_vec(&body).unwrap(),
        }
    }
    fn comic(id: &str) -> serde_json::Value {
        json!({"path_word":id,"name":"合成作品","brief":"合成简介","cover":"https://unused.invalid/image.webp"})
    }
    fn page(offset: u32, total: u32, list: Vec<serde_json::Value>) -> serde_json::Value {
        json!({"code":200,"results":{"offset":offset,"total":total,"limit":21,"list":list}})
    }
    #[test]
    fn themes_keep_names_ids_detail_and_encoded_paging() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", listener.local_addr().unwrap());
        let mut work = comic("tagged");
        work["theme"] = json!([{"name":"冒险", "path_word":"adventure &?"}, {"name":"冒险", "path_word":"second"}]);
        let job = thread::spawn(move || {
            for (path, body) in [
                ("/api/v3/h5/filterIndex/comic/tags?type=1&platform=3", json!({"code":200,"results":{"theme":[{"name":"冒险","path_word":"adventure &?","count":123}],"top":[]}})),
                ("/api/v3/comics?limit=21&offset=21&ordering=-datetime_updated&theme=adventure+%26%3F&platform=3", page(21,22,vec![comic("tagged")])),
                ("/api/v3/comic2/tagged?platform=3", json!({"code":200,"results":{"comic":work}})),
            ] {
                let (mut stream, _) = listener.accept().unwrap();
                stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let mut bytes = Vec::new(); let mut buf = [0u8;1024];
                while !bytes.windows(4).any(|w| w == b"\r\n\r\n") { let n = stream.read(&mut buf).unwrap(); assert!(n>0); bytes.extend_from_slice(&buf[..n]); }
                let request = String::from_utf8(bytes).unwrap(); assert!(request.starts_with(&format!("GET {path} HTTP/1.1")), "{request}");
                let body = body.to_string(); write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body).unwrap();
            }
        });
        let source = source(base);
        tokio::runtime::Runtime::new().unwrap().block_on(async {
            let themes = source.themes(context()).await.unwrap();
            assert_eq!(themes[0].name, "冒险");
            let page = source
                .themed(
                    context(),
                    themes[0].path_word.clone(),
                    SourcePageRequest::Page { number: 2 },
                )
                .await
                .unwrap();
            assert_eq!(page.continuation, SourceContinuation::End);
            let book = source
                .book(context(), page.items[0].key.clone())
                .await
                .unwrap();
            assert_eq!(book.detail.tags.unwrap(), vec!["冒险", "冒险"]);
            assert_eq!(book.themes.unwrap()[1].path_word, "second");
            source.invalidate();
            assert!(source.themes(context()).await.is_err());
        });
        job.join().unwrap();
    }
    #[test]
    fn pagination_and_business_failures_keep_their_meaning() {
        assert_eq!(
            offset(SourcePageRequest::Page { number: 0 }),
            Err(SourceError::InvalidRequest)
        );
        assert_eq!(
            offset(SourcePageRequest::Page { number: u32::MAX }),
            Err(SourceError::InvalidRequest)
        );
        assert_eq!(
            offset(SourcePageRequest::Cursor { token: "21".into() }),
            Err(SourceError::InvalidRequest)
        );
        let empty: Page<Comic> = parse(reply(page(0, 30, vec![]))).unwrap();
        assert_eq!(
            continuation(&empty, 0).unwrap(),
            SourceContinuation::Next {
                request: SourcePageRequest::Page { number: 2 }
            }
        );
        assert_eq!(continuation(&empty, 21), Err(SourceError::InvalidResponse));
        let end: Page<Comic> = parse(reply(page(42, 30, vec![]))).unwrap();
        assert_eq!(continuation(&end, 42).unwrap(), SourceContinuation::End);
        let bad: Page<Comic> = parse(reply(page(0, 0, vec![comic("a")]))).unwrap();
        assert_eq!(continuation(&bad, 0), Err(SourceError::InvalidResponse));
        for (code, error) in [
            (401, SourceError::AuthenticationRequired),
            (403, SourceError::AuthenticationRequired),
            (404, SourceError::NotFound),
            (
                429,
                SourceError::RateLimited {
                    retry_after_seconds: None,
                },
            ),
            (500, SourceError::InvalidResponse),
        ] {
            assert_eq!(
                parse::<Detail>(reply(
                    json!({"code":code,"message":"never publish server text","results":"failure"})
                ))
                .err(),
                Some(error)
            );
        }
        assert!(parse::<Detail>(reply(json!({"code":200}))).is_err());
        assert!(
            parse::<Page<Comic>>(reply(page(0, 1, vec![json!({"name":"missing identity"})])))
                .is_err()
        );
        let mut html = reply(json!({"code":200}));
        html.content_type = Some("text/html".into());
        assert!(parse::<Detail>(html).is_err());
    }
    #[test]
    fn synthetic_http_recommendations_search_detail_and_identity() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/proxy/", listener.local_addr().unwrap());
        let mut detailed = comic("a");
        detailed["author"] = json!([{"name":"Synthetic Author","path_word":"author"}]);
        detailed["status"] = json!({"value":9,"display":"连载中"});
        detailed["datetime_updated"] = json!("2026-10-01 12:30:00");
        let job = thread::spawn(move || {
            for (path, body) in [
                ("/proxy/api/v3/recs?pos=3200102&limit=21&offset=0&platform=3", page(0,22,vec![json!({"comic":comic("a")})])),
                ("/proxy/api/v3/recs?pos=3200102&limit=21&offset=21&platform=3", page(21,22,vec![json!({"comic":comic("b")})])),
                ("/proxy/api/v3/search/comic?limit=21&offset=0&q=%E4%B8%AD%E6%96%87+%26%3F&q_type=&platform=3",page(0,1,vec![comic("a")])),
                ("/proxy/api/v3/comic2/a?platform=3",json!({"code":200,"results":{"comic":detailed}})),
            ] {
                let (mut stream,_) = listener.accept().unwrap(); stream.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                let mut bytes=Vec::new(); let mut buf=[0u8;1024];
                while !bytes.windows(4).any(|w| w==b"\r\n\r\n") { let n=stream.read(&mut buf).unwrap(); assert!(n>0); bytes.extend_from_slice(&buf[..n]); }
                let request=String::from_utf8(bytes).unwrap(); assert!(request.starts_with(&format!("GET {path} HTTP/1.1")),"{request}");
                let request=request.to_lowercase();
                for header in ["platform: 3","version: 2024.4.28","authorization: token","x-requested-with: com.manga2020.app"] { assert!(request.contains(header)); }
                assert!(!request.contains("x-api-key")); assert!(!request.contains("cookie:"));
                let body=body.to_string(); write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body).unwrap();
            }
        });
        let source = source(base);
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let first = source
                .recommendations(context(), SourcePageRequest::Start)
                .await
                .unwrap();
            assert_eq!(first.items[0].key.source_id, "copy-fixture");
            assert_eq!(first.items[0].key.manga_id, "a");
            assert_eq!(
                first.continuation,
                SourceContinuation::Next {
                    request: SourcePageRequest::Page { number: 2 }
                }
            );
            let second = source
                .recommendations(context(), SourcePageRequest::Page { number: 2 })
                .await
                .unwrap();
            assert_eq!(second.continuation, SourceContinuation::End);
            let search = source
                .search(context(), "中文 &?".into(), SourcePageRequest::Start)
                .await
                .unwrap();
            let detail = source
                .detail(context(), search.items[0].key.clone())
                .await
                .unwrap();
            assert_eq!(detail.description.as_deref(), Some("合成简介"));
            assert!(detail.tags.is_none());
            assert_eq!(detail.metadata.authors[0].name, "Synthetic Author");
            assert_eq!(detail.metadata.status.as_deref(), Some("连载中"));
            assert_eq!(detail.metadata.updated_at.as_deref(), Some("2026-10-01 12:30:00"));
            assert_eq!(detail.metadata.update_kind, Some(crate::detail_metadata::SourceUpdateKind::Content));
            let mut foreign = context();
            foreign.generation += 1;
            assert_eq!(
                source
                    .recommendations(foreign, SourcePageRequest::Start)
                    .await
                    .err(),
                Some(SourceError::SessionMismatch)
            );
            let mut key = detail.manga.key;
            key.source_id = "komga".into();
            assert_eq!(
                source.detail(context(), key).await.err(),
                Some(SourceError::SessionMismatch)
            );
            assert!(source.capabilities().pages);
            assert!(source.capabilities().updates);
            source.invalidate();
            assert_eq!(
                source
                    .recommendations(context(), SourcePageRequest::Start)
                    .await
                    .err(),
                Some(SourceError::SessionMismatch)
            );
        });
        job.join().unwrap();
    }
    #[test]
    fn construction_rejects_private_and_credential_scopes() {
        let config = SourceHttpConfig {
            timeout_ms: 1000,
            max_in_flight: 1,
            min_interval_ms: 0,
            max_retries: 0,
            max_body_bytes: 1024,
        };
        let mut private = context();
        private.world = SourceWorld::Nsfw;
        assert!(
            CopyMangaSource::new(private, "https://example.invalid/".into(), config.clone())
                .is_err()
        );
        let mut account = context();
        account.auth_ref = Some("komga-key".into());
        assert!(CopyMangaSource::new(account, "https://example.invalid/".into(), config).is_err());
    }
    #[test]
    #[ignore = "Manual local cost comparison; not an Android performance result"]
    fn fixed_recommendation_mapping_cost() {
        let source = source("http://127.0.0.1:1/".into());
        let fixture = reply(page(
            0,
            200,
            (0..21)
                .map(|i| json!({"comic":comic(&format!("book-{i}"))}))
                .collect(),
        ));
        let mut baseline = Vec::new();
        let mut mapped = Vec::new();
        for round in 0..42 {
            for full in if round % 2 == 0 {
                [false, true]
            } else {
                [true, false]
            } {
                let start = Instant::now();
                for _ in 0..200 {
                    let copy = SourceHttpReply {
                        context: fixture.context.clone(),
                        content_type: fixture.content_type.clone(),
                        body: fixture.body.clone(),
                    };
                    if full {
                        let value: Page<ComicEntry> = parse(copy).unwrap();
                        std::hint::black_box(
                            source
                                .page(
                                    context(),
                                    Page {
                                        total: value.total,
                                        offset: value.offset,
                                        limit: value.limit,
                                        list: value.list.into_iter().map(|r| r.comic).collect(),
                                    },
                                    0,
                                )
                                .unwrap(),
                        );
                    } else {
                        std::hint::black_box(
                            serde_json::from_slice::<Envelope<Page<ComicEntry>>>(&copy.body)
                                .unwrap(),
                        );
                    }
                }
                if round >= 2 {
                    (if full { &mut mapped } else { &mut baseline })
                        .push(start.elapsed().as_nanos() as f64 / 200.0 / 1000.0);
                }
            }
        }
        baseline.sort_by(f64::total_cmp);
        mapped.sort_by(f64::total_cmp);
        println!("21 synthetic items; 40 alternating batches x 200; debug host microseconds per call: minimal p50={:.2} p95={:.2}; status+identity+mapping p50={:.2} p95={:.2}",baseline[20],baseline[37],mapped[20],mapped[37]);
    }

    #[test]
    fn cover_policy_and_metadata_references_are_source_scoped_and_bounded() {
        let base = Url::parse("https://api.2024manga.com/").unwrap();
        for url in [
            "https://hi77-overseas.mangafuna.xyz/a.webp",
            "https://c2.mangafunz.fun/a?signature=x",
            "https://api.2024manga.com/cover/a",
        ] {
            assert!(cover_url(&base, url).is_ok(), "{url}");
        }
        for url in [
            "http://hi77-overseas.mangafuna.xyz/a",
            "https://evil.invalid/a",
            "https://c.mangafuna.xyz.evil.invalid/a",
            "https://x.c.mangafuna.xyz/a",
            "https://u:p@c.mangafuna.xyz/a",
            "https://c.mangafuna.xyz/a#frag",
            "https://c.mangafuna.xyz:8443/a",
            "http://127.0.0.1/a",
        ] {
            assert!(cover_url(&base, url).is_err(), "{url}");
        }
        let source = source("https://api.2024manga.com/".into());
        for i in 0..150 {
            source
                .manga(Comic {
                    theme: None,
                    reclass: None,
                    path_word: format!("book-{i}"),
                    name: "synthetic".into(),
                    brief: None,
                    cover: Some("https://c.mangafuna.xyz/a".into()),
                })
                .unwrap();
        }
        assert_eq!(source.covers.lock().unwrap().len(), 128);
        assert!(source.known_cover("book-0").unwrap().is_none());
        assert!(source.known_cover("book-149").unwrap().is_some());
        source.invalidate();
        assert_eq!(source.covers.lock().unwrap().len(), 0);
    }

    #[test]
    fn grouped_chapters_and_cdn_files_preserve_identity_headers_and_ownership() {
        let api = TcpListener::bind("127.0.0.1:0").unwrap();
        let cdn = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", api.local_addr().unwrap());
        let cover = format!("http://{}/cover.png", cdn.local_addr().unwrap());
        let mut work = comic("work");
        work["cover"] = json!(cover);
        let book = json!({"code":200,"results":{"comic":work,"groups":{
            "z":{"path_word":"b_extra","name":"番外","count":0},"a":{"path_word":"a_main","name":"正文","count":101}}}});
        let chapters = |offset: u32, total: u32, group: &str, id: &str| {
            json!({"code":200,"results":{"offset":offset,"limit":100,"total":total,
            "list":[{"uuid":id,"name":"第 1.5 话","comic_path_word":"work","group_path_word":group,"count":99}]}})
        };
        let empty = json!({"code":200,"results":{"offset":0,"limit":100,"total":0,"list":[]}});
        let mut wrong = chapters(0, 1, "b_extra", "foreign");
        wrong["results"]["list"][0]["comic_path_word"] = json!("other-work");
        let api_steps = vec![
            ("recs?".to_owned(), page(0, 1, vec![json!({"comic":work})])),
            ("comic2/work?".into(), book.clone()),
            (
                "group/a_main/chapters?limit=100&offset=0&".into(),
                chapters(0, 101, "a_main", "ch-1"),
            ),
            (
                "group/a_main/chapters?limit=100&offset=100&".into(),
                chapters(100, 101, "a_main", "ch-101"),
            ),
            ("group/b_extra/chapters?".into(), wrong),
            ("comic2/work?".into(), book.clone()),
            (
                "group/a_main/chapters?limit=100&offset=0&".into(),
                chapters(0, 101, "a_main", "ch-1"),
            ),
            ("comic2/work?".into(), book.clone()),
            (
                "group/a_main/chapters?limit=100&offset=100&".into(),
                chapters(100, 101, "a_main", "ch-101"),
            ),
            ("comic2/work?".into(), book),
            ("group/b_extra/chapters?".into(), empty),
        ];
        fn request(stream: &mut std::net::TcpStream) -> String {
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut bytes = Vec::new();
            let mut buf = [0u8; 1024];
            while !bytes.windows(4).any(|w| w == b"\r\n\r\n") {
                let n = stream.read(&mut buf).unwrap();
                assert!(n > 0);
                bytes.extend_from_slice(&buf[..n]);
            }
            String::from_utf8(bytes).unwrap().to_lowercase()
        }
        let api_thread = thread::spawn(move || {
            for (path, body) in api_steps {
                let (mut stream, _) = api.accept().unwrap();
                let req = request(&mut stream);
                assert!(req.contains(&path), "{req}");
                assert!(req.contains("authorization: token"));
                let body = body.to_string();
                write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body).unwrap();
            }
        });
        let cdn_thread = thread::spawn(move || {
            for response in [
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 9\r\nSet-Cookie: never=send\r\nConnection: close\r\n\r\nsynthetic",
                "HTTP/1.1 302 Found\r\nLocation: https://example.invalid/never\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 3\r\nConnection: close\r\n\r\nbad",
                "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 999\r\nConnection: close\r\n\r\npartial",
            ] {
                let (mut stream,_)=cdn.accept().unwrap();let req=request(&mut stream);
                assert!(req.starts_with("get /cover.png "));
                for name in ["authorization:","cookie:","x-api-key:","platform:","version:","x-requested-with:","accept: application/json"] {assert!(!req.contains(name),"{req}");}
                assert!(req.contains("pixel 7"));stream.write_all(response.as_bytes()).unwrap();
            }
        });
        let source = source(base);
        let directory = std::env::temp_dir().join(format!(
            "flux-copy-cover-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        let original = directory.join("original.cbz");
        std::fs::write(&original, b"untouched").unwrap();
        let runtime = tokio::runtime::Runtime::new().unwrap();
        runtime.block_on(async {
            let item = source
                .recommendations(context(), SourcePageRequest::Start)
                .await
                .unwrap()
                .items
                .remove(0);
            // List metadata supplies cover directly: no redundant detail request.
            let image = source
                .cover_file(
                    context(),
                    item.key.clone(),
                    directory.to_str().unwrap().into(),
                )
                .await
                .unwrap();
            let path = image.path();
            assert_eq!(std::fs::read(&path).unwrap(), b"synthetic");
            drop(image);
            assert!(!std::path::Path::new(&path).exists());
            for error in [
                SourceError::HttpStatus { status: 302 },
                SourceError::InvalidResponse,
                SourceError::AuthenticationRequired,
                SourceError::Transport,
            ] {
                assert_eq!(
                    source
                        .cover_file(
                            context(),
                            item.key.clone(),
                            directory.to_str().unwrap().into()
                        )
                        .await
                        .err(),
                    Some(error)
                );
                assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 1);
            }
            let book = source.book(context(), item.key.clone()).await.unwrap();
            let groups = book.groups.unwrap();
            assert_eq!(
                groups.iter().map(|g| g.id.as_str()).collect::<Vec<_>>(),
                vec!["a_main", "b_extra"]
            );
            assert_eq!(groups[1].chapter_count, Some(0));
            let first = source
                .group_chapters(
                    context(),
                    item.key.clone(),
                    "a_main".into(),
                    SourcePageRequest::Start,
                )
                .await
                .unwrap();
            assert_eq!(first.items[0].title, "第 1.5 话");
            assert!(first.items[0].page_count.is_none());
            let second = source
                .group_chapters(
                    context(),
                    item.key.clone(),
                    "a_main".into(),
                    SourcePageRequest::Page { number: 2 },
                )
                .await
                .unwrap();
            assert_eq!(second.items[0].ordinal, 101);
            assert_eq!(second.continuation, SourceContinuation::End);
            assert_eq!(
                source
                    .group_chapters(
                        context(),
                        item.key.clone(),
                        "b_extra".into(),
                        SourcePageRequest::Start
                    )
                    .await
                    .err(),
                Some(SourceError::InvalidResponse)
            );
            let mut next = SourcePageRequest::Start;
            for _ in 0..3 {
                let page = MangaSource::chapters(&source, context(), item.key.clone(), next)
                    .await
                    .unwrap();
                match page.continuation {
                    SourceContinuation::Next { request } => next = request,
                    SourceContinuation::End => {
                        assert!(page.items.is_empty());
                        break;
                    }
                }
            }
            let mut foreign = item.key.clone();
            foreign.source_id = "komga".into();
            assert_eq!(
                source
                    .cover_file(context(), foreign, directory.to_str().unwrap().into())
                    .await
                    .err(),
                Some(SourceError::SessionMismatch)
            );
            source.invalidate();
            assert_eq!(
                source
                    .group_chapters(
                        context(),
                        item.key,
                        "a_main".into(),
                        SourcePageRequest::Start
                    )
                    .await
                    .err(),
                Some(SourceError::SessionMismatch)
            );
        });
        api_thread.join().unwrap();
        cdn_thread.join().unwrap();
        assert_eq!(std::fs::read(&original).unwrap(), b"untouched");
        std::fs::remove_file(original).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
    fn chapter_body(url: &str, chapter: &str) -> serde_json::Value {
        json!({"comic":comic("work"),"chapter":{"uuid":chapter,"comic_path_word":"work","size":999,
            "contents":[{"uuid":"last","url":format!("{url}/last.png")},{"uuid":"first","url":format!("{url}/first.png")}],"words":[1,0]}})
    }
    #[test]
    fn body_order_identity_and_malformed_encryption_are_explicit() {
        let base = Url::parse("https://api.example/").unwrap();
        let key = SourceMangaKey {
            world: SourceWorld::Sfw,
            source_id: context().source_id,
            manga_id: "work".into(),
        };
        let raw = chapter_body("https://img.mangafuna.xyz", "ch1");
        let ordered = ordered_images(
            &base,
            &key,
            "ch1",
            serde_json::from_value(raw.clone()).unwrap(),
        )
        .unwrap();
        assert_eq!(ordered[0].0, "ch1:uuid:first");
        assert!(ordered[0].1.path().ends_with("first.png"));
        for words in [json!([0, 0]), json!([0, 2]), json!([0]), json!([-1, 0])] {
            let mut bad = raw.clone();
            bad["chapter"]["words"] = words;
            let result = serde_json::from_value(bad)
                .map_err(|_| SourceError::InvalidResponse)
                .and_then(|body| ordered_images(&base, &key, "ch1", body));
            assert!(matches!(result, Err(SourceError::InvalidResponse)));
        }
        let mut plain = raw.clone();
        plain["chapter"]["words"] = json!(null);
        plain["chapter"]["contents"][0]["uuid"] = json!(null);
        let pages =
            ordered_images(&base, &key, "ch1", serde_json::from_value(plain).unwrap()).unwrap();
        assert_eq!(pages[0].0, "ch1:page:1");
        assert!(pages[0].1.path().ends_with("last.png"));
        for field in ["comic_path_word", "uuid"] {
            let mut bad = raw.clone();
            bad["chapter"][field] = json!("foreign");
            assert!(
                ordered_images(&base, &key, "ch1", serde_json::from_value(bad).unwrap()).is_err()
            );
        }
        for url in ["https://untrusted.invalid/a", "http://img.mangafuna.xyz/a"] {
            let mut bad = raw.clone();
            bad["chapter"]["contents"][0]["url"] = json!(url);
            assert!(
                ordered_images(&base, &key, "ch1", serde_json::from_value(bad).unwrap()).is_err()
            );
        }
        let mut duplicate = raw.clone();
        duplicate["chapter"]["contents"][0]["uuid"] = json!("first");
        assert!(ordered_images(
            &base,
            &key,
            "ch1",
            serde_json::from_value(duplicate).unwrap()
        )
        .is_err());
        assert!(
            parse::<ChapterBody>(reply(json!({"code":200,"results":"encrypted-unsupported"})))
                .is_err()
        );
    }
    #[test]
    fn body_http_version_file_retry_and_bounded_source_manifest() {
        let api = TcpListener::bind("127.0.0.1:0").unwrap();
        let cdn = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/", api.local_addr().unwrap());
        let image_base = format!("http://{}", cdn.local_addr().unwrap());
        fn request(stream: &mut std::net::TcpStream) -> String {
            stream
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut data = Vec::new();
            let mut buf = [0u8; 1024];
            while !data.windows(4).any(|w| w == b"\r\n\r\n") {
                let n = stream.read(&mut buf).unwrap();
                assert!(n > 0);
                data.extend_from_slice(&buf[..n]);
            }
            String::from_utf8(data).unwrap().to_lowercase()
        }
        let api_thread = thread::spawn(move || {
            for (chapter, legacy) in [
                ("ch1", false),
                ("wrong", false),
                ("ch2", true),
                ("ch3", false),
            ] {
                let (mut stream, _) = api.accept().unwrap();
                let req = request(&mut stream);
                assert!(req.contains("/comic2/work?platform=3"));
                assert!(req.contains("authorization: token"));
                let mut work = comic("work");
                if legacy {
                    work["reclass"] = json!({"name":"legacy"});
                }
                let text = json!({"code":200,"results":{"comic":work}}).to_string();
                write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",text.len(),text).unwrap();
                let (mut stream, _) = api.accept().unwrap();
                let req = request(&mut stream);
                assert!(req.contains(&format!(
                    "/comic/work/chapter{}/{chapter}?platform=3",
                    if legacy { "" } else { "2" }
                )));
                let body = chapter_body(
                    &image_base,
                    if chapter == "wrong" {
                        "foreign"
                    } else {
                        chapter
                    },
                );
                let text = json!({"code":200,"results":body}).to_string();
                write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",text.len(),text).unwrap();
            }
        });
        let cdn_thread = thread::spawn(move || {
            for status in [403, 200] {
                let (mut stream, _) = cdn.accept().unwrap();
                let req = request(&mut stream);
                assert!(req.starts_with("get /first.png "));
                for header in [
                    "authorization:",
                    "cookie:",
                    "platform:",
                    "version:",
                    "x-requested-with:",
                ] {
                    assert!(!req.contains(header));
                }
                write!(stream,"HTTP/1.1 {status} Synthetic\r\nContent-Type: image/png\r\nContent-Length: 9\r\nConnection: close\r\n\r\nsynthetic").unwrap();
            }
        });
        let source = source(base);
        let key = SourceMangaKey {
            world: SourceWorld::Sfw,
            source_id: context().source_id,
            manga_id: "work".into(),
        };
        let directory =
            std::env::temp_dir().join(format!("flux-copy-reader-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let original = directory.join("original.cbz");
        std::fs::write(&original, b"keep").unwrap();
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let pages = source
                    .pages(
                        context(),
                        key.clone(),
                        "ch1".into(),
                        SourcePageRequest::Start,
                    )
                    .await
                    .unwrap();
                assert_eq!(pages.items.len(), 2);
                assert_eq!(pages.items[0].ordinal, 1);
                assert_eq!(pages.items[0].id, "ch1:uuid:first");
                let mut foreign = pages.items[0].clone();
                foreign.manga.source_id = "other".into();
                assert!(matches!(
                    source
                        .page_file(context(), foreign, directory.display().to_string())
                        .await,
                    Err(SourceError::SessionMismatch)
                ));
                let mut forged = pages.items[0].clone();
                forged.uri = "https://untrusted.invalid".into();
                assert!(matches!(
                    source
                        .page_file(context(), forged, directory.display().to_string())
                        .await,
                    Err(SourceError::InvalidRequest)
                ));
                assert!(matches!(
                    source
                        .page_file(
                            context(),
                            pages.items[0].clone(),
                            directory.display().to_string()
                        )
                        .await,
                    Err(SourceError::AuthenticationRequired)
                ));
                let file = source
                    .page_file(
                        context(),
                        pages.items[0].clone(),
                        directory.display().to_string(),
                    )
                    .await
                    .unwrap();
                let path = file.path();
                assert_eq!(std::fs::read(&path).unwrap(), b"synthetic");
                drop(file);
                assert!(!std::path::Path::new(&path).exists());
                assert!(matches!(
                    source
                        .pages(
                            context(),
                            key.clone(),
                            "wrong".into(),
                            SourcePageRequest::Start
                        )
                        .await,
                    Err(SourceError::InvalidResponse)
                ));
                assert_eq!(source.images.lock().unwrap().len(), 1);
                for chapter in ["ch2", "ch3"] {
                    source
                        .pages(
                            context(),
                            key.clone(),
                            chapter.into(),
                            SourcePageRequest::Start,
                        )
                        .await
                        .unwrap();
                }
                assert_eq!(source.images.lock().unwrap().len(), 2);
                assert!(matches!(
                    source
                        .page_file(
                            context(),
                            pages.items[0].clone(),
                            directory.display().to_string()
                        )
                        .await,
                    Err(SourceError::InvalidRequest)
                ));
                source.invalidate();
                assert!(source.images.lock().unwrap().is_empty());
                assert!(matches!(
                    source
                        .pages(context(), key, "ch3".into(), SourcePageRequest::Start)
                        .await,
                    Err(SourceError::SessionMismatch)
                ));
            });
        api_thread.join().unwrap();
        cdn_thread.join().unwrap();
        assert_eq!(std::fs::read(&original).unwrap(), b"keep");
        assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 1);
        std::fs::remove_file(original).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
}

#[path = "copy_account.rs"]
mod account;
