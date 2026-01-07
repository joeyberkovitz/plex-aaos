# Unofficial Plex client for Android Automotive

This app is a Plex music client for Android Automotive.

The application is available in the [Play Store](https://play.google.com/store/apps/details?id=us.berkovitz.plexaaos)

## Features

### Music Browsing
- **Home** - Quick access to Recently Played tracks and Recently Added albums
- **Playlists** - Browse and play your Plex playlists (large playlists grouped alphabetically)
- **All Music** - Browse all tracks in your library, grouped alphabetically (A-Z, #)
- **Artists** - Browse all artists, view their albums, and play tracks
- **Albums** - Browse all albums and play tracks

### Playback
- Background playback with media notification
- Shuffle and repeat modes (persisted between sessions)
- Resume playback from last position
- Track prefetching for seamless playback

### Offline Support
- Automatic caching of recently played tracks
- Works with intermittent connectivity

## Browse Hierarchy

```
Root
├── Home
│   ├── Recently Played → Tracks
│   └── Recently Added → Albums → Tracks
├── Playlists
│   └── [Playlist] → [A-Z groups] → Tracks
├── All Music
│   └── [A-Z] → Tracks
├── Artists
│   └── [Artist] → [Albums] → Tracks
└── Albums
    └── [Album] → Tracks
```

*Note: Android Auto displays up to 4 tabs. The order is: Home, Playlists, All Music, Artists (Albums is accessible but may require scrolling).*

## Screenshots

*Coming soon*

## Requirements

- Android Automotive OS (Android 10+)
- Plex Media Server with music library
- Plex account

## Changelog

### v0.8.0 (Unreleased)
- Added Home tab with Recently Played and Recently Added sections
- Added All Music tab - browse all tracks grouped alphabetically (A-Z, #)
- Large playlists now grouped alphabetically instead of by page numbers
- Added Artists browsing - view all artists, their albums, and tracks
- Added Albums browsing - view all albums and their tracks
- New icons for browse categories
- Playback support for album-based track selection

### v0.7.2
- Artist name now uses `originalTitle` if present

### v0.7.1
- Remember shuffle and repeat modes between sessions

### v0.7.0
- Added offline playback support
- Track prefetching for better streaming experience

## TODOs

- Multi-server support
- Multi-user support
- Relay fallback when direct connect fails
- Improved login logic
- ~~Support for music selection other than playlists~~ ✓ Implemented

## Building

### Prerequisites

1. Clone this repository
2. Clone the kotlin-plexapi fork with library browsing support:
   ```bash
   git clone -b feature/library-browsing https://github.com/hamoudydev/kotlin-plexapi.git
   cd kotlin-plexapi
   ./gradlew publishToMavenLocal
   ```
3. Create `local.properties` in the plex-aaos root with:
   ```properties
   sdk.dir=/path/to/Android/sdk
   gpr_user=your-github-username
   gpr_key=your-github-token  # needs read:packages scope
   # Optional for release builds:
   # signing_key_path=/path/to/keystore.jks
   # signing_key_password=your-password
   # signing_key_alias=your-alias
   ```

### Build

```bash
./gradlew assembleDebug
# or for release:
./gradlew assembleRelease
```

### Install on Emulator

```bash
./gradlew :automotive:installDebug
```

## Dependencies

- [kotlin-plexapi](https://github.com/hamoudydev/kotlin-plexapi/tree/feature/library-browsing) - Plex API client library (fork with library browsing support)
- ExoPlayer - Media playback
- Glide - Image loading

## License

Apache 2.0

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
