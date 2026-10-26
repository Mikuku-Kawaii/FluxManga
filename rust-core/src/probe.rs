//! M0-only local fixtures. No network, image decoding or library database lives here.
use std::fs::{File, OpenOptions};
use std::future::Future;
use std::io::{Read, Write};
use std::sync::atomic::{AtomicU64, Ordering::SeqCst};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::runtime::{Builder, Runtime};
use tokio::task::JoinHandle;

static SESSIONS: AtomicU64 = AtomicU64::new(0);
static THREADS: AtomicU64 = AtomicU64::new(0);
static JOBS: AtomicU64 = AtomicU64::new(0);
static COMPLETED: AtomicU64 = AtomicU64::new(0);
static FAILED: AtomicU64 = AtomicU64::new(0);
static CANCELLED: AtomicU64 = AtomicU64::new(0);
static TICKS: AtomicU64 = AtomicU64::new(0);
static FILES: AtomicU64 = AtomicU64::new(0);

#[derive(uniffi::Record)]
pub struct ProbeResources {
    pub sessions: u64,
    pub runtime_threads: u64,
    pub jobs: u64,
    pub completed: u64,
    pub failed: u64,
    pub cancelled: u64,
    pub ticks: u64,
    pub files: u64,
}

#[uniffi::export]
pub fn probe_resources() -> ProbeResources {
    ProbeResources {
        sessions: SESSIONS.load(SeqCst),
        runtime_threads: THREADS.load(SeqCst),
        jobs: JOBS.load(SeqCst),
        completed: COMPLETED.load(SeqCst),
        failed: FAILED.load(SeqCst),
        cancelled: CANCELLED.load(SeqCst),
        ticks: TICKS.load(SeqCst),
        files: FILES.load(SeqCst),
    }
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum ProbeError {
    #[error("fixture failure: {request_id}")]
    FixtureFailure { request_id: String },
    #[error("invalid probe input: {reason}")]
    InvalidInput { reason: String },
    #[error("probe I/O/runtime error: {reason}")]
    Io { reason: String },
}

fn io(error: impl std::fmt::Display) -> ProbeError {
    ProbeError::Io {
        reason: error.to_string(),
    }
}

#[derive(uniffi::Record)]
pub struct ProbeRequest {
    pub request_id: String,
    pub delay_ms: u32,
    pub fail: bool,
}

#[derive(uniffi::Record)]
pub struct ProbeReply {
    pub request_id: String,
    pub message: String,
    pub steps: u32,
}

#[derive(uniffi::Record)]
pub struct ImageProbeRequest {
    pub input_path: String,
    // A deterministic cancellation checkpoint for the local fixture test, 0 in benchmarks.
    pub chunk_delay_ms: u32,
}

#[derive(uniffi::Record)]
pub struct ProbeImageBytes {
    pub data: Vec<u8>,
    pub rust_micros: u64,
}

#[derive(uniffi::Record)]
pub struct ProbeImageFileInfo {
    pub path: String,
    pub encoded_bytes: u64,
    pub rust_micros: u64,
}

/// Owns only the newly created output. Never deletes the caller's source file.
#[derive(uniffi::Object)]
pub struct ProbeImageFile {
    path: String,
    encoded_bytes: u64,
    rust_micros: u64,
}

#[uniffi::export]
impl ProbeImageFile {
    pub fn info(&self) -> ProbeImageFileInfo {
        ProbeImageFileInfo {
            path: self.path.clone(),
            encoded_bytes: self.encoded_bytes,
            rust_micros: self.rust_micros,
        }
    }
}

impl Drop for ProbeImageFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
        FILES.fetch_sub(1, SeqCst);
    }
}

struct JobLifetime {
    finished: bool,
}

impl JobLifetime {
    fn new() -> Self {
        JOBS.fetch_add(1, SeqCst);
        Self { finished: false }
    }
    fn finish<T>(&mut self, result: &Result<T, ProbeError>) {
        self.finished = true;
        if result.is_ok() { &COMPLETED } else { &FAILED }.fetch_add(1, SeqCst);
    }
}

impl Drop for JobLifetime {
    fn drop(&mut self) {
        if !self.finished {
            CANCELLED.fetch_add(1, SeqCst);
        }
        JOBS.fetch_sub(1, SeqCst);
    }
}

// Dropping a plain JoinHandle detaches its work. UniFFI future cancellation must abort it.
struct AbortOnDrop<T>(JoinHandle<T>);
impl<T> Drop for AbortOnDrop<T> {
    fn drop(&mut self) {
        self.0.abort();
    }
}

#[derive(uniffi::Object)]
pub struct ProbeSession {
    runtime: Option<Runtime>,
}

#[uniffi::export]
impl ProbeSession {
    #[uniffi::constructor]
    pub fn new() -> Result<Self, ProbeError> {
        let runtime = Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("flux-m0-probe")
            .enable_time()
            .on_thread_start(|| {
                THREADS.fetch_add(1, SeqCst);
            })
            .on_thread_stop(|| {
                THREADS.fetch_sub(1, SeqCst);
            })
            .build()
            .map_err(io)?;
        SESSIONS.fetch_add(1, SeqCst);
        Ok(Self {
            runtime: Some(runtime),
        })
    }

    pub async fn run(&self, request: ProbeRequest) -> Result<ProbeReply, ProbeError> {
        self.execute(async move {
            if request.delay_ms > 30_000 {
                return Err(ProbeError::InvalidInput {
                    reason: "delay exceeds 30 seconds".into(),
                });
            }
            let mut elapsed = 0;
            let mut steps = 0;
            while elapsed < request.delay_ms {
                let slice = (request.delay_ms - elapsed).min(10);
                tokio::time::sleep(Duration::from_millis(slice.into())).await;
                elapsed += slice;
                steps += 1;
                TICKS.fetch_add(1, SeqCst);
            }
            if request.fail {
                return Err(ProbeError::FixtureFailure {
                    request_id: request.request_id,
                });
            }
            Ok(ProbeReply {
                request_id: request.request_id,
                message: "Rust async ready · 流光".into(),
                steps,
            })
        })
        .await
    }

    pub async fn image_bytes(
        &self,
        request: ImageProbeRequest,
    ) -> Result<ProbeImageBytes, ProbeError> {
        self.execute(async move {
            let start = Instant::now();
            let (mut input, size) = open_input(&request)?;
            let mut data = Vec::with_capacity(size as usize);
            copy_chunks(&mut input, &mut data, request.chunk_delay_ms).await?;
            Ok(ProbeImageBytes {
                data,
                rust_micros: start.elapsed().as_micros() as u64,
            })
        })
        .await
    }

    pub async fn image_file(
        &self,
        request: ImageProbeRequest,
        output_path: String,
    ) -> Result<Arc<ProbeImageFile>, ProbeError> {
        self.execute(async move {
            let start = Instant::now();
            let (mut input, _) = open_input(&request)?;
            let opened = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&output_path)
                .map_err(io)?;
            FILES.fetch_add(1, SeqCst);
            let mut artifact = ProbeImageFile {
                path: output_path,
                encoded_bytes: 0,
                rust_micros: 0,
            };
            let mut output = opened;
            // Drop the open writer before the artifact so cleanup also works on Windows.
            let copied = copy_chunks(&mut input, &mut output, request.chunk_delay_ms).await;
            drop(output);
            artifact.encoded_bytes = copied?;
            artifact.rust_micros = start.elapsed().as_micros() as u64;
            Ok(Arc::new(artifact))
        })
        .await
    }
}

impl ProbeSession {
    async fn execute<T: Send + 'static>(
        &self,
        work: impl Future<Output = Result<T, ProbeError>> + Send + 'static,
    ) -> Result<T, ProbeError> {
        let mut task = AbortOnDrop(self.runtime.as_ref().expect("live session").spawn(
            async move {
                let mut lifetime = JobLifetime::new();
                let result = work.await;
                lifetime.finish(&result);
                result
            },
        ));
        (&mut task.0).await.map_err(io)?
    }
}

impl Drop for ProbeSession {
    fn drop(&mut self) {
        // Do not block a Kotlin coroutine thread waiting for the runtime to shut down.
        // All probe work yields; tests also wait for THREADS/JOBS to return to zero.
        if let Some(runtime) = self.runtime.take() {
            runtime.shutdown_background();
        }
        SESSIONS.fetch_sub(1, SeqCst);
    }
}

fn open_input(request: &ImageProbeRequest) -> Result<(File, u64), ProbeError> {
    let input = File::open(&request.input_path).map_err(io)?;
    let metadata = input.metadata().map_err(io)?;
    if !metadata.is_file() || metadata.len() > 64 * 1024 * 1024 || request.chunk_delay_ms > 100 {
        return Err(ProbeError::InvalidInput {
            reason: "probe requires a regular file <=64 MiB and chunk delay <=100 ms".into(),
        });
    }
    Ok((input, metadata.len()))
}

async fn copy_chunks(
    input: &mut File,
    output: &mut impl Write,
    delay_ms: u32,
) -> Result<u64, ProbeError> {
    let mut chunk = [0u8; 64 * 1024];
    let mut copied = 0;
    loop {
        let count = input.read(&mut chunk).map_err(io)?;
        if count == 0 {
            break;
        }
        output.write_all(&chunk[..count]).map_err(io)?;
        copied += count as u64;
        TICKS.fetch_add(1, SeqCst);
        if delay_ms > 0 {
            tokio::time::sleep(Duration::from_millis(delay_ms.into())).await;
        } else {
            tokio::task::yield_now().await;
        }
    }
    output.flush().map_err(io)?;
    Ok(copied)
}
