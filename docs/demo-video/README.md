# Demo video: script and commands (2:00–2:30)

About 300 words of narration, which fits 2:00–2:30 at a normal pace. Each scene lists what to show, the command to run (if any), and what to say.

---

## 1. Prepare (not recorded)

### 1.1 Start the backend

```bash
cd ~/Desktop/Mosip/mosip-decode
scripts/dev-up.sh
```

This starts Postgres, the mock identity system, the eSignet login UI (port 3000) and eSignet (port 8080). Check it:

```bash
curl -s localhost:8080/health        # prints: ok
```

### 1.2 Start the emulator with a visible window

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
ADB=$ANDROID_HOME/platform-tools/adb

$ANDROID_HOME/emulator/emulator -avd native_sso &
$ADB wait-for-device
for p in 8080 3000 8082; do $ADB reverse tcp:$p tcp:$p; done
```

### 1.3 Install the apps and reset them to a fresh state

```bash
cd ~/Desktop/Mosip/mosip-decode/native-sso-android
./gradlew -q :super-app:installDebug :child-app:installDebug

$ADB shell pm clear com.acme.superapp
$ADB shell pm clear com.acme.pharmacy
$ADB uninstall com.acme.pharmacy.rogue     # installed live in scene 7
```

### 1.4 Start the SSO Guardian dashboard (second terminal)

```bash
cd ~/Desktop/Mosip/mosip-decode/native-sso-lab
go run . serve
```

Open http://localhost:8099 in your browser and leave it there.

### 1.5 Create the demo user

Open **Acme Super App** on the emulator and tap **Create demo user**. Write down the UIN it shows. The OTP is always `111111`.

### 1.6 Recording tips

- Record with macOS screen recording (**Cmd+Shift+5**, "Record selected portion") around the emulator window. Switch to the browser for the last scene.
- Speed up the OTP typing 2× when editing.
- Keep a terminal visible for scenes 7 and 8.

---

## 2. Script

### Scene 1: The problem (0:00–0:15)

**Show:** the title slide, or the project README in the browser.

**Say:**
> "On a phone, every app from the same company makes you log in again with OTP and consent, even when they all trust the same eSignet identity. We built OpenID Native SSO into eSignet Go: log in once, and the company's other apps sign you in automatically and securely."

### Scene 2: The Super App logs in (0:15–0:45)

**Do:**
1. **Acme Super App** → tap **Sign in with eSignet**.
2. On the eSignet page: enter the UIN, tick the captcha, tap **Get OTP**, enter `111111`, tap **Verify**.
3. On the consent screen: turn on every item **except Email Address**, then tap **Allow**.
4. You're back in the app: **"Signed in as Asha Devi"**.

**Say:**
> "The first app logs in through the real eSignet screen. It uses PAR and DPoP with a key stored in the phone's secure hardware. Besides the usual tokens, eSignet returns a device secret: signed, then encrypted, and tied to that hardware key."

### Scene 3: Consent is respected (0:45–1:00)

**Do:**
1. Open **Acme Pharmacy**. Select **profile + email**.
2. Tap **Continue with your Acme account**.
3. The orange **"Your consent is needed"** card appears.

**Say:**
> "The Pharmacy app asks for email, but the user didn't share email with the Super App. So eSignet refuses to reuse that consent, and Pharmacy has to ask the user itself, which is the bonus re-consent flow."

### Scene 4: Pharmacy signs in silently (1:00–1:30)

**Do:**
1. Select **profile**.
2. Tap **Continue with your Acme account**.
3. Scroll through **"Why this sign-in was trusted"**.

**Say:**
> "With only what the user agreed to share, Pharmacy signs in silently. Android lets it talk to the Super App only because both are signed by the same company. The Super App signs a proof with its key, and the key never leaves it. eSignet issues Pharmacy its own token in about 20 milliseconds: no OTP, no consent screen."

> Do not tap **Sign out here** and sign in again: each app can use one Super App login only once (replay protection). For another try, log out of all Acme apps in the Super App and sign in again.

### Scene 5: Log out of every app (1:30–1:45)

**Do:**
1. Switch to **Super App** → **Log out of all Acme apps**.
2. Switch to **Pharmacy** → **Refresh profile** → red **"userinfo rejected (401)"**.

**Say:**
> "One tap logs the user out of every company app, including tokens already handed out. That goes beyond the spec."

### Scene 6: A fake app is blocked (1:45–1:55)

**Run (on camera):**

```bash
cd ~/Desktop/Mosip/mosip-decode/native-sso-android
./gradlew -q :child-app:installRogue
```

**Do:** open **Pharmacy (unofficial)** → **Continue** → **"Android refused to connect to the Super App"**.

**Say:**
> "A lookalike app signed by someone else can't even reach the login."

### Scene 7: Proof (1:55–2:20)

**Do (browser, http://localhost:8099):**
1. Click **Run all scenarios** → **20/20**.
2. Expand **A2** (stolen device secret rejected) and **R1** (only 1 of 100 simultaneous replays succeeds).
3. Scroll to the mutation table: **16/16**.

**Optional (+10 s), in a terminal:**

```bash
cd ~/Desktop/Mosip/mosip-decode/native-sso-lab
go run .                              # ends with: 20/20 scenarios passed
```

**Say:**
> "Every security rule is tested against the live server: stolen and tampered secrets, mixed sessions, other companies' apps, 100 simultaneous replays. When we switch off any single security check, a test fails, so every check is covered. Everything is open source and documented."

---

## 3. If something goes wrong

| Problem | Fix |
|---|---|
| Pharmacy says the device secret "cannot be redeemed" | Replay protection: Pharmacy already used this Super App login once (for example after **Sign out here** → **Continue**). In the Super App, tap **Log out of all Acme apps**, sign in again, then retry in Pharmacy. |
| The eSignet page doesn't load in the emulator | Rerun `for p in 8080 3000 8082; do $ADB reverse tcp:$p tcp:$p; done` and check `curl -s localhost:8080/health`. |
| The dashboard shows an error | Make sure eSignet is up (`curl -s localhost:8080/health`), then click **Run all scenarios** again. |
| You want to restart from a clean state | `$ADB shell pm clear com.acme.superapp && $ADB shell pm clear com.acme.pharmacy`, then create a new demo user. |
