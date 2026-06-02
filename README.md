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
App traverses the Accessibility Tree and locates the chat-header avatar bounds
         │
         ▼
If Android 11+ and an enrolled avatar hash exists:
capture screenshot → crop avatar → compute 64-bit dHash → compare by Hamming distance
         │
    ┌────┴────┐
   MATCH      NO MATCH
    │          │
    ▼          ▼
performGlobalAction  Fall back to name matching over visible text
(GLOBAL_ACTION_BACK)
```

1. You add contact names to a blocked list inside the app.
2. Optionally, on Android 11+ you enroll that contact's WhatsApp profile photo by opening the chat once and letting the accessibility service capture only the avatar crop.
3. The service stores only a compact **64-bit dHash** (difference hash), not the raw image.
4. Every time a WhatsApp window changes, the service first tries the avatar match:
   - find the avatar bounds heuristically by position/size in the header
   - take a screenshot
   - downscale the crop to **9x8 grayscale**
   - compare neighboring pixels to produce a **64-bit dHash**
   - compare against enrolled hashes using **Hamming distance** with a default threshold of **10**
5. If no avatar hash is enrolled, the device is below API 30, or the avatar check does not match, the app falls back to the existing name-based detection.

This defeats the easy “rename the contact in the phone Contacts app” bypass: your phonebook name can change, but the WhatsApp profile picture shown in the chat header comes from WhatsApp and is not editable from the Contacts app.

---

## Why not just block the whole app?

Apps like AppBlock can already block WhatsApp entirely. This project solves a **different problem**: you may want to use WhatsApp normally — check group chats, call people, send messages — but stay away from one specific person or a handful of contacts.

Blocking the whole app is a sledgehammer. This is a scalpel.

---

## Technical architecture

### `BlockerAccessibilityService`

The core of the app. Extends `AccessibilityService` and listens for:

- `TYPE_WINDOW_STATE_CHANGED` — fires when you navigate to a new screen
- `TYPE_WINDOW_CONTENT_CHANGED` — fires when content updates within a screen

Only activates when the foreground app is `com.whatsapp` or `com.whatsapp.w4b` (WhatsApp Business). Its detection order is:

1. **Avatar-first (API 30+)** — if any avatar hashes are enrolled, the service calls `takeScreenshot()`, crops the header avatar, computes a dHash, and looks for a stored hash within Hamming distance `<= 10`.
2. **Name fallback** — if the avatar path is unavailable or does not match, it falls back to the existing text-tree traversal.

**Fallback tree traversal algorithm:**

```kotlin
private fun traverseTree(node: AccessibilityNodeInfo?, output: MutableList<String>) {
    node ?: return
    val text = node.text?.toString()
    if (!text.isNullOrBlank()) output.add(text)
    val desc = node.contentDescription?.toString()
    if (!desc.isNullOrBlank()) output.add(desc)
    for (i in 0 until node.childCount) {
        traverseTree(node.getChild(i), output)
    }
}
```

The avatar matcher never relies on WhatsApp view IDs; it uses only accessibility-exposed bounds plus a screenshot. The fallback name matcher still collects both `text` and `contentDescription` from every node and then checks:

```kotlin
val matched = blocked.firstOrNull { name ->
    allText.any { it.contains(name, ignoreCase = true) }
}
if (matched != null) performGlobalAction(GLOBAL_ACTION_BACK)
```

### `BlockedContactsRepository`

Persistence layer using `SharedPreferences`. It keeps the original blocked-name `Set<String>` for backward compatibility and adds per-contact avatar hashes plus a small enrollment state.

| Method | Description |
|---|---|
| `getBlockedContacts(context)` | Returns the full set of blocked names |
| `addContact(context, name)` | Adds a name (trimmed) to the set |
| `removeContact(context, name)` | Removes a name and clears any stored avatar hashes for it |
| `addAvatarHash(context, name, hash)` | Stores a 64-bit avatar hash for a blocked contact |
| `getAvatarHashes(context)` | Returns all stored avatar hashes grouped by contact |
| `getAllAvatarHashes(context)` | Returns the flattened set of stored avatar hashes |
| `requestAvatarEnrollment(context, name)` | Marks a blocked contact as the next avatar to enroll |

### `MainActivity`

The user-facing UI. Built with ViewBinding and Material Components. Responsibilities:

- Shows whether the `AccessibilityService` is currently **active** (green) or **inactive** (red)
- Deep-links to system Accessibility Settings
- Lets the user add contact names via an `EditText`
- Shows the full blocked list in a `RecyclerView` with per-item delete and **avatar enrollment** actions
- Refreshes state on every `onResume`

### `AvatarMatcher`

Encapsulates the screenshot-based signal:

- Finds the chat-header avatar bounds heuristically by **position, size, and image-like class names**
- Throttles `takeScreenshot()` calls to respect the platform rate limit (~333 ms)
- Crops the avatar rectangle, downsamples to **9x8**, computes a **64-bit dHash**
- Compares hashes using Hamming distance with a tunable default threshold of **10**
- Cleanly disables itself below API 30

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
│       │   ├── MainActivity.kt                   # UI entry point
│       │   ├── AvatarMatcher.kt                  # Screenshot + dHash avatar matching
│       │   ├── BlockerAccessibilityService.kt    # Core blocking logic
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
2. Type the contact's display name as it currently appears in WhatsApp.
3. Tap **Add** (or press Done on the keyboard).
4. The name appears in the list immediately.

### Enrolling a contact avatar (Android 11+)

1. In the blocked list, tap the **camera icon** next to the contact.
2. Open that direct chat in WhatsApp with the header avatar visible.
3. The accessibility service captures the screen, crops only the avatar area, computes a dHash, stores the hash locally, and exits the chat again.
4. The next time you open that chat, the service can identify it even if you renamed the contact in the phone Contacts app.

### Removing a contact from the block list

1. Find the contact in the list.
2. Tap the **🗑 delete icon** on the right.
3. The block and any enrolled avatar hashes are removed instantly — no confirmation needed.

### Temporarily disabling blocking

- Go to **Accessibility Settings** and toggle **WhatsApp Contact Blocker** off.
- Or simply remove the contact from the list inside the app.

---

## Why it's resilient to WhatsApp updates

Most apps that interact with WhatsApp's UI rely on **hardcoded view IDs** like:

```
com.whatsapp:id/conversation_contact_name
```

WhatsApp uses code obfuscation (ProGuard/R8) and ships updates frequently. After each update, those IDs can change to something like `com.whatsapp:id/a3f` — breaking the app instantly.

**This app never uses view IDs.** It simply:

1. Gets the root of the accessibility window.
2. Walks **every single node** in the tree recursively.
3. Collects every `text` and `contentDescription` string it finds.
4. Uses that text traversal only as the fallback signal after the avatar hash check.

As long as WhatsApp keeps exposing normal accessibility bounds and text, this approach avoids fragile view IDs and survives UI churn better than selector-based implementations.

**The only scenario where this could break** is if WhatsApp completely stops exposing text content to the Accessibility API — which would also break TalkBack and other screen readers, making it a serious accessibility regression that WhatsApp would be unlikely to ship.

---

## Known limitations

| Limitation | Details |
|---|---|
| **Avatar drift** | If the blocked person changes their WhatsApp profile photo, the stored hash may stop matching. Re-enroll the avatar; you can also store multiple hashes over time |
| **Hidden / generic avatars** | If the contact hides their profile photo or WhatsApp shows a generic avatar, that image is not a reliable identifier and can collide with other chats. The app still keeps the name fallback for this reason |
| **Screenshot API requirement** | Avatar matching needs Android 11 / API 30 or newer. Devices on API 26-29 use only the fallback name-based detection |
| **Screenshot throttling** | `takeScreenshot()` is rate-limited by Android, so avatar capture is throttled to roughly one request every 333 ms |
| **False positives from fallback text** | If a blocked name appears in a group name, in a quoted message, or anywhere else in the WhatsApp UI, the fallback name matcher can still trigger even if you didn't open their direct chat |
| **Brief flash** | The chat screen renders for a fraction of a second before the service fires back. You may see a flash of the conversation |
| **Heuristic avatar bounds** | The app does not use WhatsApp view IDs, so avatar cropping depends on header position/size heuristics. If WhatsApp radically redesigns the header, the avatar signal may need tuning |
| **Non-latin characters** | Names with accents or special characters work fine (comparison is unicode-aware), but emojis in names may cause unexpected behavior |
| **Battery optimization** | Aggressive battery optimizers on some ROMs (MIUI, OneUI) may kill the service. Mark the app as battery-unrestricted if this happens |
| **Accessibility tree depth** | On very complex screens, deep traversal has a negligible but non-zero CPU cost. In practice this is imperceptible |
| **iOS** | Not supported. Apple's sandbox does not allow cross-app UI inspection |

---

## FAQ

**Q: Does this app read my WhatsApp messages?**  
A: The `AccessibilityService` may read visible text for the fallback matcher, and on Android 11+ it may capture a screenshot briefly to crop the chat-header avatar and compute a compact perceptual hash. It does not store raw screenshots, log message content, or send anything off-device.

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
- ✅ Stores only compact avatar hashes, **not raw profile photos or screenshots**
- ✅ Does **not log or store** message content
- ⚠️ Requires the Accessibility permission, which Android flags with a system warning — this is expected and necessary for the app to function

---

## License

Personal use. No license. Fork it, break it, improve it.
