# WhatsApp Contact Blocker

An Android app that automatically blocks WhatsApp chats from specific contacts. When you open a blocked contact's chat, the app immediately navigates back — keeping you focused.

> ⚠️ This app is intended for **personal sideloaded use only**. It is not published on the Play Store.

---

## What it does

1. You add contact names to a blocked list inside the app.
2. The app runs an `AccessibilityService` in the background.
3. Whenever you open WhatsApp and navigate into a chat, the service reads all visible text on screen.
4. If a blocked contact's name is found, it immediately presses **Back** to close the chat.

---

## How to build & sideload

### Requirements
- Android Studio Hedgehog or later
- Android device with **Developer Options** enabled
- ADB installed

### Steps

```bash
# Clone the repo
git clone https://github.com/DiegoUC3M/Whatsapp-Block.git
cd Whatsapp-Block

# Build the APK
./gradlew assembleDebug

# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in **Android Studio** and click **Run**.

---

## How to enable the Accessibility Service

1. Open the **WhatsApp Block** app.
2. Tap **"Open Accessibility Settings"**.
3. Find **"WhatsApp Contact Blocker"** in the list.
4. Enable it and confirm the permission dialog.
5. Come back to the app — the status indicator will turn **green**.

---

## Why it's resilient to WhatsApp updates

Most similar apps rely on **hardcoded view IDs** (e.g. `com.whatsapp:id/conversation_contact_name`). When WhatsApp updates and changes its internal view IDs (which it does frequently due to code obfuscation), those apps break.

This app takes a different approach: it **traverses the entire accessibility tree** and reads every text node visible on screen — without caring about IDs or tree structure. As long as WhatsApp displays the contact's name somewhere on the chat screen (which it always does), the blocker will work.

---

## Known limitations

- **False positives**: If a blocked name appears elsewhere in the WhatsApp UI (e.g. in a group name, or a message quoting the contact), it may also trigger the block.
- **Slight delay**: The accessibility event fires after the screen renders, so there may be a brief flash of the chat before navigating back.
- **WhatsApp Business**: Supported (`com.whatsapp.w4b` is included).
- **iOS**: Not supported — Apple's sandbox prevents this kind of cross-app interaction.

---

## Project structure

```
app/src/main/java/com/diegouc3m/whatsappblock/
├── MainActivity.kt                  # UI: manage contacts + service status
├── BlockerAccessibilityService.kt   # Core: reads screen text, triggers back
├── BlockedContactsRepository.kt     # Storage: SharedPreferences
└── ui/
    └── ContactListAdapter.kt        # RecyclerView adapter
```
