//! Komga 1.29.1 REST metadata adapter. API-key / Basic auth; no server mutations or local library writes.
use crate::image_file::SourceImageFile;
use crate::{
    source::*,
    transport::{SourceHttpConfig, SourceHttpReply, SourceHttpSession},
};
use serde::{de::DeserializeOwned, Deserialize};
use serde_json::json;
use std::sync::atomic::{AtomicBool, Ordering::SeqCst};
use std::sync::Arc;
const PAGE_SIZE: u32 = 20;

#[derive(Deserialize)]
struct Metadata {
    title: String,
    summary: Option<String>,
    #[serde(default, rename = "lastModified")]
    modified: serde_json::Value,
}
#[derive(Deserialize)]
struct Series {
    id: String,
    metadata: Metadata,
}
// Only detail requests decode tags. Catalog/chapters keep their small Metadata DTO.
#[derive(Deserialize)]
struct DetailedSeries {
    id: String,
    metadata: DetailMetadata,
    #[serde(default, rename = "booksMetadata")]
    books_metadata: serde_json::Value,
}
#[derive(Deserialize)]
struct DetailMetadata {
    #[serde(flatten)]
    base: Metadata,
    tags: Option<Vec<String>>,
    #[serde(default)]
    status: serde_json::Value,
}
fn detail_tags(tags: Option<Vec<String>>) -> Result<Option<Vec<String>>, SourceError> {
    tags.map(|mut values| {
        if values
            .iter()
            .any(|tag| tag.trim().is_empty() || tag.len() > 4096)
        {
            return Err(SourceError::InvalidResponse);
        }
        let mut seen = std::collections::HashSet::new();
        values.retain(|tag| seen.insert(tag.clone()));
        Ok(values)
    })
    .transpose()
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Book {
    id: String,
    series_id: String,
    metadata: Metadata,
    media: Media,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Media {
    pages_count: u32,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Page {
    number: u32,
    media_type: String,
    width: Option<u32>,
    height: Option<u32>,
}
#[derive(Deserialize)]
struct PageList<T> {
    content: Vec<T>,
    number: u32,
    size: u32,
    last: bool,
    #[serde(default, rename = "totalElements")]
    total: Option<u32>,
}
#[derive(Deserialize)]
struct User {
    id: String,
}

fn parse<T: DeserializeOwned>(reply: SourceHttpReply) -> Result<T, SourceError> {
    if !reply.content_type.as_deref().is_some_and(|v| {
        v.split(';')
            .next()
            .unwrap_or_default()
            .trim()
            .eq_ignore_ascii_case("application/json")
    }) {
        return Err(SourceError::InvalidResponse);
    }
    serde_json::from_slice(&reply.body).map_err(|_| SourceError::InvalidResponse)
}
fn valid_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 256
        && value
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'-' || c == b'_')
}
fn page_number(page: SourcePageRequest) -> Result<u32, SourceError> {
    match page {
        SourcePageRequest::Start => Ok(0),
        SourcePageRequest::Page { number } if (1..=u32::MAX / PAGE_SIZE).contains(&number) => {
            Ok(number - 1)
        }
        _ => Err(SourceError::InvalidRequest),
    }
}
fn unpack<T>(page: PageList<T>, number: u32) -> Result<(Vec<T>, SourceContinuation), SourceError> {
    if page.number != number || page.size != PAGE_SIZE || page.content.len() > PAGE_SIZE as usize {
        return Err(SourceError::InvalidResponse);
    }
    let next = if page.last {
        SourceContinuation::End
    } else {
        SourceContinuation::Next {
            request: SourcePageRequest::Page { number: number + 2 },
        }
    };
    Ok((page.content, next))
}

#[derive(Clone, Copy, Debug, PartialEq, uniffi::Enum)]
pub enum KomgaListKind {
    Library,
    Collection,
    ReadList,
}
#[derive(uniffi::Record)]
pub struct KomgaAuthorizedList {
    pub id: String,
    pub name: String,
    pub kind: KomgaListKind,
    pub ordered: bool,
    pub filtered: bool,
}
#[derive(uniffi::Record)]
pub struct KomgaListPage {
    pub context: SourceContext,
    pub items: Vec<KomgaAuthorizedList>,
    pub next_page: Option<u32>,
}
#[derive(Clone, uniffi::Record)]
pub struct KomgaImportEntry {
    pub series_id: String,
    pub title: String,
    pub book_id: Option<String>,
    pub position: u32,
}
#[derive(uniffi::Record)]
pub struct KomgaImportPage {
    pub context: SourceContext,
    pub items: Vec<KomgaImportEntry>,
    pub next_page: Option<u32>,
}
#[derive(Deserialize)]
struct LibraryName {
    id: String,
    name: String,
}
#[derive(Deserialize)]
struct SharedList {
    id: String,
    name: String,
    ordered: bool,
    filtered: bool,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ListedBook {
    id: String,
    series_id: String,
    series_title: String,
}
fn valid_title(value: &str) -> bool {
    !value.trim().is_empty() && value.len() <= 4096
}
fn list_query(page: u32) -> Result<String, SourceError> {
    if page > u32::MAX / PAGE_SIZE - 1 {
        return Err(SourceError::InvalidRequest);
    }
    Ok(format!("page={page}&size={PAGE_SIZE}"))
}
fn next_index(next: SourceContinuation) -> Option<u32> {
    match next {
        SourceContinuation::Next {
            request: SourcePageRequest::Page { number },
        } => Some(number - 1),
        _ => None,
    }
}

#[derive(uniffi::Object)]
pub struct KomgaSource {
    context: SourceContext,
    base: reqwest::Url,
    http: SourceHttpSession,
    closed: AtomicBool,
}
impl KomgaSource {
    fn check(&self, context: &SourceContext) -> Result<(), SourceError> {
        if *context != self.context || self.closed.load(SeqCst) {
            return Err(SourceError::SessionMismatch);
        }
        Ok(())
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
    fn manga(&self, value: Series) -> Result<SourceManga, SourceError> {
        if !valid_id(&value.id) || value.metadata.title.trim().is_empty() {
            return Err(SourceError::InvalidResponse);
        }
        Ok(SourceManga {
            key: SourceMangaKey {
                world: self.context.world.clone(),
                source_id: self.context.source_id.clone(),
                manga_id: value.id,
            },
            title: value.metadata.title,
            page_count: None,
            updated_at: value.metadata.modified.as_str().filter(|v| !v.trim().is_empty()).map(str::to_owned),
            latest_chapter: None,
        })
    }
    async fn book(
        &self,
        context: &SourceContext,
        key: &SourceMangaKey,
        id: &str,
    ) -> Result<Book, SourceError> {
        if !valid_id(id) {
            return Err(SourceError::InvalidRequest);
        }
        let book: Book = parse(
            self.http
                .get(context.clone(), format!("api/v1/books/{id}"))
                .await?,
        )?;
        if book.id != id || book.series_id != key.manga_id {
            return Err(SourceError::InvalidResponse);
        }
        Ok(book)
    }
}
#[uniffi::export]
impl KomgaSource {
    #[uniffi::constructor]
    pub fn new(
        context: SourceContext,
        base_url: String,
        api_key: String,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        if api_key.trim().is_empty() {
            return Err(SourceError::InvalidRequest);
        }
        let http = SourceHttpSession::build(
            context.clone(),
            base_url.clone(),
            None,
            None,
            Some(api_key),
            config,
        )?;
        let base = reqwest::Url::parse(&base_url).map_err(|_| SourceError::InvalidRequest)?;
        Ok(Self {
            context,
            base,
            http: http.preserve_http_forbidden(),
            closed: AtomicBool::new(false),
        })
    }
    /// Basic is per-request authentication, with no shared CookieJar or redirect forwarding.
    #[uniffi::constructor]
    pub fn with_basic(
        context: SourceContext,
        base_url: String,
        authorization: String,
        config: SourceHttpConfig,
    ) -> Result<Self, SourceError> {
        if !authorization.starts_with("Basic ")
            || authorization.len() > 16384
            || authorization.len() <= 6
        {
            return Err(SourceError::InvalidRequest);
        }
        let http = SourceHttpSession::build(
            context.clone(),
            base_url.clone(),
            Some(authorization),
            None,
            None,
            config,
        )?;
        let base = reqwest::Url::parse(&base_url).map_err(|_| SourceError::InvalidRequest)?;
        Ok(Self {
            context,
            base,
            http: http.preserve_http_forbidden(),
            closed: AtomicBool::new(false),
        })
    }
    /// Reuse a browser-authenticated session only on this configured HTTPS origin.
    #[uniffi::constructor]
    pub fn with_session(context: SourceContext, base_url: String, token: String, config: SourceHttpConfig) -> Result<Self, SourceError> {
        if token.is_empty() || token.len() > 4096 || token.contains([';', '\r', '\n']) {
            return Err(SourceError::InvalidRequest);
        }
        let http = SourceHttpSession::build(context.clone(), base_url.clone(), None,
            Some(format!("KOMGA-SESSION={token}")), None, config)?;
        let base = reqwest::Url::parse(&base_url).map_err(|_| SourceError::InvalidRequest)?;
        Ok(Self { context, base, http: http.preserve_http_forbidden(), closed: AtomicBool::new(false) })
    }
    pub async fn account_id(&self, context: SourceContext) -> Result<String, SourceError> {
        self.check(&context)?;
        let user: User = parse(
            self.http
                .get(context.clone(), "api/v2/users/me".into())
                .await?,
        )?;
        if !valid_id(&user.id) {
            return Err(SourceError::InvalidResponse);
        }
        self.check(&context)?;
        Ok(user.id)
    }
    pub async fn authorized_lists(
        &self,
        context: SourceContext,
        kind: KomgaListKind,
        page: u32,
    ) -> Result<KomgaListPage, SourceError> {
        self.check(&context)?;
        let query = list_query(page)?;
        let (items, next_page) = if kind == KomgaListKind::Library {
            if page != 0 {
                return Err(SourceError::InvalidRequest);
            }
            let rows: Vec<LibraryName> = parse(
                self.http
                    .get(context.clone(), "api/v1/libraries".into())
                    .await?,
            )?;
            if rows.len() > 1000 {
                return Err(SourceError::InvalidResponse);
            }
            (
                rows.into_iter()
                    .map(|row| KomgaAuthorizedList {
                        id: row.id,
                        name: row.name,
                        kind,
                        ordered: false,
                        filtered: true,
                    })
                    .collect::<Vec<_>>(),
                None,
            )
        } else {
            let route = if kind == KomgaListKind::Collection {
                "collections"
            } else {
                "readlists"
            };
            let (rows, next) = unpack(
                parse::<PageList<SharedList>>(
                    self.http
                        .get(
                            context.clone(),
                            format!("api/v1/{route}?{query}&sort=name,asc"),
                        )
                        .await?,
                )?,
                page,
            )?;
            (
                rows.into_iter()
                    .map(|row| KomgaAuthorizedList {
                        id: row.id,
                        name: row.name,
                        kind,
                        ordered: row.ordered,
                        filtered: row.filtered,
                    })
                    .collect(),
                next_index(next),
            )
        };
        if items
            .iter()
            .any(|row| !valid_id(&row.id) || !valid_title(&row.name))
        {
            return Err(SourceError::InvalidResponse);
        }
        self.check(&context)?;
        Ok(KomgaListPage {
            context,
            items,
            next_page,
        })
    }
    pub async fn list_entries(
        &self,
        context: SourceContext,
        kind: KomgaListKind,
        id: String,
        page: u32,
    ) -> Result<KomgaImportPage, SourceError> {
        self.check(&context)?;
        if !valid_id(&id) {
            return Err(SourceError::InvalidRequest);
        }
        let query = list_query(page)?;
        let (items, next) = if kind == KomgaListKind::ReadList {
            let (rows, next) = unpack(
                parse::<PageList<ListedBook>>(
                    self.http
                        .get(
                            context.clone(),
                            format!("api/v1/readlists/{id}/books?{query}&deleted=false"),
                        )
                        .await?,
                )?,
                page,
            )?;
            let items = rows
                .into_iter()
                .enumerate()
                .map(|(index, row)| {
                    if !valid_id(&row.id) {
                        return Err(SourceError::InvalidResponse);
                    }
                    Ok(KomgaImportEntry {
                        series_id: row.series_id,
                        title: row.series_title,
                        book_id: Some(row.id),
                        position: page * PAGE_SIZE + index as u32,
                    })
                })
                .collect::<Result<Vec<_>, _>>()?;
            (items, next)
        } else {
            let reply = if kind == KomgaListKind::Library {
                let body = serde_json::to_vec(&json!({"condition":{"allOf":[{"libraryId":{"operator":"is","value":id}},{"deleted":{"operator":"isFalse"}}]}})).map_err(|_| SourceError::InvalidRequest)?;
                self.http
                    .komga_list(
                        context.clone(),
                        format!(
                            "api/v1/series/list?{query}&sort=metadata.titleSort,asc&sort=id,asc"
                        ),
                        body,
                    )
                    .await?
            } else {
                self.http
                    .get(
                        context.clone(),
                        format!("api/v1/collections/{id}/series?{query}&deleted=false"),
                    )
                    .await?
            };
            let (rows, next) = unpack(parse::<PageList<Series>>(reply)?, page)?;
            (
                rows.into_iter()
                    .enumerate()
                    .map(|(index, row)| KomgaImportEntry {
                        series_id: row.id,
                        title: row.metadata.title,
                        book_id: None,
                        position: page * PAGE_SIZE + index as u32,
                    })
                    .collect(),
                next,
            )
        };
        if items
            .iter()
            .any(|row| !valid_id(&row.series_id) || !valid_title(&row.title))
        {
            return Err(SourceError::InvalidResponse);
        }
        self.check(&context)?;
        Ok(KomgaImportPage {
            context,
            items,
            next_page: next_index(next),
        })
    }
    pub fn invalidate(&self) {
        self.closed.store(true, SeqCst);
        self.http.invalidate();
    }
    pub async fn connect(&self, context: SourceContext) -> Result<SourceContext, SourceError> {
        self.check(&context)?;
        let user: User = parse(
            self.http
                .get(context.clone(), "api/v2/users/me".into())
                .await?,
        )?;
        if user.id.trim().is_empty() {
            return Err(SourceError::InvalidResponse);
        }
        self.check(&context)?;
        Ok(context)
    }
    pub fn capabilities(&self) -> SourceCapabilities {
        <Self as MangaSource>::capabilities(self)
    }
    pub async fn search(
        &self,
        context: SourceContext,
        query: String,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        <Self as MangaSource>::search(self, context, query, page).await
    }
    pub async fn recommendations(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        <Self as MangaSource>::recommendations(self, context, page).await
    }
    pub async fn updates(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        <Self as MangaSource>::updates(self, context, page).await
    }
    /// Source tag names are opaque values, not path IDs. Never trim or case-fold them.
    pub async fn tags(
        &self,
        context: SourceContext,
        query: String,
        page: SourcePageRequest,
    ) -> Result<SourceTagPage, SourceError> {
        self.check(&context)?;
        if query.len() > 4096 {
            return Err(SourceError::InvalidRequest);
        }
        let number = page_number(page)?;
        let query = reqwest::Url::parse_with_params(
            "http://localhost/",
            &[
                ("include", "SERIES"),
                ("search", &query),
                ("page", &number.to_string()),
                ("size", &PAGE_SIZE.to_string()),
            ],
        )
        .map_err(|_| SourceError::InvalidRequest)?;
        let reply = self
            .http
            .get(
                context.clone(),
                format!("api/v2/tags?{}", query.query().unwrap_or_default()),
            )
            .await?;
        let (items, continuation) = unpack(parse::<PageList<String>>(reply)?, number)?;
        if items
            .iter()
            .any(|tag| tag.trim().is_empty() || tag.len() > 4096)
        {
            return Err(SourceError::InvalidResponse);
        }
        self.check(&context)?;
        Ok(SourceTagPage {
            context,
            items,
            continuation,
        })
    }
    pub async fn tagged(
        &self,
        context: SourceContext,
        tag: String,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        self.check(&context)?;
        if tag.trim().is_empty() || tag.len() > 4096 {
            return Err(SourceError::InvalidRequest);
        }
        let number = page_number(page)?;
        let body = serde_json::to_vec(&json!({"condition":{"allOf":[{"tag":{"operator":"is","value":tag}},{"deleted":{"operator":"isFalse"}}]}})).map_err(|_| SourceError::InvalidRequest)?;
        let reply = self
            .http
            .komga_list(
                context.clone(),
                format!(
                    "api/v1/series/list?page={number}&size={PAGE_SIZE}&sort=metadata.titleSort,asc"
                ),
                body,
            )
            .await?;
        let (items, continuation) = unpack(parse::<PageList<Series>>(reply)?, number)?;
        let items = items
            .into_iter()
            .map(|s| self.manga(s))
            .collect::<Result<Vec<_>, _>>()?;
        self.check(&context)?;
        Ok(SourceSearchPage {
            context,
            items,
            continuation,
        })
    }
    pub async fn detail(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
    ) -> Result<SourceDetail, SourceError> {
        <Self as MangaSource>::detail(self, context, key).await
    }
    pub async fn chapters(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        page: SourcePageRequest,
    ) -> Result<SourceChapterPage, SourceError> {
        <Self as MangaSource>::chapters(self, context, key, page).await
    }
    pub async fn pages(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        chapter_id: String,
        page: SourcePageRequest,
    ) -> Result<SourcePages, SourceError> {
        <Self as MangaSource>::pages(self, context, key, chapter_id, page).await
    }
    /// Bounded transport probe only; full reader streaming/decoding remains separate.
    pub async fn page_bytes(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        chapter_id: String,
        number: u32,
    ) -> Result<SourceHttpReply, SourceError> {
        self.key_check(&context, &key)?;
        let book = self.book(&context, &key, &chapter_id).await?;
        if number == 0 || number > book.media.pages_count {
            return Err(SourceError::InvalidRequest);
        }
        let result = self
            .http
            .get(
                context.clone(),
                format!("api/v1/books/{chapter_id}/pages/{number}"),
            )
            .await?;
        self.check(&context)?;
        Ok(result)
    }
    /// Authenticated reader output. Caller supplies a private cache directory and
    /// keeps the returned owner alive until Android decoding completes.
    pub async fn page_file(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        chapter_id: String,
        number: u32,
        directory: String,
    ) -> Result<Arc<SourceImageFile>, SourceError> {
        self.key_check(&context, &key)?;
        let book = self.book(&context, &key, &chapter_id).await?;
        if number == 0 || number > book.media.pages_count {
            return Err(SourceError::InvalidRequest);
        }
        let file = self
            .http
            .image_file(
                context.clone(),
                format!("api/v1/books/{chapter_id}/pages/{number}"),
                directory,
            )
            .await?;
        self.check(&context)?;
        Ok(Arc::new(file))
    }
    /// Official series poster endpoint from the pinned 1.29.1 OpenAPI snapshot.
    pub async fn cover_file(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        directory: String,
    ) -> Result<Arc<SourceImageFile>, SourceError> {
        self.key_check(&context, &key)?;
        let file = self
            .http
            .image_file(
                context.clone(),
                format!("api/v1/series/{}/thumbnail", key.manga_id),
                directory,
            )
            .await?;
        self.check(&context)?;
        Ok(Arc::new(file))
    }
}
impl MangaSource for KomgaSource {
    fn capabilities(&self) -> SourceCapabilities {
        SourceCapabilities {
            search: true,
            recommendations: false,
            updates: true,
            tags: true,
            detail: true,
            chapters: true,
            pages: true,
            pagination: SourcePagination::PageNumber,
        }
    }
    fn recommendations(
        &self,
        context: SourceContext,
        _page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage> {
        Box::pin(async move {
            self.check(&context)?;
            Err(SourceError::Unsupported {
                operation: SourceOperation::Recommendations,
            })
        })
    }
    fn search(
        &self,
        context: SourceContext,
        query: String,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage> {
        Box::pin(async move {
            self.check(&context)?;
            if query.len() > 4096 {
                return Err(SourceError::InvalidRequest);
            }
            let number = page_number(page)?;
            let body = serde_json::to_vec(&json!({"fullTextSearch":query}))
                .map_err(|_| SourceError::InvalidRequest)?;
            let reply = self.http.komga_list(context.clone(), format!("api/v1/series/list?page={number}&size={PAGE_SIZE}&sort=metadata.titleSort,asc"), body).await?;
            let (items, continuation) = unpack(parse::<PageList<Series>>(reply)?, number)?;
            let items = items
                .into_iter()
                .map(|s| self.manga(s))
                .collect::<Result<Vec<_>, _>>()?;
            self.check(&context)?;
            Ok(SourceSearchPage {
                context,
                items,
                continuation,
            })
        })
    }
    fn updates(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage> {
        Box::pin(async move {
            self.check(&context)?;
            let number = page_number(page)?;
            // Official 1.29.1 updated series, excluding newly added series. Server owns ordering.
            let reply = self
                .http
                .get(
                    context.clone(),
                    format!("api/v1/series/updated?deleted=false&page={number}&size={PAGE_SIZE}"),
                )
                .await?;
            let (items, continuation) = unpack(parse::<PageList<Series>>(reply)?, number)?;
            let items = items
                .into_iter()
                .map(|s| self.manga(s))
                .collect::<Result<Vec<_>, _>>()?;
            self.check(&context)?;
            Ok(SourceSearchPage {
                context,
                items,
                continuation,
            })
        })
    }
    fn detail(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
    ) -> SourceFuture<'_, SourceDetail> {
        Box::pin(async move {
            self.key_check(&context, &key)?;
            let series: DetailedSeries = parse(
                self.http
                    .get(context.clone(), format!("api/v1/series/{}", key.manga_id))
                    .await?,
            )?;
            if series.id != key.manga_id {
                return Err(SourceError::InvalidResponse);
            }
            let description = series
                .metadata
                .base
                .summary
                .clone()
                .filter(|s| !s.trim().is_empty());
            let mut metadata = crate::detail_metadata::komga_metadata(
                &series.books_metadata["authors"], &series.metadata.status, &series.metadata.base.modified);
            if !series.books_metadata.is_null() && !series.books_metadata.is_object() { metadata.partial = true; }
            let tags = detail_tags(series.metadata.tags)?;
            let manga = self.manga(Series {
                id: series.id,
                metadata: series.metadata.base,
            })?;
            self.check(&context)?;
            Ok(SourceDetail {
                context,
                manga,
                description,
                tags,
                metadata,
            })
        })
    }
    fn chapters(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceChapterPage> {
        Box::pin(async move {
            self.key_check(&context, &key)?;
            let number = page_number(page)?;
            let body = serde_json::to_vec(
                &json!({"condition":{"seriesId":{"operator":"is","value":key.manga_id}}}),
            )
            .map_err(|_| SourceError::InvalidRequest)?;
            let reply = self.http.komga_list(context.clone(), format!("api/v1/books/list?page={number}&size={PAGE_SIZE}&sort=metadata.numberSort,asc"), body).await?;
            let page = parse::<PageList<Book>>(reply)?;
            let total = page.total;
            let (books, continuation) = unpack(page, number)?;
            let items = books
                .into_iter()
                .enumerate()
                .map(|(index, book)| {
                    if book.series_id != key.manga_id
                        || !valid_id(&book.id)
                        || book.metadata.title.trim().is_empty()
                    {
                        return Err(SourceError::InvalidResponse);
                    }
                    Ok(SourceChapter {
                        manga: key.clone(),
                        id: book.id,
                        title: book.metadata.title,
                        ordinal: number * PAGE_SIZE + index as u32 + 1,
                        page_count: Some(book.media.pages_count),
                    })
                })
                .collect::<Result<Vec<_>, _>>()?;
            self.check(&context)?;
            Ok(SourceChapterPage {
                context,
                items,
                continuation,
                total,
            })
        })
    }
    fn pages(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        chapter_id: String,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourcePages> {
        Box::pin(async move {
            self.key_check(&context, &key)?;
            if page_number(page)? != 0 {
                return Err(SourceError::InvalidRequest);
            }
            let book = self.book(&context, &key, &chapter_id).await?;
            let mut pages: Vec<Page> = parse(
                self.http
                    .get(context.clone(), format!("api/v1/books/{chapter_id}/pages"))
                    .await?,
            )?;
            if pages.len() != book.media.pages_count as usize {
                return Err(SourceError::InvalidResponse);
            }
            pages.sort_by_key(|p| p.number);
            let items = pages
                .into_iter()
                .enumerate()
                .map(|(index, p)| {
                    if p.number != index as u32 + 1
                        || !p.media_type.starts_with("image/")
                        || p.width == Some(0)
                        || p.height == Some(0)
                    {
                        return Err(SourceError::InvalidResponse);
                    }
                    let path = format!("api/v1/books/{chapter_id}/pages/{}", p.number);
                    let uri = self
                        .base
                        .join(&path)
                        .map_err(|_| SourceError::InvalidResponse)?
                        .to_string();
                    Ok(SourcePageResource {
                        manga: key.clone(),
                        chapter_id: chapter_id.clone(),
                        id: p.number.to_string(),
                        ordinal: p.number,
                        uri,
                        mime_type: Some(p.media_type),
                        width: p.width,
                        height: p.height,
                        context: context.clone(),
                    })
                })
                .collect::<Result<Vec<_>, _>>()?;
            self.check(&context)?;
            Ok(SourcePages {
                context,
                items,
                continuation: SourceContinuation::End,
            })
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture_context() -> SourceContext {
        SourceContext {
            world: SourceWorld::Sfw,
            source_id: "updates-fixture".into(),
            process_session: "fixture".into(),
            generation: 1,
            auth_ref: Some("opaque".into()),
        }
    }
    fn fixture_source(base: String) -> KomgaSource {
        KomgaSource::new(
            fixture_context(),
            base,
            "synthetic-key".into(),
            SourceHttpConfig {
                timeout_ms: 3000,
                max_in_flight: 2,
                min_interval_ms: 0,
                max_retries: 1,
                max_body_bytes: 65536,
            },
        )
        .unwrap()
    }
    #[test]
    fn updates_http_paging_retry_auth_and_invalid_responses() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let source = fixture_source(format!("http://{}/proxy/", listener.local_addr().unwrap()));
        listener.set_nonblocking(true).unwrap();
        let server = std::thread::spawn(move || {
            let responses = [
                (
                    0,
                    200,
                    r#"{"content":[],"number":0,"size":20,"last":false}"#,
                ),
                (1, 503, "{}"),
                (
                    1,
                    200,
                    r#"{"content":[{"id":"s2","metadata":{"title":"Synthetic update"}}],"number":1,"size":20,"last":true}"#,
                ),
                (0, 401, "{}"),
                (0, 200, r#"{"content":[],"number":9,"size":20,"last":true}"#),
                (
                    0,
                    200,
                    r#"{"content":[{"id":"../bad","metadata":{"title":"Synthetic"}}],"number":0,"size":20,"last":true}"#,
                ),
            ];
            for (page, status, body) in responses {
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
                let mut socket = loop {
                    if let Ok((socket, _)) = listener.accept() {
                        break socket;
                    }
                    assert!(
                        std::time::Instant::now() < deadline,
                        "missing expected request"
                    );
                    std::thread::sleep(std::time::Duration::from_millis(5));
                };
                // Accepted sockets inherit nonblocking mode on Windows; reads below are blocking.
                socket.set_nonblocking(false).unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                    .unwrap();
                let mut request = Vec::new();
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0];
                    socket.read_exact(&mut byte).unwrap();
                    request.push(byte[0]);
                }
                let request = String::from_utf8_lossy(&request).to_ascii_lowercase();
                assert!(request.starts_with(&format!(
                    "get /proxy/api/v1/series/updated?deleted=false&page={page}&size=20 "
                )));
                assert!(request.contains("x-api-key: synthetic-key"));
                write!(socket, "HTTP/1.1 {status} Fixture\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
            }
        });
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let context = fixture_context();
                assert!(source.capabilities().updates);
                assert!(!source.capabilities().recommendations);
                assert!(matches!(
                    source
                        .recommendations(context.clone(), SourcePageRequest::Start)
                        .await,
                    Err(SourceError::Unsupported {
                        operation: SourceOperation::Recommendations
                    })
                ));
                let first = source
                    .updates(context.clone(), SourcePageRequest::Start)
                    .await
                    .unwrap();
                assert!(first.items.is_empty());
                let SourceContinuation::Next { request } = first.continuation else {
                    panic!("empty non-last page must continue")
                };
                assert_eq!(request, SourcePageRequest::Page { number: 2 });
                let second = source.updates(context.clone(), request).await.unwrap();
                assert_eq!(second.context, context);
                assert_eq!(second.items[0].key.source_id, context.source_id);
                assert_eq!(second.items[0].key.manga_id, "s2");
                assert_eq!(second.continuation, SourceContinuation::End);
                assert!(matches!(
                    source
                        .updates(context.clone(), SourcePageRequest::Start)
                        .await,
                    Err(SourceError::AuthenticationRequired)
                ));
                for _ in 0..2 {
                    assert!(matches!(
                        source
                            .updates(context.clone(), SourcePageRequest::Start)
                            .await,
                        Err(SourceError::InvalidResponse)
                    ));
                }
                assert!(matches!(
                    source
                        .updates(context.clone(), SourcePageRequest::Page { number: 0 })
                        .await,
                    Err(SourceError::InvalidRequest)
                ));
                let mut stale = context.clone();
                stale.generation += 1;
                assert!(matches!(
                    source.updates(stale, SourcePageRequest::Start).await,
                    Err(SourceError::SessionMismatch)
                ));
                source.invalidate();
                assert!(matches!(
                    source.updates(context, SourcePageRequest::Start).await,
                    Err(SourceError::SessionMismatch)
                ));
            });
        server.join().unwrap();
    }

    #[test]
    fn tag_directory_and_series_filter_use_paged_read_only_protocol() {
        use std::io::{Read, Write};
        let tag = " 标签 / \" &? A ";
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let source = fixture_source(format!("http://{}/proxy/", listener.local_addr().unwrap()));
        let server = std::thread::spawn(move || {
            let responses = [
                (
                    "tags",
                    0,
                    200,
                    r#"{"content":["Tag","tag"],"number":0,"size":20,"last":false}"#,
                ),
                (
                    "tags",
                    1,
                    200,
                    r#"{"content":[],"number":1,"size":20,"last":false}"#,
                ),
                ("tags", 2, 401, "{}"),
                (
                    "tags",
                    2,
                    200,
                    r#"{"content":["final"],"number":2,"size":20,"last":true}"#,
                ),
                (
                    "series",
                    0,
                    200,
                    r#"{"content":[{"id":"s1","metadata":{"title":"same"}},{"id":"s2","metadata":{"title":"same"}}],"number":0,"size":20,"last":false}"#,
                ),
                ("series", 1, 503, "{}"),
                (
                    "series",
                    1,
                    200,
                    r#"{"content":[],"number":1,"size":20,"last":true}"#,
                ),
                (
                    "tags",
                    0,
                    200,
                    r#"{"content":[" "],"number":0,"size":20,"last":true}"#,
                ),
            ];
            for (kind, page, status, body) in responses {
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
                let mut socket = loop {
                    if let Ok((socket, _)) = listener.accept() {
                        break socket;
                    }
                    assert!(std::time::Instant::now() < deadline);
                    std::thread::sleep(std::time::Duration::from_millis(5));
                };
                // Accepted sockets inherit nonblocking mode on Windows; reads below are blocking.
                socket.set_nonblocking(false).unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                    .unwrap();
                let mut request = Vec::new();
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0];
                    socket.read_exact(&mut byte).unwrap();
                    request.push(byte[0]);
                }
                let header = String::from_utf8(request).unwrap();
                let lower = header.to_ascii_lowercase();
                assert!(lower.contains("x-api-key: synthetic-key"));
                let path = header.split_whitespace().nth(1).unwrap();
                let url = reqwest::Url::parse(&format!("http://fixture{path}")).unwrap();
                let params: std::collections::HashMap<_, _> =
                    url.query_pairs().into_owned().collect();
                assert_eq!(params.get("page").unwrap(), &page.to_string());
                assert_eq!(params.get("size").unwrap(), "20");
                if kind == "tags" {
                    assert!(header.starts_with("GET "));
                    assert_eq!(url.path(), "/proxy/api/v2/tags");
                    assert_eq!(params.get("include").unwrap(), "SERIES");
                    assert_eq!(params.get("search").unwrap(), tag);
                } else {
                    assert!(header.starts_with("POST "));
                    assert_eq!(url.path(), "/proxy/api/v1/series/list");
                    let length: usize = lower
                        .lines()
                        .find_map(|line| line.strip_prefix("content-length: "))
                        .unwrap()
                        .trim()
                        .parse()
                        .unwrap();
                    let mut body = vec![0; length];
                    socket.read_exact(&mut body).unwrap();
                    let value: serde_json::Value = serde_json::from_slice(&body).unwrap();
                    assert_eq!(
                        value,
                        json!({"condition":{"allOf":[{"tag":{"operator":"is","value":tag}},{"deleted":{"operator":"isFalse"}}]}})
                    );
                    assert_eq!(params.get("sort").unwrap(), "metadata.titleSort,asc");
                }
                write!(socket,"HTTP/1.1 {status} Fixture\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).unwrap();
            }
        });
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let c = fixture_context();
                assert!(source.capabilities().tags);
                let first = source
                    .tags(c.clone(), tag.into(), SourcePageRequest::Start)
                    .await
                    .unwrap();
                assert_eq!(first.context, c);
                assert_eq!(first.items, vec!["Tag", "tag"]);
                assert_eq!(
                    first.continuation,
                    SourceContinuation::Next {
                        request: SourcePageRequest::Page { number: 2 }
                    }
                );
                let empty = source
                    .tags(c.clone(), tag.into(), SourcePageRequest::Page { number: 2 })
                    .await
                    .unwrap();
                assert!(empty.items.is_empty());
                assert!(matches!(
                    empty.continuation,
                    SourceContinuation::Next { .. }
                ));
                assert!(matches!(
                    source
                        .tags(c.clone(), tag.into(), SourcePageRequest::Page { number: 3 })
                        .await,
                    Err(SourceError::AuthenticationRequired)
                ));
                assert_eq!(
                    source
                        .tags(c.clone(), tag.into(), SourcePageRequest::Page { number: 3 })
                        .await
                        .unwrap()
                        .continuation,
                    SourceContinuation::End
                );
                let books = source
                    .tagged(c.clone(), tag.into(), SourcePageRequest::Start)
                    .await
                    .unwrap();
                assert_eq!(books.items.len(), 2);
                assert_eq!(books.items[0].key.source_id, c.source_id);
                assert_eq!(
                    source
                        .tagged(c.clone(), tag.into(), SourcePageRequest::Page { number: 2 })
                        .await
                        .unwrap()
                        .continuation,
                    SourceContinuation::End
                );
                assert!(matches!(
                    source
                        .tags(c.clone(), tag.into(), SourcePageRequest::Start)
                        .await,
                    Err(SourceError::InvalidResponse)
                ));
                assert!(matches!(
                    source
                        .tagged(c.clone(), " ".into(), SourcePageRequest::Start)
                        .await,
                    Err(SourceError::InvalidRequest)
                ));
                assert!(matches!(
                    source
                        .tags(c.clone(), "".into(), SourcePageRequest::Page { number: 0 })
                        .await,
                    Err(SourceError::InvalidRequest)
                ));
                let mut stale = c.clone();
                stale.generation += 1;
                assert!(matches!(
                    source
                        .tags(stale, "".into(), SourcePageRequest::Start)
                        .await,
                    Err(SourceError::SessionMismatch)
                ));
                source.invalidate();
                assert!(matches!(
                    source.tagged(c, tag.into(), SourcePageRequest::Start).await,
                    Err(SourceError::SessionMismatch)
                ));
            });
        server.join().unwrap();
    }

    #[test]
    fn detail_tag_metadata_distinguishes_missing_empty_and_invalid() {
        for (json, expected) in [
            (r#"{"id":"s","metadata":{"title":"fixture"}}"#, None),
            (
                r#"{"id":"s","metadata":{"title":"fixture","tags":null}}"#,
                None,
            ),
            (
                r#"{"id":"s","metadata":{"title":"fixture","tags":[]}}"#,
                Some(vec![]),
            ),
            (
                r#"{"id":"s","metadata":{"title":"fixture","tags":["Tag","tag"," Tag ","Tag"],"genres":["genre"]},"booksMetadata":{"tags":["book-tag"]}}"#,
                Some(vec!["Tag".into(), "tag".into(), " Tag ".into()]),
            ),
        ] {
            let series: DetailedSeries = serde_json::from_str(json).unwrap();
            assert_eq!(detail_tags(series.metadata.tags).unwrap(), expected);
        }
        assert_eq!(
            detail_tags(Some(vec![" ".into()])),
            Err(SourceError::InvalidResponse)
        );
        assert_eq!(
            detail_tags(Some(vec!["a".repeat(4097)])),
            Err(SourceError::InvalidResponse)
        );
        for tags in ["{}", "[12]", "\"comma,list\""] {
            let json = format!(r#"{{"id":"s","metadata":{{"title":"fixture","tags":{tags}}}}}"#);
            assert!(serde_json::from_str::<DetailedSeries>(&json).is_err());
            // Non-detail paths still skip this optional metadata, without allocating tags.
            assert!(serde_json::from_str::<Series>(&json).is_ok());
        }
    }

    #[test]
    fn detail_exposes_literal_series_tags_without_an_extra_directory_request() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let source = fixture_source(format!("http://{}/proxy/", listener.local_addr().unwrap()));
        let tag = " 标签 / \" &? ";
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                .unwrap();
            let mut request = Vec::new();
            while !request.ends_with(b"\r\n\r\n") {
                let mut byte = [0];
                socket.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            let request = String::from_utf8(request).unwrap().to_ascii_lowercase();
            assert!(request.starts_with("get /proxy/api/v1/series/s1 "));
            assert!(request.contains("x-api-key: synthetic-key"));
            let body=json!({"id":"s1","metadata":{"title":"fixture","summary":"summary","status":"HIATUS","lastModified":"2026-10-01T12:30:00","tags":[tag,"Tag",tag],"genres":["not-a-tag"]},"booksMetadata":{"authors":[{"name":"Synthetic Creator","role":"writer"}],"tags":["book-tag"]}}).to_string();
            write!(socket,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",body.len()).unwrap();
            socket.shutdown(std::net::Shutdown::Write).unwrap();
            // Keep listener alive until caller finishes; only the existing detail GET is accepted.
            listener
        });
        let c = fixture_context();
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let detail = source
                    .detail(
                        c.clone(),
                        SourceMangaKey {
                            world: c.world.clone(),
                            source_id: c.source_id.clone(),
                            manga_id: "s1".into(),
                        },
                    )
                    .await
                    .unwrap();
                assert_eq!(detail.context, c);
                assert_eq!(detail.manga.key.source_id, c.source_id);
                assert_eq!(detail.description.as_deref(), Some("summary"));
                assert_eq!(detail.tags, Some(vec![tag.into(), "Tag".into()]));
                assert_eq!(detail.metadata.authors[0].name, "Synthetic Creator");
                assert_eq!(detail.metadata.status.as_deref(), Some("休刊中"));
                assert_eq!(detail.metadata.updated_at.as_deref(), Some("2026-10-01T12:30:00"));
                assert_eq!(detail.metadata.update_kind, Some(crate::detail_metadata::SourceUpdateKind::Metadata));
            });
        let listener = server.join().unwrap();
        listener.set_nonblocking(true).unwrap();
        assert_eq!(
            listener.accept().err().unwrap().kind(),
            std::io::ErrorKind::WouldBlock
        );
    }

    /// Host-only same-payload comparison. Not a device/rendering benchmark.
    #[test]
    #[ignore]
    fn discovery_parse_cost_comparison() {
        let source = fixture_source("http://127.0.0.1:1/".into());
        let body = serde_json::to_vec(&json!({"content": (0..20).map(|i| json!({"id":format!("s{i}"),"metadata":{"title":format!("Synthetic {i}"),"summary":"fixture"}})).collect::<Vec<_>>(),"number":0,"size":20,"last":false})).unwrap();
        for route in ["search", "updates"] {
            let mut timings = Vec::new();
            for _ in 0..21 {
                let begin = std::time::Instant::now();
                for _ in 0..2000 {
                    let reply = SourceHttpReply {
                        context: fixture_context(),
                        body: body.clone(),
                        content_type: Some("application/json".into()),
                    };
                    let (rows, next) =
                        unpack(parse::<PageList<Series>>(reply).unwrap(), 0).unwrap();
                    let cards = rows
                        .into_iter()
                        .map(|s| source.manga(s))
                        .collect::<Result<Vec<_>, _>>()
                        .unwrap();
                    std::hint::black_box((cards, next));
                }
                timings.push(begin.elapsed().as_secs_f64() * 1_000_000.0 / 2000.0);
            }
            timings.sort_by(f64::total_cmp);
            println!("{route}: 20 items, {} bytes, 21 x 2000 iterations, parse+map p50={:.2}us p95={:.2}us", body.len(), timings[10], timings[19]);
        }
    }
    #[test]
    fn poster_uses_pinned_endpoint_auth_and_owned_file() {
        use std::io::{Read, Write};
        let directory = std::env::temp_dir().join(format!(
            "flux-poster-test-{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}/proxy/", listener.local_addr().unwrap());
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                .unwrap();
            let mut request = Vec::new();
            while !request.ends_with(b"\r\n\r\n") {
                let mut byte = [0];
                socket.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            let request = String::from_utf8_lossy(&request).to_ascii_lowercase();
            assert!(request.starts_with("get /proxy/api/v1/series/book/thumbnail "));
            assert!(request.contains("x-api-key: synthetic-key"));
            socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\nConnection: close\r\n\r\nimage").unwrap();
        });
        let context = SourceContext {
            world: SourceWorld::Sfw,
            source_id: "komga-a".into(),
            process_session: "fixture".into(),
            generation: 1,
            auth_ref: Some("auth".into()),
        };
        let key = SourceMangaKey {
            world: SourceWorld::Sfw,
            source_id: "komga-a".into(),
            manga_id: "book".into(),
        };
        let source = KomgaSource::new(
            context.clone(),
            base,
            "synthetic-key".into(),
            SourceHttpConfig {
                timeout_ms: 1000,
                max_in_flight: 1,
                min_interval_ms: 0,
                max_retries: 0,
                max_body_bytes: 1024,
            },
        )
        .unwrap();
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let file = source
                    .cover_file(context, key, directory.to_string_lossy().into())
                    .await
                    .unwrap();
                assert_eq!(std::fs::read(file.path()).unwrap(), b"image");
                drop(file);
                assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 0);
            });
        server.join().unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
    #[test]
    fn paging_and_safe_ids() {
        assert_eq!(page_number(SourcePageRequest::Start), Ok(0));
        assert_eq!(page_number(SourcePageRequest::Page { number: 2 }), Ok(1));
        for n in [0, u32::MAX] {
            assert_eq!(
                page_number(SourcePageRequest::Page { number: n }),
                Err(SourceError::InvalidRequest)
            );
        }
        for id in ["", "../x", "a/b", "a%2fb", "?key=secret"] {
            assert!(!valid_id(id));
        }
        assert!(valid_id("0ABC-1_x"));
        assert!(unpack(
            PageList {
                content: Vec::<Series>::new(),
                number: 1,
                size: 20,
                last: true,
                total: None,
            },
            0
        )
        .is_err());
    }
    #[test]
    fn malformed_payloads_are_safe_errors() {
        let context = SourceContext {
            world: SourceWorld::Sfw,
            source_id: "komga".into(),
            process_session: "p".into(),
            generation: 0,
            auth_ref: Some("ref".into()),
        };
        for (body, mime) in [
            ("<html>secret</html>", "text/html"),
            ("{\"id\":false}", "application/json"),
            ("{}", "application/json"),
        ] {
            let error = parse::<User>(SourceHttpReply {
                context: context.clone(),
                body: body.as_bytes().to_vec(),
                content_type: Some(mime.into()),
            })
            .err()
            .unwrap();
            assert_eq!(error, SourceError::InvalidResponse);
            assert!(!error.to_string().contains("secret"));
        }
    }
}

#[cfg(test)]
#[path = "komga_account_tests.rs"]
mod account_tests;
