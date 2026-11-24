# RoadHouse Overlay Groq v1 (Groq clone)

Standalone, voice-first driver assistant under the RoadHouse brand.
Package `com.atothei.roadhouse.overlay.groq`, app name "RoadHouse Overlay Groq".
This is a clone of RoadHouse Overlay v1.4.1 — every feature is the same, but
the Q&A model is Groq's `openai/gpt-oss-120b` (free tier) instead of Gemini.
It installs alongside the Gemini build; the original project was not changed.

It sits on top of the delivery apps (Walmart Spark, DoorDash, Instacart) and
helps hands-free: it reads the delivery app's screen, checks the offer in
dollars per mile, answers questions out loud, navigates, and logs deliveries.

**It never auto-accepts or auto-declines orders.** It advises; the driver
decides. It never touches driver accounts or credentials.

## What v1 does

- **Screen reading** — accessibility service walks the foreground app's node
  tree (same proven shape as the AI Overlay APK shell: `getScreenContext`,
  `extractText`). Read-only: it never clicks or types in any app.
- **Floating mic button** — drawn over any app (`SYSTEM_ALERT_WINDOW`).
  Tap to start listening, long-press for an instant offer check, drag to move.
- **Voice in / voice out** — Android SpeechRecognizer + TextToSpeech.
  Say "check this offer", "navigate", "log this delivery", or ask anything
  about the screen ("how much is this paying?", "what's the address?").
- **Offer check in code** — pay and miles are parsed from the screen text
  and the $/mile math runs in code against an adjustable floor (default
  $1.00/mile). The model never decides the verdict: "$2.66 a mile — take it"
  / "$0.60 a mile — skip it".
- **Navigate** — pulls the delivery address off the screen and opens Google
  Maps navigation.
- **Delivery log** — "log this delivery" saves date, platform, pay, miles,
  address to a local JSON log (`delivery_log.json` in the app's files dir),
  in a RoadHouse-compatible shape for mileage/tax tracking. Export it from
  Settings.
- **Groq Q&A** — open questions go to Groq `openai/gpt-oss-120b` (free
  tier) with a driver-aware prompt and the current screen text.
- **Settings** — Groq API key (bring your own, stored in
  EncryptedSharedPreferences), $/mile floor, voice on/off, overlay button
  size, log export/clear, key test button.
- **First-run flow** — overlay permission → accessibility → microphone →
  Groq key → floor → start. Restarts itself after reboot once setup is
  done.

## What's new in v1.4 — five features

- **Offer memory** — every offer checked is saved locally (pay, miles,
  $/mile, store, date, verdict). After a check the card asks "Took it" /
  "Skipped it" — tap one, or say "took it" / "skipped it". Next time the
  same store's offer appears, the card and the voice say so: "You skipped
  this offer last time — $12.50 for 8.2 miles." Same store, different
  numbers still warns: "You've seen this store before."
- **Earnings tally** — say "how much today" / "how much this week" /
  "how much this month". The math runs in code on the delivery log:
  total pay, total miles, deliveries, average $/mile. Nothing logged for
  the range says so plainly. Also an Earnings button on the card.
- **Pre-shift check** — say "pre-shift check" or tap the card button. It
  walks through three questions by voice: vehicle OK, fuel level, which
  apps you're running — then fetches the weather for your city (free
  Open-Meteo, no key, fail-soft) and speaks a short summary. The apps
  answer is what YOU say; the overlay cannot detect another app's online
  status and never pretends to. The check is saved locally. Say "cancel"
  to stop mid-check.
- **Store notes** — say "remember: this Walmart's pickup is at the back"
  to save a note for the store on screen, or "what do I know about this
  store" to hear it back. Notes come back automatically on the next offer
  check for that store. "Forget that note" deletes them. Notes live in
  local JSON; manage/export them in Settings.
- **Batch math** — Spark batches with several drops: the offer check now
  shows the batch total (pay, miles, $/mile, verdict on the whole batch)
  plus a per-stop breakdown (each stop's miles where the screen shows
  them). Single-stop offers work exactly as before.

All five are on-demand only: no polling, no timers, no background reads,
no background Groq calls. Earnings and batch math are code math, never
Groq. Offer memory, notes, and pre-shift records are local JSON files
in the app's private files dir — nothing leaves the phone.

## Privacy

- Screen text goes to Groq's API **only**, and only as part of a
  question the driver asked. It is never stored and never sent anywhere else.
- No analytics, no backend, no account. The Groq key lives encrypted on
  the device. No key, token, or secret is in the code or the APK.

## Push from Termux

Create the repo first (once):

```
gh repo create jeramyadams37-dev/roadhouse-overlay-groq --public --description "RoadHouse Overlay Groq — voice-first driver assistant, Groq-powered"
```

Then push this project (run from the folder above `roadhouse-overlay-groq/`):

```
rm -rf roadhouse-overlay-groq-groq
unzip ~/storage/downloads/roadhouse-overlay-groq-v1.zip
cd roadhouse-overlay-groq-groq
git init
git add .
git commit -m "RoadHouse Overlay Groq v1"
git branch -M main
git remote add origin https://github.com/jeramyadams37-dev/roadhouse-overlay-groq.git
git push -u origin main
```

GitHub Actions (`.github/workflows/build-apk.yml`) builds the debug APK on
every push to `main`. No keystore is needed for v1 — it is a debug build.

## Download + install the APK

After the Actions run goes green:

```
gh run download -n RoadHouse-Overlay-Groq-debug-apk --repo jeramyadams37-dev/roadhouse-overlay-groq
```

That drops `app-debug.apk` in the current folder. Install it:

```
termux-open app-debug.apk
```

If nothing pops up, tap the APK in the Files app instead. (Recurring quirk:
`termux-open` sometimes does not surface the install prompt on the first try.)

## First-run setup on the phone

1. Open **RoadHouse Overlay Groq**.
2. Tap **1. Allow overlay permission** → allow "draw over other apps".
3. Tap **2. Turn on screen reading** → find **RoadHouse Overlay Groq** in the
   Accessibility list and turn it on. (This is how it reads the delivery
   app's screen. It only reads — it never taps or types.)
4. Tap **3. Allow microphone**.
5. Tap **4. Settings** → paste your Groq API key (free at console.groq.com), set
   your $/mile floor (default 1.00), tap **Save settings**. Tap **Test
   Groq key** — it should say the key works.
6. Tap **5. Start overlay**. The amber mic button appears. Switch to Spark,
   DoorDash, or Instacart and tap it.

## What to test first

1. Open Spark (or DoorDash / Instacart) to an offer screen.
2. Tap the mic button and say **"check this offer"** — it should speak the
   pay, miles, $/mile, and take-it-or-skip-it verdict, and show the same on
   the card. Long-press the button does the same check silently.
3. Say **"navigate"** — Google Maps should open with the delivery address.
4. Say **"log this delivery"** — Settings should show "Deliveries logged: 1".
5. Say **"what's the address?"** or any question about the screen — Groq
   answers out loud and on the card.
6. Drag the mic button — it should move and stay where you put it.
7. Offer memory: check an offer, tap **Took it** or **Skipped it**, then
   check the same offer again — the card should say you took/skipped it
   last time.
8. Say **"how much today"** — it should read back the logged deliveries,
   pay, miles, and average $/mile (log one first, or it says nothing is
   logged yet).
9. Say **"pre-shift check"** — answer the three questions by voice; it
   should finish with a summary including the weather.
10. Say **"remember: pickup is at the back"** on a store screen, then
    **"what do I know about this store"** — it should read the note back.
11. On a multi-stop Spark batch, **"check this offer"** should show the
    batch total plus a per-stop breakdown.

## Troubleshooting

- **No mic button:** overlay permission off, or the service was killed.
  Reopen the app and tap Start overlay. Lock the app in the recents screen
  so Android does not kill it for memory (same low-memory behaviour seen
  with StoreForge — reopen from the app icon, not recents).
- **"I can't see a pay amount or miles":** the offer screen was not the
  foreground screen, or the app shows the offer as an image. Open the offer
  details first, then check again.
- **Speech recognition unavailable:** the Google app must be installed and
  updated; SpeechRecognizer depends on it.
- **Groq errors:** check the key with the Test button in Settings. A 401
  "API key not valid" means a bad or mistyped key. Free-tier rate limits
  show as 429 — wait a minute and retry.
- **Card buttons don't respond:** the card is focusable only while shown.
  Hide it and tap the mic button again.

## Build details

- Native Android, Kotlin, AGP 8.10.1, Gradle 8.13, JDK 17.
- minSdk 26, targetSdk 36, compileSdk 36.
- Dependencies: androidx core-ktx, appcompat, material,
  security-crypto (EncryptedSharedPreferences). Groq is plain
  HttpURLConnection — no extra networking dependency.
- The GitHub Actions build is the compile check: this project was written
  and verified on a machine with no Android SDK, so the first green Actions
  run is the first real compile.

## Known v1 limits

- Voice is tap-to-talk, not always-on listening (no hotword engine).
- The $/mile parser reads text the accessibility service exposes; offers
  rendered as images with no text nodes cannot be parsed.
- The delivery log is local JSON; RoadHouse import is a later step.
- Foreground service type is `specialUse`; if the app ever goes to the Play
  Store, that needs a Play Console declaration.

## What's new in v1.4.1 — four more features

- **Auto-log on take** — tapping "Took it" (or saying "took it") after an
  offer check also logs the delivery to the delivery log automatically.
  Same delivery twice in a row is logged once, not twice; "log this
  delivery" for an already-logged offer says it is already logged.
  "Skipped it" never logs.
- **Speak new offers out loud** — OFF by default; turn it on in Settings.
  When on, a fresh offer on screen is spoken (pay and dollars per mile)
  from local parsing only — no model call, so it costs nothing. When off,
  nothing changes.
- **Address notes** — "remember: gate code 1234 for this address" saves a
  note tied to that delivery address; it comes back spoken and on the card
  the next time an offer shows the address. Explicit only: nothing is ever
  auto-captured from the screen. Questions ("what did I ask you to
  remember about this address") list, never save — same rules as store
  notes. Manage/export them in Settings alongside store notes.
- **Standalone weather** — say "what's the weather" any time, not just in
  the pre-shift check. It uses your current location (one-shot GPS, asked
  only when you ask), falling back to your saved city if location is off
  or unavailable. Allow location in Settings for the current-location
  version; temperature, conditions, wind, and rain chance are spoken and
  shown on the card.

## What's new in v1.6 (Groq clone only) — co-pilot round, 10 features + fixes

- **End-of-shift summary** — say "end of shift" or tap End shift on the card
  / End-of-shift summary in the app: deliveries, total pay, miles, $/mile,
  per-platform split, best and worst delivery. Code math on today's log.
- **Daily earnings goal** — say "set my goal to 150 dollars" or set it in
  Settings; "how am I doing on my goal" reports progress. One short nudge
  when the goal is reached, spoken once per day right after a delivery is
  logged — never nagging, never unprompted.
- **Dead-mile warning** — at offer-check time, when the last dropoff address
  differs from the next offer's address, the unpaid repositioning is flagged.
  Honest limit: no GPS trail and no pickup coordinates, so no dead-mile
  distance is ever invented. With the new OSRM lookup (below) the real
  unpaid drive is spoken when the addresses geocode.
- **Fuel math** — MPG + fuel price in Settings (defaults 25 MPG, $3.50/gal).
  "Fuel cost today" reports fuel cost, cost per mile, and pay after fuel.
- **Best hours from his own log** — "your best hours are…" groups logged
  deliveries by hour of day and day of week; an hour needs 2+ deliveries to
  lead, and under 3 deliveries total the answer says the sample is too small.
- **Voice reminders** — "remind me in 30 minutes to…" / "remind me at 5pm
  to…". On-device AlarmManager + ReminderReceiver (speaks + notification);
  re-armed after reboot by BootReceiver. On-device only, no server.
- **Delivery instructions read aloud on arrival** — when the delivery app
  moves to a screen saying the driver arrived, stored notes for that address
  are spoken (explicit-only notes, never auto-captured). Once per address
  per 30 minutes; tied to the foreground delivery-app screen, no polling.
- **Tax tally** — "tax tally" / "how much can I deduct": miles x IRS 2026
  split rates (72.5c Jan–Jun, 76c Jul–Dec 2026; 70c for 2025 and earlier).
  Estimate for his own records, not tax advice.
- **Accept-tap auto-logging** — OFF by default (Settings). When on, tapping
  an Accept button in a delivery app logs the fresh checked offer (same app,
  within ~3 minutes). No fresh offer: logs nothing, never guesses. Same
  10-minute dedupe as "took it".
- **Log-every-offer** — every offer the overlay sees is saved to
  `seen_offers.json`, outcome UNKNOWN. Auto-resolves two ways: the accept
  tap / "took it", or pay-history matching when a delivery is logged (same
  platform + pay within $0.50 + same store/address, exactly one match).
  Identical offers that can't be told apart are flagged for manual review,
  never auto-matched. Review by voice ("review my offers") or in the app
  (Review offers button) — each unresolved offer gets Took it / Skipped it.
- **Platform tags** — stored data now uses stable lowercase tags
  ("spark" / "doordash" / "instacart" / "unknown"); older rows that stored
  the display name are normalised on read. All summaries split per platform.
- **Stale-screen fix** — the cached screen read is now tagged with the
  package it came from; switching apps inside the 5-second reuse window
  forces a fresh read, so a card can never describe the wrong app. While a
  fresh read is in flight the card shows "Reading…", never the old answer.
- **Real route miles (OSRM, free, no key)** — after an offer check, the
  unpaid drive from the last dropoff to the new pickup is looked up via
  Nominatim geocoding + OSRM routing and spoken as real miles; a batch
  route check compares real driving distance through the addresses on
  screen with the printed miles and speaks up only when they differ
  meaningfully. Deadhead-home ("dead miles today") reports the real drive
  back to the saved home city the same way. Fail-soft, always: any geocode
  or route failure falls back silently to the printed miles and the check
  is never blocked. Geocode cache is in-memory only; nothing is polled.
- **Nearest gas station (Overpass, free, no key)** — "nearest gas station"
  uses one-shot GPS + the Overpass API and speaks the closest 1–2 stations
  with distance. If the lookup fails, it says so plainly instead of
  guessing. No signup key was added anywhere in this round.

## What's new in v1.7 (Groq clone only) — co-pilot round 2, 16 + 9 + 8 + camera features

- **Expense log by voice** — "log $12 gas", "log $8 car wash" into expenses.json
  (separate store, delivery_log.json shape untouched). "Expenses today" reports
  the total; the tax tally adds logged expenses separately from mileage.
- **Best app right now** — per-hour, per-platform average pay from his own log;
  needs 2+ deliveries per platform in that hour or it says the sample is too small.
- **Weekly pay pace** — "at this rate you'll make about $X this week"
  (this week's earnings ÷ elapsed days × 7; the method is stated every time).
- **Deadhead-home math** — last dropoff → home via OSRM route miles + return-leg
  fuel cost; on lookup failure the distance is unknown, never invented.
- **Break reminder** — one +3h AlarmManager alarm set at shift start, fires once
  per shift. No polling. Lost on a reboot mid-shift (alarms don't survive one).
- **Odometer prompt** — start-of-shift (first offer / pre-shift finish) and
  end-of-shift (with the summary), once each; a number is stored, a skip is
  skipped, never nagged. "Log odometer 123456" also works; "odometer" reports.
- **Customer message helper** — drafts "I'm here" / "running 5 minutes late" /
  custom, reads it out, shows it on the card. NEVER sends anything.
- **Parking spot memory** — "remember I parked in row C" / "where did I park",
  explicit-only, latest spot only, never auto-captured.
- **Tip tracker** — "log a $4 tip" into tips.json; earnings and tax tally report
  tips separately from base pay, always.
- **Shift auto-detect** — first offer of the day starts the shift; the summary is
  OFFERED once on the next interaction after 2+ quiet hours in the evening
  (5 PM+). No GPS home check — evening + quiet is a proxy, and the offer can
  fire on a slow evening even if he is still out. One spoken question; "no" or
  any other command ends it, never repeated that day.
- **Conversation** — "talk to me" / "keep me company" opens Groq chat; "stop
  talking" ends it. Only when HE starts it; an offer event pauses it and the
  offer wins.
- **Dead-mile entertainment** — on a long empty stretch the verdict offers a
  joke / story / trivia / music; only plays on a yes. One offer per stretch.
- **Jokes / trivia / stories** — via the same Groq chat path, on request.
- **Music by voice (real playback)** — "play some country" fires the standard
  voice-playback intent at the music app on the phone, then checks
  AudioManager.isMusicActive() 4s later: playing = audio actually started.
  If it only opened the search, the card says exactly that and never claims
  it is playing. Suggestions too: mood/time-based (upbeat morning, chill late
  night, high-energy on a dead stretch) — offers only, never autoplay.
- **Family date reminders** — "remind me Ilah's birthday is June 3", one 8 AM
  daily alarm; speaks only when a stored date matches today. Never invents a date.
- **Shift encouragement** — one brief note at shift start; the goal half reuses
  the existing once-per-day goal-reached nudge, not a duplicate.
- **Beat yesterday** — "you're $12 ahead of yesterday" / "new best day" from the
  log; first logged day says so plainly. Reports numbers, never shames a slow day.
- **Store likes and avoids** — "avoid this store" / "I like this store"; an
  avoided store gets a plain warning at offer-check time (voice + card).
- **Offer vs offer** — "which is better?" compares two offers on screen by
  $/mile (ties break on total pay) in code; fewer than two full offers says so.
- **Road notes** — "remember, this road floods" pins note + one-shot GPS at
  dictation time; read back once when his GPS is near the spot (checked on the
  next user interaction, no tracking, no polling). No fix at dictation = not stored.
- **Call and text hands-free** — "call Ella" opens the dialer (ACTION_DIAL, never
  ACTION_CALL); "read my messages" reads unread texts aloud. Permissions
  requested in Settings when first used; denied = plain spoken message.
- **Take me home** — navigates home (saved city) and offers the shift summary on
  arrival (foreground-app wording, same as arrival notes). Never ends the shift.
- **Phone battery warning** — sticky-battery read on the next user interaction;
  one spoken warning at 20% or under, once per shift. No polling, no background receiver.
- **Next batch prediction** — histogram of first-offer/log times per platform from
  his log; under 5 deliveries the sample is too small. A pattern, never a promise.
- **Voice journal** — end-of-shift note saved as transcribed text (journal.json,
  not audio); "read my journal" reads today, else the most recent day.
- **Voice navigation** — "navigate to the pickup" uses the screen address;
  "navigate to 123 Main St" uses the spoken address. Never starts without him saying so.
- **Voice take / skip** — already in v1.4 ("took it" / "skipped it"); confirmed
  still working in this clone, not duplicated.
- **Voice floor change** — "set my floor to $2 a mile", validated 0.01–10.00,
  $0 and nonsense rejected, saved to Prefs.
- **Voice app switching** — "open Spark / DoorDash / Instacart" + music app only;
  anything else is refused, never launched.
- **Voice place search** — "nearest bathroom", "cheapest gas near me", "food
  nearby" via Overpass + one-shot GPS, closest 1–2 with distance. "Cheapest gas"
  answers with the NEAREST gas and says prices aren't available — Overpass has
  no live fuel prices, so no price is ever invented.
- **Voice timer** — "set a 10 minute timer", one-shot AlarmManager, speaks once.
- **Read notifications aloud** — NotificationListenerService, default-off until
  notification access is granted (Settings button); reads only the text, never opens apps.
- **"Where am I"** — one-shot GPS + Nominatim reverse geocode; failure says so,
  never invents a place.
- **Hands-free camera (CameraX)** — "record" starts video right away, no taps,
  rear camera; "stop recording" stops and saves; "take a photo" snaps a still.
  Clips to Movies/RoadHouse, photos to DCIM/RoadHouse, timestamped filenames.
  A real red "● REC 00:00" badge + Stop button shows only while recording is
  actually running; stop works by voice AND by tapping it. The system-camera
  intent path was NOT used: ACTION_VIDEO_CAPTURE cannot auto-start recording
  without a tap. No 911 / emergency calling feature exists in this build, by
  explicit instruction — nothing was stubbed for it either.

### v1.7 honest limits (read before testing)
- No Android SDK on the build VM: the compile check is GitHub Actions, and
  there is no on-device testing in this round. Every feature below is
  structurally verified + logic-tested in a Python harness (see report), but
  none has run on a phone yet.
- VoiceManager.speak uses QUEUE_FLUSH, so multi-part speech is combined into
  single utterances; the battery warning and the shift-summary offer are
  delayed (5s / 8s) so they cannot cut off the answer playing.
- Shift auto-detect uses evening + 2 quiet hours as a proxy for "home" — no GPS.
- Break alarm is lost on a reboot mid-shift; the family-date 8 AM alarm is
  re-armed on boot.
- Camera needs the CameraX dependencies (added to app/build.gradle.kts);
  recording lives with the visible CameraActivity — it never records in the
  background.
