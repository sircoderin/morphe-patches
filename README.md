# 🧩 sircoderin Patches

Small UX fixes for apps I use, packaged as a Morphe patch bundle.

## ❓ About

### Kvaesitso

[Kvaesitso](https://github.com/MM2-0/Kvaesitso) can be opened as a search-only overlay (its "assistant" mode) on top of another launcher. These patches make that overlay feel smoother:

- **Instant search dismiss**: swipe-to-close finishes as soon as the swipe is released, instead of waiting for the swipe animation to fully settle. This removes the ~350 ms of frozen blur before the home screen comes back.
- **Search fade-in**: the overlay fades and gently scales in when it opens, mirroring the close animation, instead of popping in.

Tested on Kvaesitso 1.41.0 (official GitHub release). Later versions are marked experimental in Morphe Manager.

### Spotify

Fixes the Home feed going blank after Back, and makes Back return to where you were:

- **Restore Home scroll position**: after scrolling the Home feed down, opening an album and pressing Back, the feed no longer stays blank. You land at the same spot, with carousels scrolled where you left them. Home is shown from cache on Back instead of being reloaded a few seconds later, which used to throw you back to the top.
- **Home pull to refresh**: turns on Spotify's built-in pull-to-refresh on the Home feed, which Spotify normally keeps off with a server flag. Pull down at the top of Home to load a fresh feed.

Tested on Spotify 9.2.0.2053. Later versions are marked experimental in Morphe Manager.

Unofficial and not affiliated with the patched apps or the Morphe project.

### How to use these patches

Click the button below, or manually add this repo url to Morphe Manager: https://github.com/sircoderin/morphe-patches

<a href="https://morphe.software/add-source?github=sircoderin/morphe-patches">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->
> **[v1.1.0](https://github.com/sircoderin/morphe-patches/releases/tag/v1.1.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;3 patches total
<details open>
<summary>📦 Kvaesitso&nbsp;&nbsp;•&nbsp;&nbsp;2 patches</summary>
<br>

**Package name:** `de.mm20.launcher2.release`

**🎯 Supported versions:**

| 1.41.0 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Instant search dismiss](#instant-search-dismiss) | Closes the search overlay as soon as the swipe-to-close is released, instead of after the swipe animation fully settles (removes the frozen blur when closing). |  |
| [Search fade-in](#search-fade-in) | Fades and gently scales the search overlay in when it opens, mirroring the close animation. |  |

</details>

<details open>
<summary>📦 Twilight&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Package name:** `com.urbandroid.lux`

**🎯 Supported versions:**

| 14.32 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Untint screenshots](#untint-screenshots) | Removes Twilight's screen tint from screenshots taken with the system shortcut (power + volume). Needs "All files access" granted to Twilight. |  |

</details>

<!-- PATCHES_END -->

### 🛠️ Building locally

- Run `./gradlew buildAndroid`
- The built patches .mpp file is found in `patches/build/libs/patches-*.mpp`
- Patch the mpp file using [Morphe-Desktop](https://github.com/MorpheApp/morphe-desktop)
  like any other patch bundle.

See the [Morphe documentation](https://github.com/MorpheApp/morphe-documentation) for more information.

## 📜 License

sircoderin Patches are licensed under the [GNU General Public License v3.0](LICENSE)
