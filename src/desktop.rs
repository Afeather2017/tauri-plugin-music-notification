use serde::de::DeserializeOwned;
use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::models::*;

pub struct MusicNotificationState;

impl Default for MusicNotificationState {
    fn default() -> Self {
        Self
    }
}

pub fn init<R: Runtime, C: DeserializeOwned>(
    app: &AppHandle<R>,
    _api: PluginApi<R, C>,
) -> crate::Result<MusicNotification<R>> {
    Ok(MusicNotification(
        app.clone(),
        MusicNotificationState::default(),
    ))
}

/// Access to the music-notification APIs.
pub struct MusicNotification<R: Runtime>(AppHandle<R>, MusicNotificationState);

impl<R: Runtime> MusicNotification<R> {
    pub fn ping(&self, payload: PingRequest) -> crate::Result<PingResponse> {
        Ok(PingResponse {
            value: payload.value,
        })
    }

    pub fn play(&self, _payload: PlayRequest) -> crate::Result<PlayResponse> {
        Ok(PlayResponse {
            success: false,
            message: Some("Desktop implementation not available".to_string()),
        })
    }

    pub fn pause(&self) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn pause_after(&self, _payload: PauseAfterRequest) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn resume(&self) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn stop(&self) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn next(&self) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn previous(&self) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn seek(&self, _position: i64) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn seek_and_play(&self, _position: i64) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn get_state(&self) -> crate::Result<PlaybackState> {
        Ok(PlaybackState {
            is_playing: false,
            position: 0,
            duration: 0,
        })
    }

    pub fn set_playing_queue(
        &self,
        _payload: SetPlayingQueueRequest,
    ) -> crate::Result<QueueMutationResponse> {
        Ok(QueueMutationResponse {
            success: false,
            message: Some("Android playback queue only available on mobile".to_string()),
        })
    }

    pub fn get_playback_session(&self) -> crate::Result<PlaybackSession> {
        Ok(PlaybackSession {
            queue: PlayingQueue {
                songs: Vec::new(),
                current_index: None,
            },
            runtime: PlaybackRuntime {
                is_playing: false,
                position_ms: 0,
                duration_ms: 0,
            },
            play_mode: PlayMode::Sequential,
            current_song_id: None,
        })
    }

    pub fn clear_playing_queue(&self) -> crate::Result<QueueMutationResponse> {
        Ok(QueueMutationResponse {
            success: false,
            message: Some("Android playback queue only available on mobile".to_string()),
        })
    }

    pub fn set_play_mode(
        &self,
        _payload: SetPlayModeRequest,
    ) -> crate::Result<QueueMutationResponse> {
        Ok(QueueMutationResponse {
            success: false,
            message: Some("Android playback queue only available on mobile".to_string()),
        })
    }

    pub fn start_service(&self) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn stop_service(&self) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: false })
    }

    pub fn set_volume(&self, _payload: SetVolumeRequest) -> crate::Result<SetVolumeResponse> {
        Ok(SetVolumeResponse {
            success: false,
            message: Some("Volume control only available on mobile".to_string()),
        })
    }

    pub fn set_normalization_config(
        &self,
        _payload: SetNormalizationConfigRequest,
    ) -> crate::Result<SetNormalizationConfigResponse> {
        Ok(SetNormalizationConfigResponse {
            success: false,
            message: Some("Normalization config only available on mobile".to_string()),
        })
    }

    pub fn set_server(&self, _library_name: String) -> crate::Result<SetServerResponse> {
        Ok(SetServerResponse {
            success: false,
            message: Some("Server registration only available on mobile".to_string()),
        })
    }

    pub fn set_headset_media_button_disabled(
        &self,
        _payload: SetHeadsetMediaButtonDisabledRequest,
    ) -> crate::Result<EmptyResponse> {
        Ok(EmptyResponse { success: true })
    }
}
