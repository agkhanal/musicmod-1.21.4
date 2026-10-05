# 🎵 ServerMusicPlayer — Minecraft Fabric Mod v2.0

Stream YouTube, Spotify, and direct audio URLs server-wide.
Paste any link into the in-game GUI (press **M**) and every player hears it.

---

## How it works

```
YouTube URL  ──┐
Spotify URL  ──┤──► Server runs yt-dlp ──► Direct CDN audio URL ──► All clients stream it
Direct MP3   ──┘
```

The server resolves links using **yt-dlp** (installed separately on the server).
Clients receive a raw audio CDN URL and stream it directly — no audio data passes
through the Minecraft server itself.

---

## Requirements

| Dependency | Version |
|---|---|
| Minecraft | 1.20.1 |
| Fabric Loader | ≥ 0.14.22 |
| Fabric API | ≥ 0.91.1+1.20.1 |
| Java | 17+ |
| **yt-dlp** | Latest (server only) |

### Install yt-dlp on your server

```bash
# Linux / macOS
pip install yt-dlp
# or
curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp
chmod +x /usr/local/bin/yt-dlp

# Windows
winget install yt-dlp
```

---

## Spotify support (optional)

For Spotify links you have two options:

**Option A — Spotify Web API (recommended):**
1. Go to https://developer.spotify.com/dashboard and create an app
2. Copy Client ID and Client Secret
3. In game: edit `config/musicmod.json` and fill in `spotifyClientId` / `spotifyClientSecret`
4. The mod will use Spotify API to get the track name, find it on YouTube, then resolve audio

**Option B — No credentials:**
yt-dlp has a built-in Spotify extractor that works in some regions without credentials.
Just paste Spotify links and it will try. May fail depending on your region.

---

## Installation

1. Install Fabric Loader + Fabric API for Minecraft 1.20.1
2. Install yt-dlp on the server (see above)
3. Drop `musicmod-2.0.0.jar` into `mods/` on **both server and client**
4. Start the server — `config/musicmod.json` is created with defaults

---

## In-game GUI (press M)

```
┌─────────────────────────────────────────────────────────┐
│  🎵 Music Manager                     ♪ Now Playing...  │
├────────────────────┬────────────────────────────────────┤
│  Playlists         │  Songs in selected playlist        │
│  ┌──────────────┐  │  ┌────────────────────────────┐   │
│  │ Chill (3)    │  │  │ ♪ LoFi Beats               │   │
│  │ Epic (5)  🔀 │  │  │ ⏳ Resolving...             │   │
│  └──────────────┘  │  └────────────────────────────┘   │
│  [▶ Play][+ New][✕]│  [Remove]                          │
├────────────────────┴────────────────────────────────────┤
│  URL: [ paste YouTube / Spotify / direct audio URL  ]   │
│       [ Playlist name (optional) ] [Add to Playlist]    │
│                                    [Library Only]        │
├─────────────────────────────────────────────────────────┤
│  [▶ Play]  [⏭ Skip]  [⏹ Stop]          ✔ Added: song   │
└─────────────────────────────────────────────────────────┘
```

- **Paste any link** into the URL box: YouTube, Spotify, or direct `.mp3`/`.wav`/`.ogg`
- **⏳** = still resolving (yt-dlp running in background)
- **♪** = ready to play
- **M key** opens/closes the screen (rebindable in Controls)

---

## Commands

All commands also accept YouTube/Spotify URLs directly where a song name is expected.

| Command | Perm | Description |
|---|---|---|
| `/music play <name\|url>` | OP | Play a song |
| `/music stop` | OP | Stop server-wide |
| `/music skip` | OP | Skip to next |
| `/music nowplaying` | All | Show current song |
| `/music addurl <url>` | OP | Add from any URL (auto-name) |
| `/music add <n> <url>` | OP | Add with custom name |
| `/music remove <n>` | OP | Remove from library |
| `/music list` | All | List library |
| `/music playlist create <n>` | OP | Create playlist |
| `/music playlist delete <n>` | OP | Delete playlist |
| `/music playlist add <pl> <song\|url>` | OP | Add song to playlist |
| `/music playlist play <n>` | OP | Play playlist |
| `/music playlist shuffle <n> <true\|false>` | OP | Toggle shuffle |
| `/music playlist list` | All | List playlists |
| `/music playlist info <n>` | All | Songs in playlist |
| `/music config ytdlp <path>` | Level 4 | Set yt-dlp binary path |

---

## Config (`config/musicmod.json`)

```json
{
  "ytDlpPath": "yt-dlp",
  "spotifyClientId": "",
  "spotifyClientSecret": "",
  "urlCacheTtlSeconds": 21000,
  "resolveTimeoutSeconds": 30,
  "ytDlpFormat": "bestaudio"
}
```

- `urlCacheTtlSeconds`: YouTube CDN URLs expire after ~6 hours. Default 21000 (5.8h) is safe.
- `ytDlpFormat`: `bestaudio` for best quality, `bestaudio[ext=webm]` to force WebM, etc.

---

## Architecture

```
SERVER SIDE                           CLIENT SIDE
───────────────────────               ───────────────────────
MusicCommands                         MusicScreen (GUI, press M)
PlaylistManager                       MusicPlayer
  └─ JSON persistence                   └─ MP3 via JLayer
LinkResolver                            └─ WAV/OGG via AudioSystem
  └─ yt-dlp subprocess              MusicHudRenderer
  └─ Spotify API                      └─ "Now Playing" overlay
MusicSessionController
  └─ Async URL resolution
  └─ Broadcasts to all clients

Packets: PLAY_SONG, STOP_MUSIC, NOW_PLAYING,
         SYNC_STATE, GUI_FEEDBACK,
         C2S_ADD_SONG, C2S_PLAYLIST_ACTION, C2S_REQUEST_SYNC
```

---

## Permissions

| Level | Access |
|---|---|
| All players | View now playing, list library/playlists, open GUI (view only) |
| OP (level 2) | Full control: add/remove songs, create/delete playlists, play/stop/skip |
| Level 4 | Config commands (yt-dlp path) |

---

## License

MIT
