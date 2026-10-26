//! Owns only a newly created transport output, never a caller's existing file.
use crate::source::SourceError;
use std::{
    fs::{File, OpenOptions},
    io::Write,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering::SeqCst},
    time::{SystemTime, UNIX_EPOCH},
};

pub(crate) const MAX_IMAGE_BYTES: usize = 32 * 1024 * 1024;
static NEXT: AtomicU64 = AtomicU64::new(0);

#[derive(uniffi::Object)]
pub struct SourceImageFile {
    path: PathBuf,
}

#[uniffi::export]
impl SourceImageFile {
    pub fn path(&self) -> String {
        self.path.to_string_lossy().into_owned()
    }
}
impl Drop for SourceImageFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

pub(crate) struct ImageSink {
    // Windows requires the open writer to close before the owner deletes the file.
    file: File,
    owner: SourceImageFile,
    bytes: usize,
}
impl ImageSink {
    pub fn create(directory: &str) -> Result<Self, SourceError> {
        let directory =
            std::fs::canonicalize(directory).map_err(|_| SourceError::InvalidRequest)?;
        if !directory.is_dir() {
            return Err(SourceError::InvalidRequest);
        }
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let path = directory.join(format!(
            "flux-image-{stamp}-{}.tmp",
            NEXT.fetch_add(1, SeqCst)
        ));
        let file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .map_err(|_| SourceError::Transport)?;
        Ok(Self {
            file,
            owner: SourceImageFile { path },
            bytes: 0,
        })
    }
    pub fn write(&mut self, chunk: &[u8]) -> Result<(), SourceError> {
        if chunk.len() > MAX_IMAGE_BYTES - self.bytes {
            return Err(SourceError::InvalidResponse);
        }
        self.file
            .write_all(chunk)
            .map_err(|_| SourceError::Transport)?;
        self.bytes += chunk.len();
        Ok(())
    }
    pub fn finish(mut self) -> Result<SourceImageFile, SourceError> {
        if self.bytes == 0 {
            return Err(SourceError::InvalidResponse);
        }
        self.file.flush().map_err(|_| SourceError::Transport)?;
        drop(self.file);
        Ok(self.owner)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ownership_limit_and_empty_output_cleanup() {
        let directory = std::env::temp_dir().join(format!(
            "flux-sink-test-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&directory).unwrap();
        let existing = directory.join("original.png");
        std::fs::write(&existing, b"original").unwrap();
        let mut sink = ImageSink::create(directory.to_str().unwrap()).unwrap();
        sink.write(b"image").unwrap();
        let owner = sink.finish().unwrap();
        assert_eq!(std::fs::read(owner.path()).unwrap(), b"image");
        let output = owner.path();
        drop(owner);
        assert!(!std::path::Path::new(&output).exists());
        let mut too_large = ImageSink::create(directory.to_str().unwrap()).unwrap();
        let partial = too_large.owner.path.clone();
        too_large.write(b"partial").unwrap();
        assert!(matches!(
            too_large.write(&vec![0; MAX_IMAGE_BYTES]),
            Err(SourceError::InvalidResponse)
        ));
        drop(too_large);
        assert!(!partial.exists());
        assert!(ImageSink::create(directory.to_str().unwrap())
            .unwrap()
            .finish()
            .is_err());
        assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 1);
        assert_eq!(std::fs::read(&existing).unwrap(), b"original");
        std::fs::remove_file(existing).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }
}
