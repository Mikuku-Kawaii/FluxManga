mod baozi_account;
uniffi::setup_scaffolding!();

mod copymanga;
mod detail_metadata;
mod image_file;
mod image_transform;
mod komga;
mod probe;
mod source;
mod transport;

/// Minimal DTO for verifying that the packaged Android library can be loaded.
#[derive(uniffi::Record)]
pub struct CoreInfo {
    pub version: String,
    pub architecture: String,
}

#[uniffi::export]
pub fn core_info() -> CoreInfo {
    CoreInfo {
        version: env!("CARGO_PKG_VERSION").to_owned(),
        architecture: std::env::consts::ARCH.to_owned(),
    }
}
