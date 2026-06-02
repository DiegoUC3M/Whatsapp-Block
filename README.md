# 📵 WhatsApp Contact Blocker

> Block specific WhatsApp contacts at the OS level — no root, no Play Store, no compromise.

An Android app that uses the **Accessibility API** to detect when you open a blocked contact's chat in WhatsApp and **immediately navigates back**, making that conversation effectively inaccessible.

Designed for **personal sideloaded use** — perfect if you want to enforce self-discipline, limit contact with certain people, or simply avoid distractions without deleting WhatsApp entirely.

---

## Table of Contents

1. [How it works](#how-it-works)
2. [Why not just block the whole app?](#why-not-just-block-the-whole-app)
3. [Technical architecture](#technical-architecture)
4. [Project structure](#project-structure)
5. [Requirements](#requirements)
6. [Build & install](#build--install)
7. [Enable the Accessibility Service](#enable-the-accessibility-service)
8. [Usage guide](#usage-guide)
9. [Why it's resilient to WhatsApp updates](#why-its-resilient-to-whatsapp-updates)
10. [Known limitations](#known-limitations)
11. [FAQ](#faq)
12. [Privacy](#privacy)

---

## How it works

```
User opens WhatsApp chat
         │
         ▼
AccessibilityService fires (typeWindowStateChanged / typeWindowContentChanged)
         │
         ▼
API 30+: capture screenshot + crop toolbar avatar + compute dHash64
         │
         ▼
Compare against stored avatar hashes (Hamming threshold = 10)
         │
    ┌────┴────┐
   YES        NO
    │          │
    ▼          ▼
Back action     Fallback to name matching
```

1. You add a contact to the blocked list and arm avatar enrollment for that contact.
2. The service stores the contact avatar as one or more 64-bit perceptual hashes.
3. During chat detection, the service matches avatar hash first (API 30+).
4. If avatar matching is unavailable or no hash matches, it falls back to name matching.
5. Matching chats trigger `GLOBAL_ACTION_BACK`.

---

## Why not just block the whole app?

Apps like AppBlock can already block WhatsApp entirely. This project solves a **different problem**: you may want to use WhatsApp normally — check group chats, call people, send messages — but stay away from one specific person or a handful of contacts.

Blocking the whole app is a sledgehammer. This is a scalpel.

---

## Technical architecture

### `BlockerAccessibilityService`

The core `AccessibilityService`, active for `com.whatsapp` and `com.whatsapp.w4b`.
For each chat event:

- Loads blocked contacts + stored avatar hashes
- API 30+: tries avatar matching first through `AvatarMatcher` (screenshot + crop + dHash + Hamming)
- Falls back to name matching in the toolbar header
- Applies per-contact schedule guard
- Triggers `GLOBAL_ACTION_BACK` with cooldown protection

### `AvatarMatcher`

Utility responsible for:

- Locating the avatar bounds in the chat header
- Taking accessibility screenshots (`takeScreenshot`, API 30+)
- Cropping and hashing with 64-bit dHash
- Comparing hashes with Hamming distance threshold (default 10)

### `BlockedContactsRepository`

`SharedPreferences` persistence layer. Stores:

- Blocked contact names
- Per-contact schedule data
- Per-contact avatar hash sets
- Pending avatar enrollment contact

### `MainActivity`

The user-facing UI. Built with ViewBinding and Material Components. Responsibilities:

- Shows whether the `AccessibilityService` is currently **active** (green) or **inactive** (red)
- Deep-links to system Accessibility Settings
- Lets the user add contact names via an `EditText`
- Arms avatar enrollment for a typed contact
- Shows the full blocked list in a `RecyclerView` with per-item delete buttons
- Shows each contact's stored avatar hash(es)
- Refreshes state on every `onResume`

### `ContactListAdapter`

Standard `RecyclerView.Adapter` backed by a sorted `MutableList<String>`. Exposes a `submitList()` method and calls a lambda `onDelete` when the delete button is tapped.

---

## Project structure

```
WhatsApp-Block/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/diegouc3m/whatsappblock/
│       │   ├── MainActivity.kt                   # UI entry point + avatar enrollment trigger
│       │   ├── BlockerAccessibilityService.kt    # Core blocking logic
│       │   ├── AvatarMatcher.kt                  # Screenshot avatar hash matching
│       │   ├── BlockedContactsRepository.kt      # SharedPreferences storage
│       │   └── ui/
│       │       └── ContactListAdapter.kt         # RecyclerView adapter
│       └── res/
│           ├── drawable/
│           │   └── bg_status.xml                 # Rounded background for status chip
│           ├── layout/
│           │   ├── activity_main.xml             # Main screen layout
│           │   └── item_contact.xml              # Single row layout
│           ├── values/
│           │   ├── strings.xml
│           │   └── themes.xml                    # WhatsApp green color scheme
│           └── xml/
│               └── accessibility_service_config.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml                        # Version catalog
└── README.md
```

---

## Requirements

| Requirement | Version / Details |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or later |
| Android device / emulator | API 26 (Android 8.0) or higher |
| Kotlin | 1.9.x |
| Gradle | 8.x |
| ADB | Any recent version |
| WhatsApp | Any version (Business included) |

> The app does **not** require root access.

---

## Build & install

### Option A — Android Studio (recommended)

1. Clone the repo:
   ```bash
   git clone https://github.com/DiegoUC3M/Whatsapp-Block.git
   ```
2. Open the project in **Android Studio**.
3. Wait for Gradle sync to finish.
4. Connect your Android device via USB (or start an emulator).
5. Click **Run ▶**.

### Option B — Command line with ADB

```bash
# 1. Clone
git clone https://github.com/DiegoUC3M/Whatsapp-Block.git
cd Whatsapp-Block

# 2. Build debug APK
./gradlew assembleDebug

# 3. Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Enable Developer Options on your device

1. Go to **Settings → About phone**.
2. Tap **Build number** 7 times.
3. Go back to **Settings → Developer options**.
4. Enable **USB debugging**.

---

## Enable the Accessibility Service

> This step is mandatory. Without it, the blocking does nothing.

1. Open the **WhatsApp Block** app.
2. You will see a **red status indicator** ("❌ Service Inactive").
3. Tap **"Open Accessibility Settings"**.
4. Scroll down to find **"WhatsApp Contact Blocker"** (under "Downloaded apps" or "Installed services").
5. Tap it and toggle it **ON**.
6. Confirm the system dialog (it warns about reading screen content — this is expected and required).
7. Press Back to return to the app.
8. The status indicator will turn **green** ("✅ Service Active").

> ⚠️ Some Android manufacturers (Xiaomi, Huawei, Samsung) kill background services aggressively. If the service stops working after a while, go to **Battery settings** and set WhatsApp Block to **"No restrictions"** or **"Don't optimize"**.

---

## Usage guide

### Adding a contact to the block list

1. Open the app.
2. Type the contact's **exact display name** as it appears in WhatsApp (case-insensitive).
3. Tap **Add** (or press Done on the keyboard).
4. The name appears in the list immediately.

### Enrolling avatar hash for stronger matching (API 30+)

1. Type the blocked contact name in the input.
2. Tap **Enroll avatar from current/open chat**.
3. The app arms enrollment and opens WhatsApp.
4. Open that contact chat. The service captures the header avatar, stores its dHash, and clears enrollment.
5. Return to the app and verify the stored hash appears under that contact in the list.

### Removing a contact from the block list

1. Find the contact in the list.
2. Tap the **🗑 delete icon** on the right.
3. The block is removed instantly — no confirmation needed.

### Temporarily disabling blocking

- Go to **Accessibility Settings** and toggle **WhatsApp Contact Blocker** off.
- Or simply remove the contact from the list inside the app.

---

## Why it's resilient to WhatsApp updates

Name matching remains robust as fallback, and avatar matching is based on perceptual hash rather than exact pixels:

- Works even if screenshots are compressed/rescaled (distance-based dHash match)
- Keeps fallback by contact name for API < 30 or when screenshot capture fails

For avatar detection, known IDs are tried first and a toolbar ImageView fallback is used for bounds detection.

---

## Known limitations

| Limitation | Details |
|---|---|
| **Avatar changes** | If the contact changes profile picture, the stored hash may stop matching until you re-enroll |
| **Hidden avatar / default avatar collisions** | If WhatsApp shows a generic avatar, different contacts can look identical to hash matching |
| **API level differences** | Avatar screenshot matching requires Android 11+ (API 30). On lower APIs, name fallback is used |
| **Hash enrollment needed** | Avatar-first logic only works after successful enrollment; the UI hash field lets you confirm this |
| **Brief flash** | The chat screen renders for a fraction of a second before the service fires back. You may see a flash of the conversation |
| **Name fallback limitations** | If no avatar hash matches and fallback is used, renamed contacts can still evade name-only checks |
| **Non-latin characters** | Names with accents or special characters work fine (comparison is unicode-aware), but emojis in names may cause unexpected behavior |
| **Battery optimization** | Aggressive battery optimizers on some ROMs (MIUI, OneUI) may kill the service. Mark the app as battery-unrestricted if this happens |
| **Screenshot rate limits** | `takeScreenshot` has system throttling; matcher enforces cooldown and can temporarily fall back to name matching |
| **iOS** | Not supported. Apple's sandbox does not allow cross-app UI inspection |

---

## FAQ

**Q: Does this app read my WhatsApp messages?**  
A: The `AccessibilityService` reads all text nodes visible on screen to find contact names. It does not store, log, transmit, or process message content in any way. Everything stays on-device.

**Q: Will this work with WhatsApp Business?**  
A: Yes. The service monitors both `com.whatsapp` and `com.whatsapp.w4b`.

**Q: What happens if WhatsApp updates overnight?**  
A: Because the app uses text-based detection instead of view IDs, it will almost certainly keep working without any changes needed.

**Q: Can I block group chats?**  
A: Yes — add the exact group name to the blocked list and it will behave the same way.

**Q: Will the blocked contact know they are blocked?**  
A: No. This app only affects your device and your ability to read their chat. The contact can still send you messages; you just won't be able to open them.

**Q: Can I add multiple contacts?**  
A: Yes, there is no limit on the number of blocked contacts.

**Q: Does it work on emulators?**  
A: Yes, as long as WhatsApp is installed and the Accessibility Service is enabled.

---

## Privacy

This app:
- ✅ Works **entirely offline**
- ✅ Stores data **only in local SharedPreferences**
- ✅ Has **no analytics, no tracking, no network requests**
- ✅ Does **not log or store** message content
- ⚠️ Requires the Accessibility permission, which Android flags with a system warning — this is expected and necessary for the app to function

---

## License

Personal use. No license. Fork it, break it, improve it.
