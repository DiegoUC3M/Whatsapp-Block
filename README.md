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
App traverses the full Accessibility Tree, collecting all visible text nodes
         │
         ▼
Checks if any text matches a name in the blocked list (case-insensitive)
         │
    ┌────┴────┐
   YES        NO
    │          │
    ▼          ▼
performGlobalAction  Do nothing
(GLOBAL_ACTION_BACK)
```

1. You add contact names to a blocked list inside the app.
2. The `AccessibilityService` runs silently in the background.
3. Every time a window changes inside WhatsApp (you open a chat, switch conversations, etc.), the service wakes up.
4. It reads **all text visible on screen** — no hardcoded IDs, no fragile selectors.
5. If any blocked name is found anywhere on screen, it fires `GLOBAL_ACTION_BACK` — same as pressing the hardware back button.

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

Only activates when the foreground app is `com.whatsapp` or `com.whatsapp.w4b` (WhatsApp Business).

**Tree traversal algorithm:**

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

Collects both `text` and `contentDescription` from every node. Then checks:

```kotlin
val matched = blocked.firstOrNull { name ->
    allText.any { it.contains(name, ignoreCase = true) }
}
if (matched != null) performGlobalAction(GLOBAL_ACTION_BACK)
```

### `BlockedContactsRepository`

Simple persistence layer using `SharedPreferences` with a `Set<String>`. Three methods:

| Method | Description |
|---|---|
| `getBlockedContacts(context)` | Returns the full set of blocked names |
| `addContact(context, name)` | Adds a name (trimmed) to the set |
| `removeContact(context, name)` | Removes a name from the set |

### `MainActivity`

The user-facing UI. Built with ViewBinding and Material Components. Responsibilities:

- Shows whether the `AccessibilityService` is currently **active** (green) or **inactive** (red)
- Deep-links to system Accessibility Settings
- Lets the user add contact names via an `EditText`
- Shows the full blocked list in a `RecyclerView` with per-item delete buttons
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
│       │   ├── MainActivity.kt                   # UI entry point
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
2. Type the contact's **exact display name** as it appears in WhatsApp (case-insensitive).
   - Example: if the chat header shows `"María García"`, type `María García`
3. Tap **Add** (or press Done on the keyboard).
4. The name appears in the list immediately.

### Removing a contact from the block list

1. Find the contact in the list.
2. Tap the **🗑 delete icon** on the right.
3. The block is removed instantly — no confirmation needed.

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
4. Checks if any of them contains a blocked name.

As long as WhatsApp renders the contact's name somewhere on screen (which it always does in the chat header), this approach works — regardless of how WhatsApp reorganizes its internals.

**The only scenario where this could break** is if WhatsApp completely stops exposing text content to the Accessibility API — which would also break TalkBack and other screen readers, making it a serious accessibility regression that WhatsApp would be unlikely to ship.

---

## Known limitations

| Limitation | Details |
|---|---|
| **False positives** | If a blocked name appears in a group name, in a quoted message, or anywhere else in the WhatsApp UI, the block will trigger even if you didn't open their direct chat |
| **Brief flash** | The chat screen renders for a fraction of a second before the service fires back. You may see a flash of the conversation |
| **Exact name matching** | The contact name must match what WhatsApp displays in the chat header. Nicknames set inside WhatsApp's contact settings are what matter, not your phone's contact book name |
| **Non-latin characters** | Names with accents or special characters work fine (comparison is unicode-aware), but emojis in names may cause unexpected behavior |
| **Battery optimization** | Aggressive battery optimizers on some ROMs (MIUI, OneUI) may kill the service. Mark the app as battery-unrestricted if this happens |
| **Accessibility tree depth** | On very complex screens, deep traversal has a negligible but non-zero CPU cost. In practice this is imperceptible |
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
