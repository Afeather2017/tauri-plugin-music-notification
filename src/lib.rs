use tauri::{
    plugin::{Builder, TauriPlugin},
    Manager, Runtime,
};

pub use models::*;

#[cfg(desktop)]
mod desktop;
#[cfg(mobile)]
mod mobile;

mod commands;
mod error;
mod models;

pub use error::{Error, Result};

// Re-export Server trait and registration functions for example apps
pub use models::{set_server, Server};

#[cfg(desktop)]
use desktop::MusicNotification;
#[cfg(mobile)]
use mobile::MusicNotification;

/// Extensions to [`tauri::App`], [`tauri::AppHandle`] and [`tauri::Window`] to access the music-notification APIs.
pub trait MusicNotificationExt<R: Runtime> {
    fn music_notification(&self) -> &MusicNotification<R>;
}

impl<R: Runtime, T: Manager<R>> crate::MusicNotificationExt<R> for T {
    fn music_notification(&self) -> &MusicNotification<R> {
        self.state::<MusicNotification<R>>().inner()
    }
}

/// Initializes the plugin.
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("music-notification")
        .invoke_handler(tauri::generate_handler![
            commands::ping,
            commands::play,
            commands::pause,
            commands::pause_after,
            commands::resume,
            commands::stop,
            commands::next,
            commands::previous,
            commands::seek,
            commands::seek_and_play,
            commands::get_state,
            commands::set_playing_queue,
            commands::get_playback_session,
            commands::clear_playing_queue,
            commands::set_play_mode,
            commands::start_service,
            commands::stop_service,
            commands::set_volume,
            commands::set_normalization_config,
            commands::set_server,
            commands::set_headset_media_button_disabled
        ])
        .setup(|app, api| {
            #[cfg(mobile)]
            let music_notification = mobile::init(app, api)?;
            #[cfg(desktop)]
            let music_notification = desktop::init(app, api)?;
            #[cfg(target_os = "android")]
            {
                models::ensure_android_jni_symbols_linked();
                if let Some(library_name) = models::get_server_library_name() {
                    music_notification.set_server(library_name)?;
                }
            }
            app.manage(music_notification);
            Ok(())
        })
        .build()
}
