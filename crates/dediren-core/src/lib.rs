pub mod io;
pub mod plugins;
pub mod quality;
pub mod validate;

pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}
