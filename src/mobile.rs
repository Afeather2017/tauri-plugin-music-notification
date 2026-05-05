use serde::de::DeserializeOwned;
use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::models::*;

pub struct MusicNotificationState;

impl Default for MusicNotificationState {
    fn default() -> Self {
        Self
    }
}

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_music_notification);

// initializes the Kotlin or Swift plugin classes
pub fn init<R: Runtime, C: DeserializeOwned>(
    _app: &AppHandle<R>,
    api: PluginApi<R, C>,
) -> crate::Result<MusicNotification<R>> {
    #[cfg(target_os = "android")]
    let handle =
        api.register_android_plugin("com.plugin.music_notification", "MusicNotificationPlugin")?;
    #[cfg(target_os = "ios")]
    let handle = api.register_ios_plugin(init_plugin_music_notification)?;
    Ok(MusicNotification(handle, MusicNotificationState::default()))
}

/// Access to the music-notification APIs.
pub struct MusicNotification<R: Runtime>(PluginHandle<R>, MusicNotificationState);

impl<R: Runtime> MusicNotification<R> {
    pub fn ping(&self, payload: PingRequest) -> crate::Result<PingResponse> {
        self.0
            .run_mobile_plugin("ping", payload)
            .map_err(Into::into)
    }

    pub fn play(&self, payload: PlayRequest) -> crate::Result<PlayResponse> {
        self.0
            .run_mobile_plugin("play", payload)
            .map_err(Into::into)
    }

    pub fn pause(&self) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("pause", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn pause_after(&self, payload: PauseAfterRequest) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("pauseAfter", payload)
            .map_err(Into::into)
    }

    pub fn resume(&self) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("resume", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn stop(&self) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("stop", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn next(&self) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("next", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn previous(&self) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("previous", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn seek(&self, position: i64) -> crate::Result<EmptyResponse> {
        #[derive(serde::Serialize)]
        struct SeekRequest {
            position: i64,
        }
        self.0
            .run_mobile_plugin("seek", SeekRequest { position })
            .map_err(Into::into)
    }

    pub fn seek_and_play(&self, position: i64) -> crate::Result<EmptyResponse> {
        #[derive(serde::Serialize)]
        struct SeekAndPlayRequest {
            position: i64,
        }
        self.0
            .run_mobile_plugin("seekAndPlay", SeekAndPlayRequest { position })
            .map_err(Into::into)
    }

    pub fn get_state(&self) -> crate::Result<PlaybackState> {
        self.0
            .run_mobile_plugin("getState", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn set_playing_queue(
        &self,
        payload: SetPlayingQueueRequest,
    ) -> crate::Result<QueueMutationResponse> {
        self.0
            .run_mobile_plugin("setPlayingQueue", payload)
            .map_err(Into::into)
    }

    pub fn get_playback_session(&self) -> crate::Result<PlaybackSession> {
        self.0
            .run_mobile_plugin("getPlaybackSession", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn clear_playing_queue(&self) -> crate::Result<QueueMutationResponse> {
        self.0
            .run_mobile_plugin("clearPlayingQueue", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn set_play_mode(
        &self,
        payload: SetPlayModeRequest,
    ) -> crate::Result<QueueMutationResponse> {
        self.0
            .run_mobile_plugin("setPlayMode", payload)
            .map_err(Into::into)
    }

    pub fn start_service(&self) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("startService", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn stop_service(&self) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("stopService", EmptyRequest {})
            .map_err(Into::into)
    }

    pub fn set_volume(&self, payload: SetVolumeRequest) -> crate::Result<SetVolumeResponse> {
        self.0
            .run_mobile_plugin("setVolume", payload)
            .map_err(Into::into)
    }

    pub fn set_normalization_config(
        &self,
        payload: SetNormalizationConfigRequest,
    ) -> crate::Result<SetNormalizationConfigResponse> {
        self.0
            .run_mobile_plugin("setNormalizationConfig", payload)
            .map_err(Into::into)
    }

    pub fn set_server(&self, library_name: String) -> crate::Result<SetServerResponse> {
        self.0
            .run_mobile_plugin("setServer", SetServerRequest { library_name })
            .map_err(Into::into)
    }

    pub fn set_headset_media_button_disabled(
        &self,
        payload: SetHeadsetMediaButtonDisabledRequest,
    ) -> crate::Result<EmptyResponse> {
        self.0
            .run_mobile_plugin("setHeadsetMediaButtonDisabled", payload)
            .map_err(Into::into)
    }
}
