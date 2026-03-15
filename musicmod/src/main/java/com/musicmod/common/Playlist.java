package com.musicmod.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Playlist {

    private final String name;
    private final List<Song> songs = new ArrayList<>();
    private int currentIndex = 0;
    private boolean shuffle = false;
    private boolean loop    = false; // default: play through once and stop

    public Playlist(String name) { this.name = name; }

    public String getName()           { return name; }
    public List<Song> getSongs()      { return Collections.unmodifiableList(songs); }
    public boolean isShuffle()        { return shuffle; }
    public boolean isLoop()           { return loop; }
    public void setShuffle(boolean v) { this.shuffle = v; }
    public void setLoop(boolean v)    { this.loop    = v; }

    public void addSong(Song song)         { songs.add(song); }
    public boolean removeSong(String name) { return songs.removeIf(s -> s.getDisplayName().equalsIgnoreCase(name)); }
    public void reset()                    { currentIndex = 0; }
    public int size()                      { return songs.size(); }
    public int getCurrentIndex()           { return currentIndex; }

    public Song current() {
        return songs.isEmpty() ? null : songs.get(currentIndex);
    }

    /**
     * Advance to the next song.
     * Returns null when the playlist ends (loop=false) or if empty.
     * Loops back to start when loop=true.
     */
    public Song next() {
        if (songs.isEmpty()) return null;
        if (shuffle) {
            currentIndex = (int)(Math.random() * songs.size());
            return songs.get(currentIndex);
        }
        currentIndex++;
        if (currentIndex >= songs.size()) {
            if (loop) {
                currentIndex = 0;
                return songs.get(currentIndex);
            }
            currentIndex = songs.size() - 1; // leave at last position
            return null; // end of playlist — caller should stop
        }
        return songs.get(currentIndex);
    }

    /** Jump to a specific index and return that song. */
    public Song playFromIndex(int index) {
        if (songs.isEmpty() || index < 0 || index >= songs.size()) return null;
        currentIndex = index;
        return songs.get(currentIndex);
    }

    /** Find a song by its sourceUrl and set it as current. Returns null if not found. */
    public Song playByUrl(String sourceUrl) {
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).getSourceUrl().equals(sourceUrl)) {
                currentIndex = i;
                return songs.get(i);
            }
        }
        return null;
    }

    /** Move song from one index to another (for reordering). */
    public boolean moveSong(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= songs.size()) return false;
        if (toIndex < 0 || toIndex >= songs.size()) return false;
        if (fromIndex == toIndex) return true;
        Song song = songs.remove(fromIndex);
        songs.add(toIndex, song);
        // Adjust currentIndex so the same song stays "current"
        if (currentIndex == fromIndex) {
            currentIndex = toIndex;
        } else if (fromIndex < toIndex) {
            if (currentIndex > fromIndex && currentIndex <= toIndex) currentIndex--;
        } else {
            if (currentIndex >= toIndex && currentIndex < fromIndex) currentIndex++;
        }
        return true;
    }

    // ─── Song ────────────────────────────────────────────────────────────────────

    public static class Song {

        /** User-visible label (auto-derived from YouTube title if not supplied). */
        private String displayName;

        /**
         * Original URL the user pasted — YouTube, Spotify, SoundCloud, or direct audio link.
         * Stored persistently.
         */
        private final String sourceUrl;

        /**
         * Resolved direct audio URL (e.g. catbox.moe CDN URL from yt-dlp).
         * Cached in-memory only; re-resolved when expired or null.
         */
        private transient String resolvedUrl;
        private transient long   resolvedAt = 0; // epoch millis

        /** Duration in seconds; -1 if unknown. */
        private transient int durationSeconds = -1;

        public Song(String displayName, String sourceUrl) {
            this.displayName = displayName;
            this.sourceUrl   = sourceUrl;
        }

        public String getDisplayName()          { return displayName; }
        public void   setDisplayName(String n)  { this.displayName = n; }
        public String getSourceUrl()            { return sourceUrl; }
        public String getResolvedUrl()          { return resolvedUrl; }

        public int  getDurationSeconds()        { return durationSeconds; }
        public void setDurationSeconds(int s)   { this.durationSeconds = s; }

        /** Returns true if the cached resolved URL is still within TTL. */
        public boolean isResolved(int ttlSeconds) {
            if (resolvedUrl == null || resolvedUrl.isBlank()) return false;
            return (System.currentTimeMillis() - resolvedAt) < (ttlSeconds * 1000L);
        }

        public void setResolvedUrl(String url) {
            this.resolvedUrl = url;
            this.resolvedAt  = System.currentTimeMillis();
        }

        /** True when the source link requires yt-dlp to obtain a playback URL. */
        public boolean needsResolution() {
            return isYouTube(sourceUrl) || isSpotify(sourceUrl) || isSoundCloud(sourceUrl);
        }

        public static boolean isYouTube(String url) {
            return url != null && (url.contains("youtube.com") || url.contains("youtu.be"));
        }

        public static boolean isSpotify(String url) {
            return url != null && url.contains("open.spotify.com");
        }

        public static boolean isSoundCloud(String url) {
            return url != null && url.contains("soundcloud.com");
        }

        /** The URL clients actually stream from. */
        public String getPlaybackUrl() {
            return (resolvedUrl != null && !resolvedUrl.isBlank()) ? resolvedUrl : sourceUrl;
        }

        @Override
        public String toString() { return displayName + " [" + sourceUrl + "]"; }
    }
}
