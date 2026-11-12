# Spotify catalog setup

The Android app reads the Spotify app credentials from Gradle properties or environment variables.

## Local Android Studio build

Add these two lines to your user Gradle properties file (recommended: `~/.gradle/gradle.properties`):

```properties
spotifyClientId=YOUR_SPOTIFY_CLIENT_ID
spotifyClientSecret=YOUR_SPOTIFY_CLIENT_SECRET
```

Do not commit this file or paste the secret into source code.

## GitHub Actions

In the repository, open **Settings → Secrets and variables → Actions** and create these repository secrets:

- `SPOTIFY_CLIENT_ID`
- `SPOTIFY_CLIENT_SECRET`

The Android workflow passes those secrets into Gradle when it builds the APKs.

## Important security note

This uses Spotify Client Credentials because it does not require each listener to sign in. The Client Secret is compiled into the APK, so it can be extracted by anyone who obtains the app. This is the simpler approach requested, not a secure way to protect a shared secret. If abuse or Spotify rate limits become a problem, move the token exchange to a backend and rotate the secret.

Spotify images are displayed without app-side image transformations or persistent disk caching, and artist/album links return to Spotify. The artist grid keeps its local cover fallback when Spotify credentials are missing or no exact artist match is found.
