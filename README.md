# 🎧 VibePlayer

**A modern, feature-rich, and beautifully crafted local audio player for Android, built with Material 3.**

[Features](#-features) • [Screenshots](#-screenshots) • [Getting Started](#-getting-started) • [Tech Stack](#-tech-stack) • [Contributing](#-contributing) • [License](#-license)

## ✨ Features

* **Wide Audio Format Support:** Smooth playback for popular audio formats including `.mp3`, `.flac`, `.opus`, `.wav`, and more.

* **Modern Material 3 UI:** Designed following the latest Android design guidelines, offering dynamic theming, clean layouts, and a fluid user experience.

* **Complete Playback Controls:** Standard controls including play, pause, skip, timeline scrubber, volume, mute, and shuffle/repeat modes.

* **Queue Management:** Keep track of your listening history—previously played songs remain visible in the queue right above the currently playing track.

* **Real-Time Audio Visualizer & Faux AOD:** An immersive full-screen audio frequency visualizer that doubles as a screen-saver style Always-On Display (AOD) mode, automatically kicking in after a period of inactivity while the app is open.

* **Metadata Parsing & Editing:** Automatic extraction of track titles, artists, and album art with built-in in-app metadata editing and internet scraping capabilities.

* **Synced Lyrics Support (`.lrc`):** Full support for synchronized lyrics. Search, download, and embed lyrics directly from online sources like *LRCLIB*, complete with a mass-loading settings option to fetch lyrics for your entire library at once.

## 🚀 Getting Started

### Prerequisites

* Android SDK (Target SDK 34+)

* JDK 17 or higher

### Setup

1. **Clone the repository:**

   ```
   git clone https://github.com/bryanfury1/vibeplayer.git
   cd vibeplayer
   
   
   ```

2. **Open in Android Studio:**

   * Launch Android Studio and select **Open**.

   * Navigate to the cloned `vibeplayer` directory and select it.

3. **Build and Run:**

   * Sync your Gradle dependencies.

   * Connect an Android device with USB debugging enabled or launch an emulator (API 31+ recommended), then click **Run**.

### Installation

The app is in pre-release. You can download and install the app under “Releases“ on an Android.

## 🛠️ Tech Stack

* **Platform:** Android (Kotlin, Jetpack Compose)

* **Design:** Material 3 (Material You dynamic color support)

* **Audio Engine:** ExoPlayer / Media3

* **Lyrics Integration:** LRCLIB API

## 🤝 Contributing

Contributions, feature requests, and bug reports are welcome!

1. Fork the Project

2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)

3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)

4. Push to the Branch (`git push origin feature/AmazingFeature`)

5. Open a Pull Request

## 📄 License

Distributed under the [GNU General Public License v3.0](LICENSE). See `LICENSE` for more information.
