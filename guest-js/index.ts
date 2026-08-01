import { invoke } from '@tauri-apps/api/core'

export async function ping(value: string): Promise<string | null> {
  return await invoke<{value?: string}>('plugin:music-notification|ping', {
    payload: {
      value,
    },
  }).then((r) => (r.value ? r.value : null));
}

export interface PlayOptions {
  url: string;
  title?: string;
  artist?: string;
  album?: string;
  coverUrl?: string;
}

export interface PlaybackState {
  isPlaying: boolean;
  position: number;
  duration: number;
}

export type PlayMode = 'sequential' | 'shuffle' | 'loop'

export interface QueueSong {
  id: number;
  name: string;
  path: string;
  deviceId?: string | null;
  sourceKind?: 'kaulan' | 'local_raw' | 'temporary' | 'legacy';
  url?: string;
  lufs: number | null;
  coverUrl?: string | null;
}

export interface PlayingQueue {
  songs: QueueSong[];
  currentIndex: number | null;
}

export interface PlaybackRuntime {
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
}

export interface PlaybackSession {
  queue: PlayingQueue;
  runtime: PlaybackRuntime;
  playMode: PlayMode;
  currentSongId: number | null;
}

export async function play(options: PlayOptions): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>('plugin:music-notification|play', {
    payload: options,
  });
}

export async function pause(): Promise<{ success: boolean }> {
  return await invoke<{ success: boolean }>('plugin:music-notification|pause');
}

export async function pauseAfter(delayMs: number): Promise<{ success: boolean }> {
  return await invoke<{ success: boolean }>('plugin:music-notification|pause_after', {
    payload: {
      delayMs,
    },
  });
}

export async function resume(): Promise<{ success: boolean }> {
  return await invoke<{ success: boolean }>('plugin:music-notification|resume');
}

export async function stop(): Promise<{ success: boolean }> {
  return await invoke<{ success: boolean }>('plugin:music-notification|stop');
}

export async function next(): Promise<{ success: boolean }> {
  return await invoke<{ success: boolean }>('plugin:music-notification|next');
}

export async function previous(): Promise<{ success: boolean }> {
  return await invoke<{ success: boolean }>('plugin:music-notification|previous');
}

export async function seek(position: number): Promise<{ success: boolean }> {
  return await invoke<{ success: boolean }>('plugin:music-notification|seek', {
    position,
  });
}

export async function seekAndPlay(position: number): Promise<{ success: boolean }> {
  return await invoke<{ success: boolean }>('plugin:music-notification|seek_and_play', {
    position,
  });
}

export async function getState(): Promise<PlaybackState> {
  return await invoke<PlaybackState>('plugin:music-notification|get_state');
}

export async function setPlayingQueue(
  queue: PlayingQueue,
  playMode: PlayMode
): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>(
    'plugin:music-notification|set_playing_queue',
    {
      payload: {
        queue,
        playMode,
      },
    }
  );
}

export async function getPlaybackSession(): Promise<PlaybackSession> {
  return await invoke<PlaybackSession>('plugin:music-notification|get_playback_session');
}

export async function clearPlayingQueue(): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>(
    'plugin:music-notification|clear_playing_queue'
  );
}

export async function setPlayMode(playMode: PlayMode): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>(
    'plugin:music-notification|set_play_mode',
    {
      payload: {
        playMode,
      },
    }
  );
}

export async function startService(): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>('plugin:music-notification|start_service');
}

export async function stopService(): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>('plugin:music-notification|stop_service');
}

export interface SetVolumeOptions {
  volume: number;
}

export async function setVolume(options: SetVolumeOptions): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>('plugin:music-notification|set_volume', {
    payload: options,
  });
}

export type NormalizationMode = 'auto' | 'manual' | 'fixed'

export interface SetNormalizationConfigOptions {
  mode: NormalizationMode;
  manualVolume: number;
  fixedLufs: number;
  lufsPrecacheCount?: number;
}

export async function setNormalizationConfig(
  options: SetNormalizationConfigOptions
): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>(
    'plugin:music-notification|set_normalization_config',
    {
      payload: options,
    }
  );
}

export async function setServer(libraryName: string): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>(
    'plugin:music-notification|set_server',
    {
      libraryName,
    }
  );
}

export async function setHeadsetMediaButtonDisabled(disabled: boolean): Promise<{ success: boolean; message?: string }> {
  return await invoke<{ success: boolean; message?: string }>(
    'plugin:music-notification|set_headset_media_button_disabled',
    {
      payload: { disabled },
    }
  );
}
