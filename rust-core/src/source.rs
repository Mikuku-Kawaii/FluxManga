//! M3 protocol and offline contract fixture. No HTTP, credentials or library writes.
use std::{future::Future, pin::Pin};

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SourceWorld {
    Sfw,
    Nsfw,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SourceMangaKey {
    pub world: SourceWorld,
    pub source_id: String,
    pub manga_id: String,
}

/// A reference to a caller-owned credential scope, never a Cookie/Token value.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct SourceContext {
    pub world: SourceWorld,
    pub source_id: String,
    pub process_session: String,
    pub generation: u64,
    pub auth_ref: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SourcePagination {
    PageNumber,
    Cursor,
}

/// Pages are one-based; Start also represents the first cursor request.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SourcePageRequest {
    Start,
    Page { number: u32 },
    Cursor { token: String },
}

/// End is explicit; an empty page need not imply end of pagination.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SourceContinuation {
    End,
    Next { request: SourcePageRequest },
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SourceCapabilities {
    pub search: bool,
    pub recommendations: bool,
    pub updates: bool,
    pub tags: bool,
    pub detail: bool,
    pub chapters: bool,
    pub pages: bool,
    pub pagination: SourcePagination,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SourceOperation {
    Search,
    Recommendations,
    Updates,
    Tags,
    Tagged,
    Detail,
    Chapters,
    Pages,
}

/// Fixed messages deliberately exclude URLs, headers and server response bodies.
#[derive(Debug, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum SourceError {
    #[error("source capability unavailable")]
    Unsupported { operation: SourceOperation },
    #[error("invalid source request")]
    InvalidRequest,
    #[error("source session mismatch")]
    SessionMismatch,
    #[error("source item not found")]
    NotFound,
    #[error("source authentication required")]
    AuthenticationRequired,
    // Fixed reason keys only; never forward server text, credentials or response bodies.
    #[error("login rejected ({code}: {reason})")]
    LoginRejected { code: u32, reason: String },
    #[error("source request timed out")]
    Timeout,
    #[error("source rate limited")]
    RateLimited { retry_after_seconds: Option<u32> },
    #[error("source transport unavailable")]
    Transport,
    #[error("invalid source response")]
    InvalidResponse,
    #[error("source HTTP status {status}")]
    HttpStatus { status: u16 },
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SourceManga {
    pub key: SourceMangaKey,
    pub title: String,
    pub page_count: Option<u32>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SourceSearchPage {
    pub context: SourceContext,
    pub items: Vec<SourceManga>,
    pub continuation: SourceContinuation,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SourceTagPage {
    pub context: SourceContext,
    pub items: Vec<String>,
    pub continuation: SourceContinuation,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SourceDetail {
    pub context: SourceContext,
    pub manga: SourceManga,
    pub description: Option<String>,
    pub tags: Option<Vec<String>>,
    pub metadata: crate::detail_metadata::SourceWorkMetadata,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SourceChapter {
    pub manga: SourceMangaKey,
    pub id: String,
    pub title: String,
    pub ordinal: u32,
    pub page_count: Option<u32>,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SourceChapterPage {
    pub context: SourceContext,
    pub items: Vec<SourceChapter>,
    pub continuation: SourceContinuation,
    pub total: Option<u32>,
}

/// Resource identity and auth scope stay attached through image loading. Fixture URIs
/// are intentionally not HTTP and must never be handed to the CBZ archive reader.
#[derive(Clone, Debug, uniffi::Record)]
pub struct SourcePageResource {
    pub manga: SourceMangaKey,
    pub chapter_id: String,
    pub id: String,
    pub ordinal: u32,
    pub uri: String,
    pub mime_type: Option<String>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub context: SourceContext,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct SourcePages {
    pub context: SourceContext,
    pub items: Vec<SourcePageResource>,
    pub continuation: SourceContinuation,
}

pub type SourceFuture<'a, T> = Pin<Box<dyn Future<Output = Result<T, SourceError>> + Send + 'a>>;

/// Internal async boundary; FFI exports concrete records, not this trait or JSON.
pub trait MangaSource: Send + Sync {
    fn capabilities(&self) -> SourceCapabilities;
    fn search(
        &self,
        context: SourceContext,
        query: String,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage>;
    fn recommendations(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage>;
    fn updates(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage>;
    fn detail(&self, context: SourceContext, key: SourceMangaKey)
        -> SourceFuture<'_, SourceDetail>;
    fn chapters(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceChapterPage>;
    fn pages(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        chapter_id: String,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourcePages>;
}

struct ContractFixture {
    context: SourceContext,
    pagination: SourcePagination,
}

impl ContractFixture {
    fn check(&self, context: &SourceContext) -> Result<(), SourceError> {
        if context != &self.context {
            return Err(SourceError::SessionMismatch);
        }
        Ok(())
    }
    fn key(&self, id: &str) -> SourceMangaKey {
        SourceMangaKey {
            world: SourceWorld::Sfw,
            source_id: self.context.source_id.clone(),
            manga_id: id.into(),
        }
    }
    fn manga(&self, key: SourceMangaKey) -> Result<SourceManga, SourceError> {
        if key.world != self.context.world || key.source_id != self.context.source_id {
            return Err(SourceError::SessionMismatch);
        }
        let title = match key.manga_id.as_str() {
            "1" | "2" => "合成作品 · 同名",
            "3" => "Synthetic 三",
            _ => return Err(SourceError::NotFound),
        };
        Ok(SourceManga {
            key,
            title: title.into(),
            page_count: None,
        })
    }
    fn paginate<T: Clone>(
        &self,
        items: &[T],
        page: SourcePageRequest,
    ) -> Result<(Vec<T>, SourceContinuation), SourceError> {
        let offset = match (&self.pagination, page) {
            (_, SourcePageRequest::Start) => 0,
            (SourcePagination::PageNumber, SourcePageRequest::Page { number }) if number > 0 => {
                ((number - 1) as u64) * 2
            }
            (SourcePagination::Cursor, SourcePageRequest::Cursor { token })
                if token == "fixture-next-2" =>
            {
                2
            }
            _ => return Err(SourceError::InvalidRequest),
        };
        if offset > items.len() as u64 {
            return Err(SourceError::InvalidRequest);
        }
        let start = offset as usize;
        let end = (start + 2).min(items.len());
        let continuation = if end == items.len() {
            SourceContinuation::End
        } else {
            SourceContinuation::Next {
                request: match self.pagination {
                    SourcePagination::PageNumber => SourcePageRequest::Page {
                        number: (end / 2 + 1) as u32,
                    },
                    SourcePagination::Cursor => SourcePageRequest::Cursor {
                        token: "fixture-next-2".into(),
                    },
                },
            }
        };
        Ok((items[start..end].to_vec(), continuation))
    }
}

impl MangaSource for ContractFixture {
    fn capabilities(&self) -> SourceCapabilities {
        SourceCapabilities {
            search: true,
            recommendations: false,
            updates: false,
            tags: false,
            detail: true,
            chapters: true,
            pages: true,
            pagination: self.pagination.clone(),
        }
    }
    fn search(
        &self,
        context: SourceContext,
        query: String,
        page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage> {
        Box::pin(async move {
            self.check(&context)?;
            let query = query.trim().to_lowercase();
            let items = ["1", "2", "3"]
                .into_iter()
                .map(|id| self.manga(self.key(id)))
                .collect::<Result<Vec<_>, _>>()?
                .into_iter()
                .filter(|m| m.title.to_lowercase().contains(&query))
                .collect::<Vec<_>>();
            let (items, continuation) = self.paginate(&items, page)?;
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
        _page: SourcePageRequest,
    ) -> SourceFuture<'_, SourceSearchPage> {
        Box::pin(async move {
            self.check(&context)?;
            Err(SourceError::Unsupported {
                operation: SourceOperation::Updates,
            })
        })
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
    fn detail(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
    ) -> SourceFuture<'_, SourceDetail> {
        Box::pin(async move {
            self.check(&context)?;
            Ok(SourceDetail {
                context,
                manga: self.manga(key)?,
                description: Some("仅用于协议验证的合成元数据".into()),
                tags: None,
                metadata: Default::default(),
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
            self.check(&context)?;
            self.manga(key.clone())?;
            let chapters = (1..=3)
                .map(|n| SourceChapter {
                    manga: key.clone(),
                    id: format!("chapter-{n}"),
                    title: format!("合成章节 {n}"),
                    ordinal: n,
                    page_count: Some(3),
                })
                .collect::<Vec<_>>();
            let (items, continuation) = self.paginate(&chapters, page)?;
            Ok(SourceChapterPage {
                context,
                items,
                continuation,
                total: Some(chapters.len() as u32),
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
            self.check(&context)?;
            self.manga(key.clone())?;
            if !["chapter-1", "chapter-2", "chapter-3"].contains(&chapter_id.as_str()) {
                return Err(SourceError::NotFound);
            }
            let pages = (1..=3)
                .map(|n| SourcePageResource {
                    manga: key.clone(),
                    chapter_id: chapter_id.clone(),
                    id: format!("page-{n}"),
                    ordinal: n,
                    uri: format!(
                        "fixture://{}/{}/{chapter_id}/{n}",
                        key.source_id, key.manga_id
                    ),
                    mime_type: Some("image/png".into()),
                    width: Some(600),
                    height: Some(800),
                    context: context.clone(),
                })
                .collect::<Vec<_>>();
            let (items, continuation) = self.paginate(&pages, page)?;
            Ok(SourcePages {
                context,
                items,
                continuation,
            })
        })
    }
}

/// Explicitly offline. Not a supported production source and not registered in UI.
#[derive(uniffi::Object)]
pub struct FixtureMangaSource {
    inner: ContractFixture,
}

#[uniffi::export]
impl FixtureMangaSource {
    #[uniffi::constructor]
    pub fn new(context: SourceContext, pagination: SourcePagination) -> Result<Self, SourceError> {
        if context.world != SourceWorld::Sfw
            || context.source_id != "fixture-m3"
            || context.process_session.is_empty()
        {
            return Err(SourceError::InvalidRequest);
        }
        Ok(Self {
            inner: ContractFixture {
                context,
                pagination,
            },
        })
    }
    pub fn capabilities(&self) -> SourceCapabilities {
        self.inner.capabilities()
    }
    pub async fn search(
        &self,
        context: SourceContext,
        query: String,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        self.inner.search(context, query, page).await
    }
    pub async fn recommendations(
        &self,
        context: SourceContext,
        page: SourcePageRequest,
    ) -> Result<SourceSearchPage, SourceError> {
        self.inner.recommendations(context, page).await
    }
    pub async fn detail(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
    ) -> Result<SourceDetail, SourceError> {
        self.inner.detail(context, key).await
    }
    pub async fn chapters(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        page: SourcePageRequest,
    ) -> Result<SourceChapterPage, SourceError> {
        self.inner.chapters(context, key, page).await
    }
    pub async fn pages(
        &self,
        context: SourceContext,
        key: SourceMangaKey,
        chapter_id: String,
        page: SourcePageRequest,
    ) -> Result<SourcePages, SourceError> {
        self.inner.pages(context, key, chapter_id, page).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn context() -> SourceContext {
        SourceContext {
            world: SourceWorld::Sfw,
            source_id: "fixture-m3".into(),
            process_session: "test".into(),
            generation: 7,
            auth_ref: Some("opaque-ref".into()),
        }
    }
    fn run(future: impl Future<Output = ()>) {
        tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap()
            .block_on(future)
    }
    #[test]
    fn both_pagination_modes_preserve_ids_and_explicit_end() {
        run(async {
            for mode in [SourcePagination::PageNumber, SourcePagination::Cursor] {
                let c = context();
                let source = FixtureMangaSource::new(c.clone(), mode).unwrap();
                let first = source
                    .search(c.clone(), "".into(), SourcePageRequest::Start)
                    .await
                    .unwrap();
                assert_eq!(first.items.len(), 2);
                assert_eq!(first.items[0].title, first.items[1].title);
                assert_ne!(first.items[0].key, first.items[1].key);
                let SourceContinuation::Next { request } = first.continuation else {
                    panic!("missing next")
                };
                let last = source.search(c.clone(), "".into(), request).await.unwrap();
                assert_eq!(last.items.len(), 1);
                assert_eq!(last.continuation, SourceContinuation::End);
                let empty = source
                    .search(c, "absent".into(), SourcePageRequest::Start)
                    .await
                    .unwrap();
                assert!(empty.items.is_empty());
                assert_eq!(empty.continuation, SourceContinuation::End);
            }
        });
    }
    #[test]
    fn unsupported_is_distinct_from_empty_and_invalid_paging() {
        run(async {
            let c = context();
            let source = FixtureMangaSource::new(c.clone(), SourcePagination::PageNumber).unwrap();
            assert!(!source.capabilities().recommendations);
            assert_eq!(
                source
                    .recommendations(c.clone(), SourcePageRequest::Start)
                    .await
                    .unwrap_err(),
                SourceError::Unsupported {
                    operation: SourceOperation::Recommendations
                }
            );
            for page in [
                SourcePageRequest::Page { number: 0 },
                SourcePageRequest::Page { number: u32::MAX },
                SourcePageRequest::Cursor {
                    token: "secret-input".into(),
                },
            ] {
                let error = source.search(c.clone(), "".into(), page).await.unwrap_err();
                assert_eq!(error, SourceError::InvalidRequest);
                assert!(!error.to_string().contains("secret-input"));
            }
        });
    }
    #[test]
    fn session_and_resource_ownership_are_explicit() {
        run(async {
            let c = context();
            let source = FixtureMangaSource::new(c.clone(), SourcePagination::Cursor).unwrap();
            let key = SourceMangaKey {
                world: SourceWorld::Sfw,
                source_id: "fixture-m3".into(),
                manga_id: "1".into(),
            };
            for other in [
                SourceContext {
                    world: SourceWorld::Nsfw,
                    ..c.clone()
                },
                SourceContext {
                    source_id: "other".into(),
                    ..c.clone()
                },
                SourceContext {
                    generation: 8,
                    ..c.clone()
                },
                SourceContext {
                    process_session: "new".into(),
                    ..c.clone()
                },
                SourceContext {
                    auth_ref: None,
                    ..c.clone()
                },
            ] {
                assert_eq!(
                    source.detail(other, key.clone()).await.unwrap_err(),
                    SourceError::SessionMismatch
                );
            }
            let detail = source.detail(c.clone(), key.clone()).await.unwrap();
            assert_eq!(detail.manga.key, key);
            let chapters = source
                .chapters(c.clone(), key.clone(), SourcePageRequest::Start)
                .await
                .unwrap();
            let pages = source
                .pages(
                    c.clone(),
                    key.clone(),
                    chapters.items[0].id.clone(),
                    SourcePageRequest::Start,
                )
                .await
                .unwrap();
            assert_eq!(pages.items[0].manga, key);
            assert_eq!(pages.items[0].context, c);
            assert_eq!(pages.items[0].ordinal, 1);
            assert!(pages.items[0].uri.starts_with("fixture://"));
            assert_eq!(
                source
                    .pages(
                        c.clone(),
                        key.clone(),
                        "missing".into(),
                        SourcePageRequest::Start
                    )
                    .await
                    .unwrap_err(),
                SourceError::NotFound
            );
            assert_eq!(
                source
                    .detail(
                        c,
                        SourceMangaKey {
                            source_id: "other".into(),
                            ..key
                        }
                    )
                    .await
                    .unwrap_err(),
                SourceError::SessionMismatch
            );
        });
    }
}
