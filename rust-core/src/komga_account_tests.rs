use super::*;
use std::io::{Read, Write};
fn context() -> SourceContext {
    SourceContext {
        world: SourceWorld::Sfw,
        source_id: "komga-fixture".into(),
        process_session: "fixture".into(),
        generation: 1,
        auth_ref: Some("opaque".into()),
    }
}
fn config() -> SourceHttpConfig {
    SourceHttpConfig {
        timeout_ms: 2000,
        max_in_flight: 1,
        min_interval_ms: 0,
        max_retries: 0,
        max_body_bytes: 65536,
    }
}
fn run<T>(future: impl std::future::Future<Output = T>) -> T {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(future)
}
fn server(
    rows: Vec<(&'static str, u16, serde_json::Value, &'static str)>,
    basic: bool,
) -> (String, std::thread::JoinHandle<()>) {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let base = format!("http://{}/proxy/", listener.local_addr().unwrap());
    let thread = std::thread::spawn(move || {
        for (path, status, body, needle) in rows {
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
            assert!(header.lines().next().unwrap().contains(path), "{header}");
            if basic {
                assert!(lower.contains("authorization: basic dXNlcjpwYXNz".to_lowercase().as_str()));
                assert!(!lower.contains("x-api-key:"));
            } else {
                assert!(lower.contains("x-api-key: fixture-key"));
                assert!(!lower.contains("authorization:"));
            }
            assert!(!lower.contains("cookie:"));
            let length = lower
                .lines()
                .find_map(|line| {
                    line.strip_prefix("content-length: ")
                        .map(|s| s.parse::<usize>().unwrap())
                })
                .unwrap_or(0);
            let mut data = vec![0; length];
            socket.read_exact(&mut data).unwrap();
            assert!(String::from_utf8(data).unwrap().contains(needle));
            let body = body.to_string();
            write!(socket, "HTTP/1.1 {status} Test\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).unwrap();
        }
    });
    (base, thread)
}
fn page(content: serde_json::Value, number: u32, last: bool) -> serde_json::Value {
    json!({"content":content,"number":number,"size":20,"last":last})
}
#[test]
fn authorized_lists_and_book_mapping_keep_auth_prefix_order_and_pagination() {
    let (base, thread) = server(
        vec![
            (
                "GET /proxy/api/v2/users/me ",
                200,
                json!({"id":"userA"}),
                "",
            ),
            (
                "GET /proxy/api/v1/libraries ",
                200,
                json!([{"id":"lib1","name":"Shared library","root":"ignored"}]),
                "",
            ),
            (
                "GET /proxy/api/v1/collections?page=0&size=20",
                200,
                page(
                    json!([{"id":"c1","name":"Shared","ordered":true,"filtered":true}]),
                    0,
                    false,
                ),
                "",
            ),
            (
                "GET /proxy/api/v1/readlists?page=1&size=20",
                200,
                page(json!([]), 1, true),
                "",
            ),
            (
                "POST /proxy/api/v1/series/list?page=0&size=20",
                200,
                page(json!([{"id":"s1","metadata":{"title":"Series"}}]), 0, true),
                "\"libraryId\":{\"operator\":\"is\",\"value\":\"lib1\"}",
            ),
            (
                "GET /proxy/api/v1/collections/c1/series?page=0&size=20&deleted=false",
                200,
                page(json!([]), 0, false),
                "",
            ),
            (
                "GET /proxy/api/v1/readlists/r1/books?page=1&size=20&deleted=false",
                200,
                page(
                    json!([{"id":"b2","seriesId":"s1","seriesTitle":"Series"},{"id":"b3","seriesId":"s1","seriesTitle":"Series"}]),
                    1,
                    true,
                ),
                "",
            ),
        ],
        true,
    );
    let source =
        KomgaSource::with_basic(context(), base, "Basic dXNlcjpwYXNz".into(), config()).unwrap();
    run(async {
        assert_eq!(source.account_id(context()).await.unwrap(), "userA");
        let libs = source
            .authorized_lists(context(), KomgaListKind::Library, 0)
            .await
            .unwrap();
        assert_eq!(libs.items[0].name, "Shared library");
        assert_eq!(libs.next_page, None);
        let collections = source
            .authorized_lists(context(), KomgaListKind::Collection, 0)
            .await
            .unwrap();
        assert!(collections.items[0].filtered);
        assert!(collections.items[0].ordered);
        assert_eq!(collections.next_page, Some(1));
        assert!(source
            .authorized_lists(context(), KomgaListKind::ReadList, 1)
            .await
            .unwrap()
            .items
            .is_empty());
        assert_eq!(
            source
                .list_entries(context(), KomgaListKind::Library, "lib1".into(), 0)
                .await
                .unwrap()
                .items[0]
                .series_id,
            "s1"
        );
        assert_eq!(
            source
                .list_entries(context(), KomgaListKind::Collection, "c1".into(), 0)
                .await
                .unwrap()
                .next_page,
            Some(1)
        );
        let books = source
            .list_entries(context(), KomgaListKind::ReadList, "r1".into(), 1)
            .await
            .unwrap();
        assert_eq!(books.context, context());
        assert_eq!(books.items[0].position, 20);
        assert_eq!(books.items[1].position, 21);
        assert_eq!(books.items[0].book_id.as_deref(), Some("b2"));
        assert_eq!(books.items[1].series_id, "s1");
    });
    thread.join().unwrap();
}
#[test]
fn permissions_empty_auth_and_malformed_pages_are_distinct() {
    let (base, thread) = server(
        vec![
            ("/api/v2/users/me", 401, json!({}), ""),
            ("/api/v1/libraries", 403, json!({}), ""),
            ("/api/v1/libraries", 200, json!([]), ""),
            (
                "/api/v1/readlists/r/books",
                200,
                page(json!([{"id":"b","seriesId":"s"}]), 0, true),
                "",
            ),
            ("/api/v1/readlists", 200, page(json!([]), 1, true), ""),
        ],
        false,
    );
    let source = KomgaSource::new(context(), base, "fixture-key".into(), config()).unwrap();
    run(async {
        assert!(matches!(
            source.account_id(context()).await,
            Err(SourceError::AuthenticationRequired)
        ));
        assert!(matches!(
            source
                .authorized_lists(context(), KomgaListKind::Library, 0)
                .await,
            Err(SourceError::HttpStatus { status: 403 })
        ));
        assert!(source
            .authorized_lists(context(), KomgaListKind::Library, 0)
            .await
            .unwrap()
            .items
            .is_empty());
        assert!(matches!(
            source
                .list_entries(context(), KomgaListKind::ReadList, "r".into(), 0)
                .await,
            Err(SourceError::InvalidResponse)
        ));
        assert!(matches!(
            source
                .authorized_lists(context(), KomgaListKind::ReadList, 0)
                .await,
            Err(SourceError::InvalidResponse)
        ));
        let mut foreign = context();
        foreign.source_id = "other".into();
        assert!(matches!(
            source
                .authorized_lists(foreign, KomgaListKind::Library, 0)
                .await,
            Err(SourceError::SessionMismatch)
        ));
        assert!(matches!(
            source
                .list_entries(context(), KomgaListKind::Library, "../bad".into(), 0)
                .await,
            Err(SourceError::InvalidRequest)
        ));
        source.invalidate();
        assert!(matches!(
            source.account_id(context()).await,
            Err(SourceError::SessionMismatch)
        ));
    });
    thread.join().unwrap();
}
