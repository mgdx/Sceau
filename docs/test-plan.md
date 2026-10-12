# On-device test plan

Manual test plan from SPEC §9.2, run via adb on a real phone (`android-test` skill) before each release. It complements the `:core` unit tests (SPEC §9.1), which cover the cryptography on a dummy PKI; here we check real NFC reading, the user interface and the lifecycle.

## Preparation

### Equipment

| Item | Use |
|---|---|
| Android 8.0+ phone with NFC | test device (record the model and Android version) |
| French CNIe (credit-card format, 2021 or later) | nominal identity card case (PACE, CAN), then via the MRZ (TP-25) |
| Identity card from another EU country, issued in August 2021 or later (optional) | TP-25, step 6 |
| French biometric passport | nominal passport case (MRZ) |
| Expired document (passport or card) | TP-09 |
| Document from an issuer missing from the built-in store, for example a Slovak identity card (see decision D1); failing that, a passport from a country missing from the BSI Master List | TP-19 |
| Non-ICAO contactless card (bank card, transit card) | TP-20 |
| Valid national Master List, other than the BSI one already built in: Italy or Sweden (`.ml`) | TP-17, TP-19 |

No personal data from the test documents may appear in the report: record only “as expected” or the deviation observed.

### Useful adb commands

```bash
PKG=io.github.mgdx.sceau

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n $PKG/.MainActivity                   # launch the app

adb shell svc nfc disable                                   # turn NFC off
adb shell svc nfc enable                                    # turn NFC back on

adb shell settings put system accelerometer_rotation 0      # manual rotation
adb shell settings put system user_rotation 1               # landscape
adb shell settings put system user_rotation 0               # portrait
adb shell settings put system accelerometer_rotation 1      # restore auto-rotation

adb shell input keyevent KEYCODE_HOME                       # send to background
adb shell input keyevent KEYCODE_APP_SWITCH                 # recent apps screen
adb shell input keyevent KEYCODE_BACK                       # back
adb shell am kill $PKG                                      # kill the process (app in background)

adb exec-out screencap -p > capture.png                     # screenshot
adb push master-list.ml /sdcard/Download/                   # copy a Master List to import
adb logcat --pid=$(adb shell pidof $PKG)                    # app logs
adb shell dumpsys package $PKG | grep -A5 "permission"      # requested and granted permissions
```

Only one agent or tester uses the phone at a time.

### Report template

For each case: tick OK or KO, and for a KO record the deviation, the exact steps and, where relevant, the logs (after checking that they contain no personal data, which would in itself be a KO for TP-21).

---

## A. Installation and environment

### TP-01 Installation and permissions

- **Preconditions**: app not installed.
- **Steps**:
  1. `adb install -r app-debug.apk`, then launch the app.
  2. `adb shell dumpsys package io.github.mgdx.sceau | grep -A5 permission`.
- **Expected**: the app starts on the introduction (first launch, TP-30), with no runtime permission request. Only `android.permission.NFC` and the internal permission `io.github.mgdx.sceau.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (decision D7) appear; no `INTERNET` or `ACCESS_NETWORK_STATE` permission.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-02 NFC turned off, then back on

- **Preconditions**: app on the home screen.
- **Steps**:
  1. `adb shell svc nfc disable`.
  2. Return to the app; look at the home screen.
  3. Tap the banner button: the system NFC settings open.
  4. `adb shell svc nfc enable`, return to the app.
  5. Enter a valid CAN and read a CNIe.
- **Expected**: “NFC is turned off” banner with a button to the settings (steps 2 and 3); the banner disappears after NFC is turned back on, without restarting the app (step 4); the reading succeeds (step 5).
- **Result**: ☐ OK ☐ KO — Notes:

### TP-03 Device without NFC (if such a device or emulator is available)

- **Preconditions**: device or emulator without NFC hardware.
- **Steps**: install and launch the app.
- **Expected**: installation is possible (`android.hardware.nfc` not required), the home screen explains that NFC is missing and the “Read” button is disabled.
- **Result**: ☐ OK ☐ KO ☐ Not tested — Notes:

## B. Nominal readings

### TP-04 Nominal case: identity card (CNIe)

- **Preconditions**: NFC on, app on the home screen, “ID card” tab.
- **Steps**:
  1. Enter the 6-digit CAN printed on the front; check the numeric keyboard and the 6-digit validation.
  2. Tap “Read”, hold the card against the back of the phone.
  3. Watch the 5 steps, then the result screen.
- **Expected**: the steps are ticked in order; verdict **Authentic** (green); photo displayed and enlargeable; complete DG1 identity (flag and country name); checklist: channel “PACE”, SOD signature OK, chain to the ANTS e-ID CSCA (source ANTS), DS validity OK, hashes OK with the list of DGs, CA and/or AA OK. Additional data displayed only if DG11/DG12 are present.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-05 Nominal case: passport

- **Preconditions**: “Passport” tab (selected on opening, D29).
- **Steps**:
  1. Enter the document number (check that uppercase is forced), then the date of birth and the expiry date with the numeric keyboard (decision D10; the keyboard closes at the eighth digit of the expiry date, D29): the `/` appear as you type, in DD/MM/YYYY order in French (MM/DD/YYYY in US English). Try a nonexistent date (31/02), a future birth date and an expiry date before 1990: message under the field, “Read” disabled.
  2. Tap “Read”, place the passport open on the chip page (or the cover, depending on the model).
- **Expected**: complete reading; PACE channel if announced, otherwise BAC (or BAC after fallback, decision D13); verdict **Authentic** for a recent French passport (chain to an ANTS passport CSCA, link certificates where applicable); same content as TP-04.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-25 Identity card read via the MRZ

- **Preconditions**: NFC on, app on the home screen.
- **Steps**:
  1. “ID card” tab: the CAN / MRZ selector is on **CAN** and only the CAN field is shown (decision D31). Enter two digits of the CAN.
  2. Tap “MRZ”: the three fields document number, date of birth and expiry date appear; the helper text under the number says “As printed on the card, up to 9 characters”. Go back to “CAN”: the two digits entered are still there. Switch back to “MRZ”.
  3. Enter the document number, the date of birth and the expiry date read from the MRZ on the back of the CNIe (the keyboard closes at the eighth digit of the expiry date, as in TP-05).
  4. Tap “Read”, place the card.
  5. Return to the home screen from the result (back or “Clear”).
  6. If an identity card from another EU country is available: read it first with its CAN, then with its MRZ.
- **Expected**: step 4: complete reading, channel “PACE” (MRZ key), same result as TP-04 (verdict **Authentic**, chain to the ANTS e-ID CSCA). Step 5: the home screen is back to its initial state, as after any reading (TP-15): Passport tab, no field filled in, and the ID card tab back on **CAN**. Step 6: both keys open the chip (PACE channel, or BAC via the MRZ if the card does not announce PACE); verdict **Authentic**, or **Unknown issuer** if its CSCA is missing from the built-in store (TP-19). If the printed CAN does not have 6 digits, record it: only the MRZ can then be used.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-26 Italian CIE with an incorrect DG12 (known issuer issue)

- **Preconditions**: an Italian CIE 3.0 identity card issued between October 2017 and February 2018 (number `CA…`), listed if possible in the Italian Deviation List (decision D34, `docs/trust-sources.md` §2.4). Rare case: run it only if such a card is available; otherwise it is covered by `KnownDeviationsTest` and `KnownDeviationEndToEndTest` on the simulated chip.
- **Steps**: “ID card” tab, CAN (or MRZ), read the card, expand the “Data group hashes” row.
- **Expected**: the hashes row is ticked and shows “DG12: known issue published by the issuer, data discarded”; no DG12 data is displayed (authority, date of issue, observations); the “DS certificate validity” row uses the SOD signing date; verdict **Authentic** if Chip Authentication or Active Authentication succeeds. A more recent CIE (compliant DG12) shows no issue and displays its DG12 data.
- **Result**: ☐ OK ☐ KO — Notes:

## C. Reading errors

For all errors, the screen shows the full technical code in small type under the message (decision D11), for example `TIMEOUT-SECURE_CHANNEL-INS86-L10`: record it as is in the report; it contains no personal data.

### TP-06 Wrong CAN

- **Steps**: enter a wrong 6-digit CAN, read the CNIe.
- **Expected**: message “The CAN or MRZ data is incorrect” (code `ACCESS_DENIED` shown) and a “Try again” button; no data displayed. Return to the home screen, correct the CAN: the reading succeeds. Do not make more than two wrong attempts in a row: the chip may then impose a delay (TP-23), or even block the CAN.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-07 Wrong MRZ

- **Steps**: enter a wrong date of birth, read the passport.
- **Expected**: same behavior as TP-06.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-08 Document removed during reading

- **Steps**: start a reading and remove the document during “Reading data” (the DG2 photo is the longest read).
- **Expected**: message “The document was moved away too soon” (code `CONNECTION_LOST-READ_DATA-INS…-L…`) and “Try again”; place the document back, then “Try again”: the reading succeeds without re-entering the key. No crash, no UI freeze when leaving the screen.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-09 Expired document

- **Steps**: read a document whose expiry date has passed.
- **Expected**: “Document expired” banner; the authenticity verdict is not changed by the expiry (Authentic if the chip is verified).
- **Result**: ☐ OK ☐ KO — Notes:

### TP-20 Non-ICAO card

- **Steps**: enter any CAN, place a contactless bank or transit card.
- **Expected**: message “This document doesn’t comply with ICAO 9303” (code `NOT_ICAO`), no further attempt, “Try again” button.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-23 Passport after failed attempts: wait of up to 60 s

- **Preconditions**: passport (preferably French); accept that the chip will then impose a delay for a while.
- **Steps**:
  1. Do two readings with a wrong date of birth (TP-07): record the code shown.
  2. Correct the MRZ, tap “Read”, place the passport and keep it still.
  3. Time the “Opening secure channel” step.
- **Expected**: if the chip makes you wait, a waiting message (“The document’s chip is making you wait… up to a minute”) appears after 5 s in this step; the reading succeeds without intervention if the chip responds within 60 s (23.3 s observed on a French passport, decision D12). If it does not respond within 60 s: message “Timed out” with a code `TIMEOUT-SECURE_CHANNEL-INS…-L…`, with no automatic retry via BAC. Record the observed duration and the code.
- **Result**: ☐ OK ☐ KO ☐ Not tested — Notes:

## D. Lifecycle and wiping

### TP-10 Rotation during reading

- **Steps**: start a reading; during “Reading data”, `adb shell settings put system accelerometer_rotation 0` then `adb shell settings put system user_rotation 1`.
- **Expected**: the screen reorients, the reading continues without restarting from scratch or losing the state of the steps, and reaches the result.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-11 Rotation on the result screen

- **Steps**: on the result, switch to landscape then portrait (`user_rotation 1` then `0`).
- **Expected**: the result stays displayed unchanged (rotation is not a move to the background); no new reading.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-12 Sending to background during reading

- **Steps**: start a reading; during the reading, `adb shell input keyevent KEYCODE_HOME`; return to the app.
- **Expected**: the reading is interrupted; on return, the app is on the home screen or on an error with “Try again”, with no read data displayed. No crash.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-13 Wiping after sending the result to background

- **Steps**: on the result, `adb shell input keyevent KEYCODE_HOME`, wait 2 s, return to the app via the icon or recent apps.
- **Expected**: the app shows the home screen, empty: no verdict, no photo, no identity; the CAN/MRZ fields are empty. To read again, the key must be re-entered.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-14 Process death

- **Steps**: on the result, `adb shell input keyevent KEYCODE_HOME`, then `adb shell am kill io.github.mgdx.sceau`, then reopen the app from recent apps.
- **Expected**: the app restarts on the home screen, empty; no data restored, no crash.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-15 “Clear” button and back

- **Steps**:
  1. On the result, tap “Clear” (bottom of the screen): return to the home screen.
  2. Do another reading, then use “Clear” in the app bar.
  3. Do another reading, then `adb shell input keyevent KEYCODE_BACK`.
- **Expected**: in all three cases, immediate return to the home screen with empty fields; back from the home screen never returns to the result.
- **Result**: ☐ OK ☐ KO — Notes:

## E. Privacy

### TP-16 `FLAG_SECURE`: screenshot refused

- **Steps**:
  1. On the home screen (MRZ fields filled in), on the reading screen and then on the result, `adb exec-out screencap -p > capture.png` and open the image; also try the system screenshot (volume down + power keys).
  2. On the home screen and on the result, `adb shell input keyevent KEYCODE_APP_SWITCH`.
  3. Go back from the result to the home screen, then open the trust store and “About”, and take the screenshot again.
- **Expected**: black screenshots or screenshots refused by the system on every screen, including the home screen, the trust store and “About”, and including after returning from a reading or result screen: `FLAG_SECURE` is set on the whole activity from its creation (SPEC §2 and §8, audit V7). Preview hidden in recent apps on every screen.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-21 No data in the logs or on disk

- **Preconditions**: debug build.
- **Steps**:
  1. `adb logcat -c`, then a complete reading of a CNIe and of a passport, then `adb logcat -d > log.txt`.
  2. Search `log.txt` for the name, the document number, the CAN, the date of birth (local search, without sharing the file).
  3. `adb shell run-as io.github.mgdx.sceau ls -laR`: inventory of the app's files.
- **Expected**: no personal data or key in the logs; the app's only files are the system ones, `shared_prefs/sceau_preferences.xml`, which contains only the `intro_seen` flag (D35), and, where applicable, the Master Lists imported into `files/trust/`. No file in `cache/`.
- **Result**: ☐ OK ☐ KO — Notes:

## F. Trust store

### TP-17 Importing a valid Master List

- **Preconditions**: Italian or Swedish Master List copied to the phone (`adb push … /sdcard/Download/`).
- **Steps**:
  1. Menu → “Trust store”: the list is displayed within a few seconds (store preloaded at startup, decision D16); record the number of CSCAs per country (ANTS, built-in BSI Master List).
  2. “Import a Master List or a certificate”, choose the file in the system picker.
  3. Compare the displayed SHA-256 fingerprint of the signer (with its subject, the signing date and the number of certificates) with the one published by the issuing authority, then confirm.
  4. Import the same file again.
  5. Kill the app (`adb shell am kill io.github.mgdx.sceau` after `KEYCODE_HOME`), relaunch it, reopen the store.
- **Expected**: the signer's fingerprint is displayed before any import, and “Cancel” imports nothing; after confirmation, message “Master List imported: N certificates”, the list appears in the “Imported items” section (signer, signing date, number of certificates), the new CSCAs appear, marked “Imported”; the built-in CSCAs are unchanged (a certificate already present keeps its ANTS or Master List source). The second import creates no duplicate. The imported CSCAs are still there after a restart (one file in `files/trust/`).
- **Result**: ☐ OK ☐ KO — Notes:

### TP-18 Importing an invalid Master List

- **Steps**:
  1. Create a tampered copy: `cp it.ml bad.ml && printf '\x00' | dd of=bad.ml bs=1 seek=2000 conv=notrunc`, push it to the phone and try to import it.
  2. Try to import a file that is not a Master List (an image or a text file renamed to `.ml`).
  3. Try to import a file larger than 20 MB.
- **Expected**: steps 1 and 2: message “This file isn’t a valid Master List…” followed by a rejection code (`BAD_SIGNATURE`, `NOT_CMS`…, see `docs/trust-store.md`), no confirmation request; step 3: “File rejected: it is larger than 20 MB.” In every case, nothing is imported, the CSCA list is unchanged, no crash.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-19 Foreign document before and after import

- **Preconditions**: no Master List imported (“Delete imported certificates”); document from an issuer missing from the built-in store (see Equipment).
- **Steps**:
  1. Read the document.
  2. Import the Master List that contains its CSCA (TP-17), then read the document again.
  3. “Delete imported certificates”, then read it a third time.
- **Expected**: step 1: **Unknown issuer** (gray), data displayed, “Certificate chain” row not available; step 2: **Authentic** (or “Valid signature, chip not verified” if the document has neither DG14 nor DG15), chain to a CSCA with source “Imported”; step 3: **Unknown issuer** again.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-27 Importing a single CSCA certificate (D33)

- **Preconditions**: no item imported; the CSCA of the issuer of a document missing from the built-in store (see Equipment), downloaded from the issuing State's website, copied to the phone in DER (`.der`, `.cer` or `.crt`) and, if possible, in PEM (`.pem`); its SHA-256 fingerprint as published by the State, or computed on the workstation (`openssl x509 -in csca.pem -outform DER | sha256sum`).
- **Steps**:
  1. Read the document: **Unknown issuer**.
  2. Trust store → “Import a Master List or a certificate”, choose the DER file.
  3. Read the dialog, then “Cancel”. Start again and confirm.
  4. Import the same certificate again, in PEM this time.
  5. Read the document again.
  6. Kill the app, relaunch it, reopen the store.
- **Expected**: step 3: “Import this certificate?” dialog with the warning (no State's signature vouches for this file, the user is the one who decides to trust it, compare the fingerprint), the type (“Self-signed CSCA” or “Link certificate”), the subject, the country, the validity and the SHA-256 fingerprint, identical to the published one; “Cancel” imports nothing. After confirmation: message “Certificate imported.”, “Imported items” section with the subject, the country, the validity and the fingerprint; in the country's list, the certificate marked “Imported certificate”. Step 4: message “This certificate has already been imported.” without a dialog, no duplicate (same DER fingerprint, see TP-34). Step 5: **Authentic** (or “Valid signature, chip not verified”), chain details with the source “Imported certificate”. Step 6: the certificate is still there (a `<fingerprint>.der` file in `files/trust/`).
- **Result**: ☐ OK ☐ KO — Notes:

### TP-28 Rejected certificates (D33)

- **Steps**: try to import:
  1. a DS certificate or a website certificate (not a CA);
  2. a PEM file containing two certificates (for example a `fullchain.pem` chain);
  3. a CSCA with one byte of its signature altered (`cp csca.der bad.der && printf '\xff' | dd of=bad.der bs=1 seek=$(( $(stat -c %s csca.der) - 10 )) conv=notrunc`);
  4. any text file renamed to `.pem`.
- **Expected**: no confirmation dialog, nothing is imported, no crash. Step 1: “This certificate can’t be imported… (NOT_CA)”; step 3: same message with `BAD_SIGNATURE`; steps 2 and 4: “This file is neither a Master List nor a single DER or PEM certificate.”
- **Result**: ☐ OK ☐ KO — Notes:

### TP-29 Deleting items one by one (D33)

- **Preconditions**: a Master List (TP-17) and a certificate (TP-27) imported.
- **Steps**:
  1. In “Imported items”, tap the delete icon of the certificate, then “Cancel”.
  2. Start again and confirm.
  3. Read the document from TP-27 again.
  4. Delete the Master List the same way, then check the “Delete imported certificates” button.
- **Expected**: step 1: “Delete this imported item?” dialog, nothing is deleted. Step 2: “Imported item deleted.”, the certificate disappears from the section and from the country's list; the Master List remains. Step 3: **Unknown issuer** (unless the imported Master List also contains this CSCA). Step 4: the section disappears once empty, and the “Delete imported certificates” button is disabled.
- **Result**: ☐ OK ☐ KO — Notes:

## G. Language

### TP-22 English user interface

- **Steps**: switch the phone to English (Settings → Languages), go through the home screen, a reading with an error (TP-06), a result, the trust store and “About”.
- **Expected**: all text in English, no French left over, no truncation. In “About”, “Limitations” section: the entry on German identity cards (“German identity cards: only cards issued since 2 August 2021…”) is present, in English, after the one on revocation lists. With a phone language that Sceau does not translate (for example Swahili), the interface is also displayed in English, the default language (D55).
- **Result**: ☐ OK ☐ KO — Notes:

## H. Demo mode (debug APK)

### TP-24 Simulated CNIe

- **Preconditions**: **debug** APK installed (`app-debug.apk`), no document on the phone.
- **Steps**:
  1. Home screen → menu → “Simulate a French ID card (demo)”.
  2. Watch the reading screen, then the result.
  3. Tap “Clear”. Then start the demo again and send it to the background (`KEYCODE_HOME`) on the result, then return.
  4. Menu → “Trust store”.
  5. Install the **release** APK (`./gradlew :app:assembleRelease`, APK signed by the tester) and open the home screen menu.
- **Expected**: step 2: the 5 steps are ticked without a document; result with the “Simulated document — demo” banner, verdict **Authentic**, synthetic portrait (silhouette, no photo of a person), identity SPECIMEN / MARIANNE, PACE channel, chain to `CSCA-TEST-FRANCE`. Step 3: same wiping as a real reading (TP-13, TP-15). Step 4: no test certificate in the real store. Step 5: the “Simulate a French ID card (demo)” entry does not exist in the release APK (decision D17).
- **Result**: ☐ OK ☐ KO — Notes:

## I. Introduction and learning

To get back to a first launch: `adb shell pm clear $PKG` (also deletes the imported Master Lists).

### TP-30 First launch: introduction, “Skip introduction” and back

- **Preconditions**: app installed with no data (`pm clear`).
- **Steps**:
  1. Launch the app: the introduction is displayed (4 pages: the chip, the access key, what Sceau checks, privacy). Swipe from page to page in both directions, then use “Next” and “Previous” (absent from page 1); check the page indicator and, with TalkBack, the announcement “Page 2 of 4”.
  2. On each of pages 1 to 3, the “Skip introduction” button is visible; on page 4, it is replaced by “Get started”.
  3. Tap “Skip introduction” on page 2: the home screen is displayed. Back: the app closes (the introduction is not in the back stack). Relaunch: the home screen is displayed directly.
  4. `pm clear`, relaunch, go to page 3, press the system back button.
  5. Relaunch the app.
  6. `pm clear`, relaunch, go to page 4, tap “Get started”.
- **Expected**: step 4: the home screen is displayed (back acts like “Skip introduction”, never a blank screen or exit from the app). Step 5: the home screen, without the introduction. Step 6: the home screen, and the introduction no longer comes back on later launches. Dark mode and enlarged system font (Settings > Display > Font size at maximum): text readable, pages scrollable, buttons wrapped onto a new line rather than truncated.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-31 Introduction replayed from About

- **Steps**:
  1. Menu → “About” → “Understanding Sceau” section → “Replay the introduction”.
  2. Tap “Skip introduction”.
  3. Replay the introduction, go to page 4, tap “Get started”.
  4. Replay the introduction, press back.
- **Expected**: the introduction starts again from page 1; at steps 2, 3 and 4, return to “About” (not to the home screen), then back returns to the home screen.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-32 “What the chip contains” screen

- **Steps**:
  1. “About” → “What the chip contains”; scroll through the whole screen; back.
  2. Replay the introduction, page 4 → “What the chip contains”; back.
- **Expected**: four sections (Read and displayed: DG1, DG2, DG11, DG12; Read for verification only: EF.CardAccess, EF.COM, EF.SOD, DG14, DG15, EF.CardSecurity; Never read: DG3, DG4, other groups; What Sceau does not do). Step 1: return to “About”. Step 2: return to page 4 of the introduction, still displayed.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-33 Rotation and process death during the introduction

- **Preconditions**: `pm clear`, app launched on the introduction.
- **Steps**:
  1. Go to page 3, rotate the screen to landscape then to portrait.
  2. On page 3, `KEYCODE_HOME`, then `adb shell am kill $PKG`, then reopen the app from recent apps.
  3. Tap “Skip introduction”, rotate the screen on the home screen.
  4. `KEYCODE_HOME`, `am kill`, reopen from recent apps.
- **Expected**: step 1: page 3 stays displayed, content scrollable in landscape, buttons visible. Step 2: the introduction reappears (page 3 or page 1), without a crash. Steps 3 and 4: the home screen stays displayed, the introduction does not come back.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-34 Duplicates rejected on import (D33)

- **Preconditions**: no item imported; copied to the phone: `core/src/main/resources/trust/ants-csca-2025.der`, `ge-csca-6.der` and `de-bsi-master-list.ml` from the repository, a CSCA missing from the store in DER and in PEM (the one from TP-27), and a Master List different from the built-in list (for example a more recent version published by the BSI, or another national Master List).
- **Steps**:
  1. Trust store → “Import a Master List or a certificate”, choose `ants-csca-2025.der`.
  2. Choose `ge-csca-6.der`.
  3. Choose `de-bsi-master-list.ml`.
  4. Import the CSCA from TP-27 in DER and confirm; then choose it again, in DER and then in PEM.
  5. Choose the different Master List: read the dialog, confirm. Then choose it again.
  6. Choose a certificate contained in this Master List (if one can be downloaded on its own).
- **Expected**: no confirmation dialog at steps 1, 2 and 3, at the repetition of step 4, at the repetition of step 5 and at step 6, only a message. Step 1: “This certificate is already in the trust store (source: ANTS). There is nothing to import.” Step 2: same message, source “national publication”. Step 3: “This Master List is identical to the one built into the app. There is nothing to import.” Step 4: first import as normal, then “This certificate has already been imported.” in DER as in PEM. Step 5: the dialog shows “N new certificates out of M” under “Number of certificates” (N can be 0: the import is still possible); on repetition, “This Master List has already been imported.” Step 6: source “imported Master List” (or “built-in Master List” if the certificate is also in it). In “Imported items”, only the CSCA from step 4 and the Master List from step 5, each once; `files/trust/` contains only these two files.

### TP-35 German identity card issued before August 2021: chip reserved for authorities

- **Preconditions**: a German identity card (*Personalausweis*) issued before 2021-08-02, with its CAN (6 digits on the front). Rare case: run it only if such a card is available; otherwise it is covered by `GermanCardsEndToEndTest` on the simulated chip (decision D36, `docs/deviations.md` §3.1).
- **Steps**: “ID card” tab, enter the CAN, read the card.
- **Expected**: opening the secure channel succeeds, then the reading stops on the message “The chip refuses access to its data… German identity cards issued before August 2021… says nothing about the document’s authenticity”, with a code `ACCESS_RESTRICTED-SECURE_CHANNEL-SELECT_APPLET` or `ACCESS_RESTRICTED-READ_DATA-SOD` (record which one); **no “Try again” button**, only “Cancel”, which returns to the home screen. With a wrong CAN, on the other hand: “The CAN or MRZ data is incorrect” (`ACCESS_DENIED`) and “Try again”. If another code is shown (`UNEXPECTED-…`), record it as is: SW `6982` is only a hypothesis drawn from TR-03127.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-36 German eID card for EU citizens (eID-UB)

- **Preconditions**: a German eID card for EU citizens (*eID-Karte für Unionsbürger*, issued since 2020-11-01), with its CAN. Rare case: run it only if such a card is available; otherwise covered by `GermanCardsEndToEndTest` and by the “eID-UB” scene of `TextOverflowTest`.
- **Steps**: “ID card” tab, enter the CAN, read the card, scroll through the result and expand the checks.
- **Expected**: no reading error; verdict **Authentic** if Chip Authentication succeeds (no Active Authentication on these cards). Under the verdict, no photo and no name: the “Identity” section shows the banner “This card’s chip holds no identity data: only its authenticity can be checked…”, then only the document type (code “UB”) and the issuing state (Germany). No field that is empty, “Unknown” or made of “<”. “DS certificate validity” row: not available if neither the SOD nor DG1 carries a date (never failed for that reason alone). Record the verdict and the status of each row.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-37 Holder's signature (DG7)

- **Preconditions**: debug APK for the demo part; if possible, a real document whose chip carries DG7 (handwritten signature: some passports and identity cards, identifiable in the hashes list of the checks, which cites DG7 if it is read). Decision D37.
- **Steps**:
  1. Home screen menu → “Simulate a French ID card (demo)”, then scroll through the result, in light theme and then in dark theme.
  2. Expand the “Data group hashes” row.
  3. “Clear”, then start the demo again and send the app to the background on the result.
  4. With a real document that carries DG7: read it and scroll through the result. With a document without DG7 (a French CNIe, for example), check that the section is absent.
  5. About → “What the chip contains”.
- **Expected**: step 1: under “Identity”, a “Holder’s signature” card shows the synthetic initials (black strokes) on a white background, readable in both themes, without distortion; no “Unreadable image”. Verdict unchanged (**Authentic**). Step 2: DG7 is among the DGs checked. Step 3: return to the home screen and wiping as for the photo (TP-13, TP-15). Step 4: the real signature is displayed (JPEG or JPEG 2000); without DG7, no section and no empty slot. Step 5: DG7 appears under “Read and displayed”; the list of unread DGs cites “DG5, DG6, DG8 to DG10, DG13 and DG16”. Screenshot blocked on the result (`FLAG_SECURE`, TP-16).
- **Result**: ☐ OK ☐ KO — Notes:

## J. Release APK and real hardware

### TP-38 Real passport reading with the signed release APK

- **Preconditions**: universal release APK signed following the procedure in `docs/release.md` (`sceau-<version>-universal.apk`, R8 enabled, D24), installed in place of the debug APK (`adb uninstall io.github.mgdx.sceau`, then `adb install sceau-<version>-universal.apk`); French biometric passport.
- **Steps**:
  1. `apksigner verify --print-certs` on the installed APK: record the certificate fingerprint.
  2. Launch the app: introduction, then home screen; check that the menu does not offer “Simulate a French ID card (demo)”.
  3. Read the passport as in TP-05, then expand each row of the checklist.
  4. “Clear”, then About: record the version shown.
- **Expected**: fingerprint equal to `AllowedAPKSigningKeys` in the F-Droid recipe; no demo entry; complete reading, same result as in TP-05 (verdict **Authentic**, JPEG 2000 photo decoded, chain to an ANTS CSCA), no crash and no `UNEXPECTED-…` code caused by a class removed by R8; version “0.9” (or that of the release). Any difference from the debug APK is a blocking KO for the release.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-39 Real CNIe reading with the signed release APK

- **Preconditions**: those of TP-38; French CNIe.
- **Steps**: “ID card” tab, enter the CAN, read the card, scroll through the result and expand the checklist.
- **Expected**: same result as in TP-04: PACE channel, chain to the ANTS e-ID CSCA, verdict **Authentic**, photo displayed; no crash and no `UNEXPECTED-…` code.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-40 NFC turned off then back on from Quick Settings, without leaving the app

- **Preconditions**: NFC on; app in the foreground on the home screen. Quick Settings (swipe down from the top of the screen) does not pause the activity: this is the case that `adb shell svc nfc` and TP-02 (return from the settings) do not cover (commit `f6cd14b`).
- **Steps**:
  1. On the home screen, turn NFC off from Quick Settings, close the panel.
  2. Turn NFC back on from Quick Settings, close the panel; enter a valid CAN, tap “Read” and place a CNIe.
  3. On the reading screen (document not yet placed), turn NFC off from Quick Settings, close the panel.
  4. Turn NFC back on from Quick Settings, close the panel, place the CNIe.
- **Expected**: step 1: the “NFC is turned off” banner appears within about one second. Step 2: it disappears within about one second, and the card is detected and read through to the result, without leaving or relaunching the app. Step 3: the banner also appears on the reading screen. Step 4: it disappears, the card is detected and the reading succeeds. Never a stuck banner or an ignored card after NFC is turned back on.
- **Result**: ☐ OK ☐ KO — Notes:

### TP-41 PACE-CAM on CNIe: Chip Authentication done by PACE-CAM

- **Preconditions**: French CNIe (or any card that announces the PACE-CAM mapping in EF.CardAccess), decision D21.
- **Steps**: read the card with the CAN; on the result, expand the “Secure channel established” and “Chip Authentication (DG14)” rows (row label unchanged whatever the method).
- **Expected**: if the card announces PACE-CAM: “Chip Authentication” row OK, with “Method: PACE-CAM, while opening the channel, with the EF.CardSecurity key”; CA via DG14 is not redone; verdict **Authentic**. If the method shown is “Chip Authentication with the DG14 key”, the card does not announce PACE-CAM (or its mapping was not selected): record it; this is not a KO, but the absence of PACE-CAM on a CNIe that announces it is one. Any failed “Chip Authentication” row (code `VERIFY_CHIP-CAM-…` in the details) is a KO.
- **Result**: ☐ OK ☐ KO — Notes:

---

## Summary

| Case | Title | Result |
|---|---|---|
| TP-01 | Installation and permissions | |
| TP-02 | NFC turned off then back on | |
| TP-03 | Device without NFC | |
| TP-04 | Nominal identity card | |
| TP-05 | Nominal passport | |
| TP-06 | Wrong CAN | |
| TP-07 | Wrong MRZ | |
| TP-08 | Removal during reading | |
| TP-09 | Expired document | |
| TP-10 | Rotation during reading | |
| TP-11 | Rotation on the result | |
| TP-12 | Background during reading | |
| TP-13 | Wiping after background | |
| TP-14 | Process death | |
| TP-15 | “Clear” and back | |
| TP-16 | `FLAG_SECURE` | |
| TP-17 | Valid Master List import | |
| TP-18 | Invalid Master List import | |
| TP-19 | Foreign document before and after import | |
| TP-20 | Non-ICAO card | |
| TP-21 | Logs and disk | |
| TP-22 | English user interface | |
| TP-23 | Passport after failed attempts (wait of up to 60 s) | |
| TP-24 | Demo mode (debug) and absence in release | |
| TP-25 | Identity card read via the MRZ | |
| TP-26 | Italian CIE with an incorrect DG12 (known issue) | |
| TP-27 | Importing a single CSCA certificate | |
| TP-28 | Rejected certificates | |
| TP-29 | Deleting items one by one | |
| TP-30 | First launch: introduction, “Skip introduction”, back | |
| TP-31 | Introduction replayed from About | |
| TP-32 | “What the chip contains” screen | |
| TP-33 | Rotation and process death during the introduction | |
| TP-34 | Duplicates rejected on import | |
| TP-35 | German identity card issued before August 2021 (restricted access) | |
| TP-36 | German eID-UB card (no identity data) | |
| TP-37 | Holder's signature (DG7) | |
| TP-38 | Passport with the signed release APK | |
| TP-39 | CNIe with the signed release APK | |
| TP-40 | NFC turned off then back on from Quick Settings | |
| TP-41 | PACE-CAM on CNIe | |

Device: ………… Android: ………… Sceau version: ………… Date: ………… Tester: …………
