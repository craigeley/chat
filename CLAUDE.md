# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Chat is a from-scratch **iMessage client** for the **Light Phone III** — a
single-module Android app (Kotlin + Jetpack Compose, `app/`). It talks to a
self-hosted **BlueBubbles Server** (running on an always-on Mac signed into
iMessage) over its REST API, styled after the phone's native messaging app.
Sibling of `reel`, `hive`, `jot`, `pod`, and `ask`. There are no tests.

It exists to replace **OpenBubbles**, whose Flutter app is heavy on battery and
buggy about message ordering. A purpose-built thin client over a single socket
on the tailnet is far lighter, and gets ordering right because it owns the sort.

**Phased build:**
- **Phase 1 (done):** read-only over REST — conversation list + thread view,
  pull-to-refresh, correct ordering, contact-name mapping.
- **Phase 2 (done):** send (`POST /api/v1/message/text`, optimistic + server
  echo) + a Socket.IO foreground service for live `new-message`/`updated-message`
  delivery and notifications. This is the working OpenBubbles replacement.
- **Phase 3 (in progress):** image attachments — receive **and** send **(done)**,
  tapbacks/reactions rendered compactly, read receipts, contact-name resolution
  inside notifications **(done)**, persisting the contact index across launches
  **(done)**. The contact index is now persisted to `Store` (normalized key→name map
  as JSON) so `SocketService` — which runs with no activity/ViewModel alive (e.g.
  started at boot) — can resolve a sender's address to a name for notifications.
  Inbound images render inline in the thread (download → cache → EXIF-orient →
  downsample, dependency-free; see `Attachments`). Outbound images: the thread
  compose bar's "+" opens the system photo picker and sends via multipart
  `POST /message/attachment` (optimistic, echo-reconciled like text). Both work over
  plain REST. **Tapbacks/reactions (done):** incoming reactions render compactly
  (folded onto their target message, not shown as their own row); long-press a
  message to send your own via `POST /message/react`, re-tap the same one to remove.
  *Sending* is gated on the server's Private API being live — the app detects this
  from `server/info` (`private_api && helper_connected`) and only offers the picker
  then; *rendering* incoming reactions works regardless (they arrive as normal
  messages). See "The server" for enabling the Private API (SIP + Library Validation
  off on the Mac). **Marking a thread read** is also wired (when the Private API is
  live): opening a thread — and a live incoming message while it's foreground —
  POSTs `chat/:guid/read`, which clears the unread on the account's other devices.
  **Typing indicators** are wired both ways (Private-API, 1:1 only — the server
  filters group typing): the open thread shows an animated `•••` when the other
  party types, and typing in the compose bar POSTs/DELETEs `chat/:guid/typing`.
  **Non-image attachments** (video/audio/vcard/pdf) render as a tappable
  `Type · filename` row; tapping downloads the file and hands off to an external app
  (`ACTION_VIEW` → share chooser → "can't open" line) via a `FileProvider`. (This is
  the last of the Phase 3 list; only inline *playback* of video/audio and history
  pagination remain unbuilt.)
  **Full-screen image viewer (done):** tapping an inline image opens
  `ImageViewerScreen` (`ui/ImageViewerScreen.kt`) — drawn as an opaque overlay
  *on top of* the thread (a `Box`, **not** `ChatDetailsScreen`'s early-return
  pattern: that unmounted the `LazyColumn`, so dismissing lost the scroll
  position and re-loaded every inline image — the disorienting-return bug).
  Same loader/caches as the inline render so it appears instantly. Pinch to zoom (capped at 4×, matching the 1080px decode cap), drag
  to pan while zoomed, double-tap to toggle zoom at the tapped point; a single
  tap or Back closes. Pure black, no chrome. The image row's own
  `combinedClickable` re-offers the long-press so tapbacks on images still work
  (the column's handler would otherwise be shadowed by the image's), and a tap
  while the tapback picker is open dismisses the picker instead of opening the
  viewer.
  **Color while viewing (done):** the viewer lifts LightOS's forced grayscale for
  exactly its own lifetime — vandamd's zero-camera trick. The phone's B&W look is
  the accessibility daltonizer pinned to mode 0 (simulate monochromacy), a secure
  setting; `ColorMode` (`ColorMode.kt`) flips
  `accessibility_display_daltonizer_enabled` off on `acquire` (viewer enters
  composition, via `DisposableEffect`) and restores the saved mode on `release` —
  a SurfaceFlinger color-matrix change, so both flips are instant. **Hiding the
  restore is the viewer's close sequence**, not a timer (timers were tried and
  failed both ways — whichever side of the dismissal frame the flip landed on,
  either the full-screen photo or its inline thumbnail visibly desaturated):
  tap/Back sets `closing`, the photo fades out to the black background (120ms),
  `release` fires while the screen is pure black — black is identical in color
  and mono, the one moment the flip can't be seen — a ~70ms hold lets the async
  settings write land, then `onClose()` reveals the thread, already B&W.
  `onAppHidden` restores immediately, no fade (another app's colors are showing).
  `MainActivity.onStop/onStart` → `onAppHidden`/`onAppVisible` keep the rest of
  the phone B&W if the app is backgrounded mid-view and re-lift on return.
  Requires a one-time `adb shell pm grant com.craigeley.chat
  android.permission.WRITE_SECURE_SETTINGS` (signature-level; declared with
  `tools:ignore="ProtectedPermissions"`); ungranted, every call no-ops and the
  viewer stays grayscale. Known gap (zero has it too): a process death mid-view
  leaves the phone in color until the app next runs.
  **Links (done):** iMessage attaches a `*.pluginPayloadAttachment` rich-link
  preview blob to every URL it sends; we can't render the preview and the URL is
  already in the text, so these are dropped at parse time (`parseAttachments`) rather
  than shown as junk file rows. URLs in a message body are linkified
  (`ThreadScreen.linkify` → `LinkAnnotation.Url`) so they're tappable (underlined,
  open in the browser).
  **Read receipts — display (done):** messages carry `dateDelivered`/`dateRead`;
  the thread shows a dim "Delivered" / "Read 3:14 PM" line under your newest sent
  message in a 1:1 (not groups — no single read state). Status changes arrive live
  as `updated-message` socket events through the existing guid merge; no Private
  API needed to *display* (only `markRead` sending is gated).
  **Group-event rows (done):** renames/member changes are messages with
  `itemType != 0` (no text — they used to render as blank turns). Known events
  (rename / member added/removed/left / photo change, see `GroupEvent` in
  `Models.kt`) render as a centered dim line ("Liz named the conversation “X”");
  unknown itemTypes (e.g. FaceTime markers) are dropped in `foldReactions`. The
  socket also subscribes to `group-name-change`/`participant-*` events (same
  serialized-message payload as `new-message`, routed through `messageEvent`; never
  notified). In the list they bump recency but the text preview keeps the newest
  real message; a live rename updates the open thread's title and triggers a
  `refresh()`. Note: renaming a *forked* group can legitimately split its merged
  row, since `groupIdentity` keys on name + participants and dead sibling rooms
  keep the old name.
  **Replies (done, Private-API):** long-press → Reply queues the next send as an
  inline reply (`selectedMessageGuid` on `message/text`); a banner above the
  compose bar shows the target until sent/cancelled. Messages with a
  `threadOriginatorGuid` render a dim `↳ “quote”` line above the turn
  (`ChatMessage.shortDescription` describes the original; "an earlier message"
  when it isn't loaded).
  **Group management (done, Private-API):** tapping a *group* thread's title opens
  `ChatDetailsScreen` — rename (`PUT /chat/:guid`, sent to **every** room of a
  forked group so the name+participants merge key holds), member list with
  add/remove (`POST /chat/:guid/participant/add|remove`; iMessage may fork a new
  room on add), and leave (`POST /chat/:guid/leave`, all rooms). Destructive taps
  confirm by second tap. Read-only member list without the Private API.
  **Failed sends (done):** `ChatMessage.error` is parsed; any sent message with a
  non-zero error shows a full-white "Not delivered" line (replacing its receipt).
  The socket subscribes to `message-send-error` (routed like `updated-message`)
  so the flag lands live — previously a send the Mac accepted but couldn't
  deliver (e.g. a non-iMessage number) failed silently. Complemented by a
  **new-message availability check** (`GET /handle/availability/imessage`,
  Private-API): adding a recipient verifies they can receive iMessages; a flagged
  recipient dims, a line explains, and the compose bar hides until they're removed.
  **List timestamps** are iMessage-style absolute (`ConversationsScreen.listTime`):
  time today, "Yesterday", weekday within a week, then a short date — not
  "18 hours ago".
  **Unread markers (done):** a row with unread messages shows a text-only `• `
  before its title plus a full-white preview line. Derived in
  `BlueBubblesApi.conversations()` from the newest non-group-event message per
  room: `!fromMe && dateRead == 0` (chat.db stamps `dateRead` on incoming messages
  when the chat is read on *any* device, so this is account-wide; group events are
  skipped — they never get a stamp and would pin the dot). Live: `applyIncoming`
  flags a new incoming message/tapback unless that thread is open + foregrounded.
  Cleared by opening the thread here, or by the **`chat-read-status-changed`**
  socket event (the server's chat.db poller — no Private API needed) when the chat
  is read on the Mac/iPhone, which also dismisses the chat's notification. The
  ViewModel's session-lived `clearedUnread` (primary guid → lastDate at clear)
  stops a refresh from resurrecting a just-cleared dot while the server's
  `dateRead` stamp catches up with our `markRead`.
  **Notification deep-links (done):** message notifications are per-chat (id
  hashed from the chat guid, so each thread keeps its own and a newer message
  replaces it) and tapping one opens that thread: the PendingIntent carries
  `Notifications.EXTRA_CHAT_GUID`, `MainActivity` (singleTask — onCreate or
  onNewIntent) hands it to `ChatViewModel.openByGuid`, which matches by guid
  *membership* (forked groups) and — on a cold start, before the list exists —
  queues the open via `pendingOpenGuid` until `loadConversations` lands.
  `Notifications.clear` (app foregrounded) enumerates `activeNotifications`
  rather than tracking ids, so it survives process restarts; the
  foreground-service notification is skipped.
  **Forked group chats (done):** iMessage can split one group into sibling chat
  rooms — same name, identical participants, different guid — with messages divided
  across them by "era". BlueBubbles reports each room as its own chat, so the list
  (keyed on chat guid) showed the group twice, and a room whose newest activity was a
  tapback surfaced as its own "Loved …" thread. We collapse them: see
  `BlueBubblesApi.conversations` + `groupIdentity` and `Conversation.guids` below.

Note: messaging yourself (note-to-self) legitimately shows each message twice —
iMessage stores a sent *and* a received row (two GUIDs). Normal chats don't; the
socket echo of your own sends dedupes by GUID.

## Commands

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21                 # JDK 21 required
./gradlew assembleDebug                                       # build debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk      # install on device
./gradlew assembleRelease                                     # minified, debug-signed so it sideloads
```

Only `arm64-v8a` is built (the Light Phone's ABI). minSdk 34 (matches the device —
Android 14), target/compile SDK 35. JDK 21 runs Gradle; the app compiles to Java
11 bytecode. Android SDK at `/opt/homebrew/share/android-commandlinetools`. The
device shows in `adb devices` as `LightPhoneIII` / model `TLP301` (USB or
adb-over-wifi; it is otherwise reached only over Tailscale).

## Setup / auth

On first launch the app asks for the **BlueBubbles Server URL and password**
(`SetupScreen` → `ChatViewModel.saveSetup`), validates them against
`GET /api/v1/server/info`, and stores both on the device (the password encrypted).
The URL is also editable later in `SettingsScreen` (`ChatViewModel.updateServerUrl`,
which re-validates and bounces the socket). If the API returns 401/403 the app
clears the password and returns to setup.

## Architecture

- **`Store`** (`api/Store.kt`) — SharedPreferences. The **server URL**
  (`baseUrl`/`setBaseUrl`) is entered at setup and editable in Settings — one
  self-hosted server per install; `setBaseUrl` normalizes it (defaults the scheme
  to `https://`, strips a trailing slash). The password is encrypted at rest via `SecureStore`. Also
  persists the **contact index** (`setContacts`/`contacts`) as the normalized
  key→name map (JSON) so `SocketService` can name notification senders without the
  app running; the ViewModel writes it on each contacts load, `signOut` wipes it
  with everything else. Also caches the **Private API flag** (`privateApi`/
  `setPrivateApi`) read from `server/info`, so the UI knows on launch whether to
  offer tapbacks before the first refresh lands.
- **`SecureStore`** (`api/SecureStore.kt`) — at-rest encryption only. An
  AES-256-GCM key lives non-exportable in the AndroidKeyStore (hardware-backed)
  and encrypts the password. (Trimmed down from `ask`'s version — no Ed25519 /
  Bouncy Castle here; BlueBubbles auth is just the password.)
- **`BlueBubblesApi`** (`api/BlueBubblesApi.kt`) — the REST surface, plain
  `HttpURLConnection` + `org.json` (no networking dependency, like hive/pod).
  Auth is the server password as the `password` query param on every call.
  `serverInfo()` → `GET /server/info` (`ServerInfo.reachable` is the setup check)
  and also reads `private_api && helper_connected` into the `ServerInfo` so the app can
  gate tapback *sending* on the Private API being live; `react(guid,selectedMsgGuid,
  reaction,partIndex)` → `POST /message/react` (Private-API only; `reaction` is a
  `ReactionType.apiValue`, prefix `-` to remove; returns the created reaction message
  to reconcile its echo); `markRead(guid)` → `POST /chat/:guid/read` (Private-API
  only; clears unread across the account's devices); `startTyping(guid)`/
  `stopTyping(guid)` → `POST`/`DELETE /chat/:guid/typing` (Private-API only);
  `messages(guid)` → `GET /chat/:guid/message`
  (`with=handle,attachment`, `sort=DESC`, guid URL-encoded); `send(guid,text,tempGuid,
  method)` → `POST /message/text` (only `chatGuid`+`message` required; we pass a
  `tempGuid` to correlate the echo and a `method` — `private-api` when the server's
  Private API is live, else `apple-script`. The ViewModel picks the method via
  `sendMethod()` off the cached `privateApi` flag, and the *room* to send to via
  `sendTargets()` (see **Forked-group sends** below); parse the created message from the
  response);
  `sendAttachment(guid,bytes,name,mime,tempGuid,method)` → `POST /message/attachment`
  (the one **multipart/form-data** call — built by hand, not via `request()` — same
  `tempGuid`/`method` echo handling as `send`, same `sendTargets()` room selection);
  `downloadAttachment(guid,dest)`
  → `GET /attachment/:guid/download` (streams the raw bytes to a file, for the inline
  image loader); `newChat(address,text)` →
  `POST /chat/new` (starts a 1:1 by sending the first message — macOS Big Sur+
  requires the message inline; returns the new chat guid); `contacts()` → `GET /contact`
  flattened to (address,name) pairs. Message parsing lives in the **companion**
  (`parseMessage`, `messageText`, `messageEvent`) so the socket service decodes
  `new-message`/`updated-message` payloads with the same logic. `parseAttachments`
  drops `*.pluginPayloadAttachment` rich-link blobs (iMessage's URL previews — not
  real files). `messageText` guards `isNull("text")` because org.json's `optString`
  returns the literal string `"null"` for an explicit JSON null (a text-less message
  would otherwise render the word "null"). `ApiException.isAuthError`
  flags 401/403. Timestamps are epoch millis, carried through unchanged.
  - **`conversations()` deliberately does NOT use `chat/query`.** That endpoint
    sorts by an unreliable `lastmessage` cache and often returns an empty
    `lastMessage`, so on this account (~2700 chats) a freshly-active chat can fall
    outside its first page and never appear, and the list shows stale ordering
    (this was the "stuck at 4 hours ago" bug). Instead we drive the list from one
    `POST /message/query` DESC sweep (`with:[chats,chats.participants,attachment]`,
    the newest ~1000 messages) and take the first message seen per chat — already in
    true recency order, with each chat's metadata read off the embedded chat object.
    (`attachment` is in the `with` so an attachment-only last message gets a real
    preview via `ChatMessage.previewText` — `[Photo]` etc. — rather than a blank line;
    without it the sweep can't tell text-less messages from empty ones, which was the
    inconsistent-preview bug.)
    Trade-off: only chats active within the sweep window appear (the recent ones —
    what a messages list shows). **1:1 chats (style 45) return empty
    `participants`; the `chatIdentifier` is the other party's address, so we fall
    back to it** — otherwise 1:1 rows render as "Unknown".
    For the preview the sweep prefers the newest **non-reaction** message (so a row
    reads "So you'll watch…" not a bare "Loved …"), while still bumping recency by
    the newest message's date — a tapback bumps the thread like iMessage does.
  - **Delta refresh (LP3-18).** The sweep is split into `sweepRows(limit, after)`
    (fetch) + `deriveConversations(rows)` (the pass-1/pass-2 logic above). The
    ViewModel retains the swept rows (`sweepRows`, ~1000 newest); unattended
    refreshes (`deltaRefresh()` — socket-triggered and post-send) fetch only
    messages `after:` the newest row, merge, and re-derive — a few KB instead of
    the full ~2–3 MB sweep, debounced 2 s so socket bursts coalesce. User-driven
    refreshes (Refresh button, Settings, group ops, delete) stay full sweeps —
    that's the recovery path for anything a delta can't see (deletions, forked-room
    reshuffles, `serverInfo`/contacts re-checks, unread reconciliation).
  - **Attachment downloads are server-downscaled (LP3-19):** the inline loader
    passes `maxDim=1080` and `downloadAttachment` sends it as `width`/`height`,
    so the server resizes images before they cross the wire (non-images and GIFs
    ignore the params). `openAttachment`'s file handoff still fetches originals.
  - **Forked-group merge (`conversations` pass 2 + `groupIdentity`).** A group iMessage
    has split into sibling rooms (same name + identical participants, different guid)
    is collapsed into one `Conversation` whose `guids` lists every room (newest-active
    first, so `guid` is the send target). Keyed by `groupIdentity` =
    `displayName + sorted participants` — **groups only** (style 43); 1:1s and unforked
    groups key by their own guid and never merge, so single-room chats are unchanged.
    The thread (`ChatViewModel.open`) fetches each member room independently and
    interleaves them (`foldReactions` sorts by date and folds tapbacks across rooms),
    resilient to one dead room; socket routing, `bumpConversation`, and mark-read all
    match by **guid membership** (`guid in conversation.guids`), not equality.
- **`ChatViewModel`** — single source of truth (`UiState` as a `StateFlow`).
  Drives `Idle → Loading → Ready/Error`. Owns password setup, the conversation
  list, the open thread, sending, and the live feed. **Ordering is enforced
  here** — conversations `sortedByDescending { lastDate }`, messages
  `sortedBy { date }` — which is the whole reason this exists. `sendMessage()`
  appends an optimistic message under a temp guid, then swaps in the server's
  echo (real guid) so the socket's `new-message` dedupes by guid.
  **Forked-group sends (`sendTargets` + `sendAcrossRooms`):** a forked group spans
  several room guids, and not all of them are sendable. With the **Private API** the
  server resolves a chat by DB identity, so any room works — `sendTargets` returns just
  `[convo.guid]`. With **AppleScript** (no Private API), the server's `chat id "…"`
  lookup *fails on a dead/stale fork room* — it throws `-1728` ("Can't get chat id"),
  which drops the server into its DM-only fallback script that rejects groups ("Can't
  use the send message (fallback) script to text a group chat!"). The *live* sibling
  room resolves and delivers fine. So for AppleScript, `sendTargets` returns **all**
  the group's room guids (UUID-form first, then `chat<number>` forms) and
  `sendAcrossRooms` tries each until one delivers. This is safe against double-sending:
  `-1728` fails during resolution, before any message goes out. (Empirically confirmed:
  one forked group's `chat388…` room `-1728`s while its `chat495…` sibling sends — see
  the verified-from-server-logs investigation.) Both `sendMessage` and `sendImage` go
  through this. It collects
  `SocketBus` and folds incoming/updated messages into the list (`bumpConversation`)
  and the open thread; a message for an unknown chat triggers a `refresh()`.
  **Tapbacks:** the open thread keeps a raw message list (`openRaw`, reaction
  messages included) as its source of truth; `state.messages` is always
  `foldReactions(openRaw)`, which attaches each tapback to its target message as a
  `Reaction` (keyed by (target, reactor), latest add wins, a `-`-prefixed removal
  clears it) and drops the reaction rows. Every open-thread mutation goes through
  `updateOpenThread(guid){…}` — guarded so a late send/echo can't clobber a thread
  you've navigated away from — so sends, the socket merge (`mergeRaw`), and
  `sendReaction` all re-fold. `sendReaction(target,type)` is optimistic + echo-
  reconciled like `sendMessage`, toggles off if you already hold that reaction, and
  no-ops unless `state.privateApi` (the cached `server/info` capability).
  `markReadIfPrivate(guid)` fires `markRead` best-effort (off-main, errors ignored)
  when you open a thread and on a foreground incoming message, so reading here
  clears the unread on your other devices — also gated on `state.privateApi`.
  **Typing:** collects `SocketBus.typing` into `state.typingChatGuid` (with a 12s
  auto-expiry, since a "stopped" event can be missed); `onComposeTextChanged` sends
  `startTyping` on the first keystroke and `stopTyping` after a 4s pause / empty
  field / send / close — gated on `privateApi`. Keeps a
  session-lived per-conversation `messageCache` (now the *raw* list) so reopening a
  thread is instant (cached shown immediately, fresh fetch refreshes in the
  background; snapshotted on `closeThread`). Starts/stops
  `SocketService`. `sendNewMessage(address,text)` starts a fresh 1:1 via `newChat`
  then opens it; the searchable `contactList` (one `Contact` per address, built
  from `contacts()`) feeds the new-message picker. Screen routing derives from
  state: no password → setup; `composingNew` → new message; `open != null` →
  thread; else list.
- **Socket** (`socket/`) — the live channel.
  - **`SocketService`** — a **`remoteMessaging`** foreground service holding one
    Socket.IO connection (`io.socket:socket.io-client`, websocket transport,
    password as a query param). On `new-message`/`updated-message` it parses via
    `BlueBubblesApi.messageEvent`, pushes onto `SocketBus`, and — when the app
    isn't foreground — posts a notification. The single ongoing socket is the
    whole battery argument vs OpenBubbles' Flutter runtime. (Type is
    `remoteMessaging`, not `dataSync`, specifically because Android 14+ **blocks
    `dataSync` from starting at `BOOT_COMPLETED`** — `remoteMessaging` is allowed
    and is the correct semantic type.)
  - **`BootReceiver`** — restarts `SocketService` on `BOOT_COMPLETED` (if a
    password is stored) so the socket reconnects after a reboot without opening
    the app. BOOT_COMPLETED arrives post-unlock, so encrypted prefs / Keystore
    are available. Needs `RECEIVE_BOOT_COMPLETED`. **Device requirement:** the
    socket can't reach `…ts.net` until Tailscale's tunnel is up, which isn't the
    case at boot unless **Tailscale "Always-on VPN"** is enabled (Android Settings
    → Network → VPN). With it on, the service's reconnect loop connects the moment
    the tunnel comes up (verified: ~15s of `connect error` retries post-boot, then
    `socket connected` the instant Tailscale connected). Leave "Block connections
    without VPN" OFF.
  - **`SocketBus`** — process-wide `MutableSharedFlow`s bridging the service (alive
    even when the activity is dead) to the ViewModel: `incoming` (messages) and
    `typing` (`TypingEvent`, from the socket's `typing-indicator` event).
  - **`AppForeground`** — a volatile flag set by `MainActivity.onStart/onStop` so
    the service only notifies for messages the user isn't already looking at.
- **Models** (`Models.kt`) — `Conversation` (carries `guids: List<String>` — every
  chat-room guid it spans, usually just `[guid]`, more for a forked group; `guid` is
  the primary/send target); `ChatMessage` (guid, text, date,
  fromMe, sender, `attachments: List<Attachment>`); `Attachment` (guid, mimeType,
  transferName, width, height — `isImage` gates inline rendering; `typeLabel`/
  `fileLabel` drive the non-image file row). `ChatMessage`
  carries the shared `ATTACHMENT_PLACEHOLDER` (`[Attachment]`) constant; its
  `bodyText` returns null when the text is *only* that placeholder for image(s) we
  draw inline (so an image-only message shows just the image), and `images` is the
  image subset. `previewText` is the conversation-list one-liner: the text, or a
  bracketed attachment summary (`[Photo]` / `[3 Photos]` / `[Attachment]`) when
  there's none — bracketed so it can't be mistaken for literal "photo" text, and used
  wherever `Conversation.lastText` is set so the preview is consistent. **Tapback fields:** `associatedMessageGuid`/`associatedMessageType`
  are set only on reaction messages (`isReaction`, `isReactionRemoval`,
  `reactionTargetGuid` strips iMessage's `p:<n>/`/`bp:` prefixes); `reactions:
  List<Reaction>` is populated by `foldReactions` for display. **Gotcha:** the
  server runs `associatedMessageType` through a transformer, so it arrives as a
  *word* (`love`/`laugh`/…, prefixed `-` for a removal), **not** the raw iMessage
  int (2000/3000/…) — so `ChatMessage.associatedMessageType` is a `String` and
  `ReactionType` keys off that word (its `apiValue`, which doubles as the
  `message/react` reaction param). The visual mark per type is drawn/typeset in
  `ui/Tapbacks.kt`, not stored on the enum. `Reaction` (type, fromMe, sender). `IncomingMessage` (socket payload: chatGuid, message, isNew,
  chatDisplayName); `Contact` (name, address — a pickable recipient for a new message).
- **`Attachments`** (`Attachments.kt`) — the inline-image loader, **dependency-free**
  (no Coil/Glide, matching the house style). `image(context, api, attachment)`
  (suspend, off-main): returns a decoded `ImageBitmap` or null; downloads via
  `BlueBubblesApi.downloadAttachment` (streams `GET /attachment/:guid/download` over
  the same socket), caches the raw bytes under `cacheDir` (so reopening a thread
  doesn't refetch), decodes a **downsampled** bitmap (`inSampleSize` to cap the long
  edge at 1080px — a full-res photo would OOM the phone), applies the **EXIF
  orientation** (BitmapFactory ignores it, so portrait phone photos would otherwise
  render sideways), and holds a small guid-keyed `LruCache`. `cacheLocal(guid,bytes)`
  seeds the cache from a just-picked image so an optimistic outgoing message renders
  through the same path with no round trip. The ViewModel exposes `loadImage`;
  `ThreadScreen`'s `AttachmentImage` loads it lazily via `produceState`, showing
  `[Image]` until ready. **Non-image files** don't use this loader: they render as a
  tappable `AttachmentFile` row (`Type · filename`), and `ViewModel.openAttachment`
  downloads the raw bytes to `cacheDir/shared/` and opens them through the
  `FileProvider` + `ACTION_VIEW`/share intent — no decoding, just a handoff. Sending: `ViewModel.sendImage(uri)` reads the picked bytes
  (the compose bar's "+" launches the system photo picker — no permission), seeds
  the cache, posts an optimistic bubble, then reconciles with the server echo. For a
  brand-new chat there's no guid yet, so `sendNewImage(address, uri)` constructs the
  canonical 1:1 guid `iMessage;-;<handle>` (the address normalized to its E.164/email
  handle by `imessageHandle` — a constructed guid can't lean on `newChat`'s loose
  AppleScript address resolution), sends the attachment to it (which creates the
  chat server-side), then opens the thread + refreshes.
- **Screens** (`ui/`) — `SetupScreen` (password entry), `ConversationsScreen`
  (list, tap title → settings, Refresh, **New**), `NewMessageScreen` (a "To" field
  that searches the contact index by name/number/email or takes a raw address,
  then a compose bar; sends via `newChat` and opens the thread), `ThreadScreen`
  (messages — text + inline images — and a compose bar with a back chevron; `linkify`
  turns http/https URLs in a body into tappable `LinkAnnotation.Url` links),
  `SettingsScreen` (server host + refresh
  + sign out). `ComposeBar` is shared; its optional `onPickImage` adds a leading
  "+" that opens the photo picker — wired in both `ThreadScreen` (sends into the open
  chat) and `NewMessageScreen` (once a recipient is chosen; sends as the first
  message of a new 1:1 via `sendNewImage`); its optional `onTextChange` (thread only)
  drives the typing-indicator sends. `ThreadScreen`'s `TypingIndicator` (an animated
  `•••` just above the compose bar) shows while `state.typingChatGuid` matches the
  open chat. The thread
  `LazyColumn` is **`reverseLayout = true`** with messages newest-first, so it
  opens anchored at the latest (no scroll-to-bottom animation — that whoosh was the
  old bug); scroll *up* for history. A new newest message auto-scrolls down only if
  you're already near the bottom. Thread bubbles-less readability: each turn is
  **width-capped at 80%**
  (`MESSAGE_MAX_WIDTH`) and hugs its side; a name label ("You" / sender) shows
  **only on the first message of a same-speaker run**. **Tapbacks** (`ui/Tapbacks.kt`):
  Public Sans has no heart/thumb/triangle glyphs (they'd fall back to a different
  font — the original bug), so the three iconic tapbacks are drawn as monochrome
  vector paths (`Icon` + parsed Material path data, tinted) and the three text ones
  (haha / ‼ / ?) are typeset in Public Sans — iMessage's own icon+text split, all
  dependency-free. A turn's folded `reactions` render in its empty **gutter**
  (`GutterReactions`) with a small *drawn* arrow pointing back at it (`<- ♥`
  received, `♥ ->` yours — Public Sans lacks the arrow glyphs too, so it's a
  `Canvas` shaft+chevron); same-type reactions collapse to one glyph + count (`♥3`), a type is
  full white if you're among its reactors else 70%, and the row `FlowRow`-wraps when
  many pile up. Long-pressing a turn opens an inline `ReactionPicker` (the six drawn
  marks, your current one bright so re-tapping reads as remove) — only when
  `state.privateApi` is true (`combinedClickable(enabled = canReact)`), since sending
  needs the server's Private API. `Notifications` has two
  channels — high-importance "messages" (per-message) and low "service" (the
  ongoing foreground notification).

## Light Phone III specifics

These look odd out of context but are deliberate, and match a family of sibling
LightOS apps that share the same conventions:

- **UI is black-and-white, Public Sans, text-only** (vandamd's LightOS style).
  Use `ChatColors`, `ChatType`, `ChatDimens` from `ui/theme/ChatTheme.kt` — not
  Material defaults. "Buttons" are tappable text (`HapticText`). Messages have no
  bubbles — the user's turns right-aligned, others left, width-capped with
  run-collapsed name labels (see the Screens section).
- **Font scale is pinned to 0.85** in `ChatTheme` (LightOS ships a large default).
- Portrait-only, single activity, `adjustResize`.
- **Full-screen / immersive** — `MainActivity.enableImmersive()` hides the top
  status bar via `WindowInsetsControllerCompat.hide(Type.statusBars())`
  (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`), re-applied in `onWindowFocusChanged`.
  This is the native equivalent of what vandamd's (Expo) apps do with
  `setStatusBarHidden`. We hide the **status bar only** (not the nav bar) so the
  bottom compose field clears the gesture strip; the same `enableImmersive()` is
  in every sibling app (ask/hive/jot/pod/reel) for a consistent full-screen look.
- **No Google Play dependency anywhere** — that's the whole point, and it's *why*
  we run our own server instead of the official BlueBubbles app (which needs FCM).
  Transport is `HttpURLConnection` (REST) + `io.socket:socket.io-client` (the live
  socket — a plain JVM dep, not a Google one; `org.json` excluded from it since the
  platform provides it). Don't reintroduce Play Services / Firebase.

## The server

A **BlueBubbles Server** on an always-on Mac signed into iMessage, reached over
**Tailscale Serve** at the user-configured `https://<machine>.<tailnet>.ts.net`
URL (entered at setup) — the server stays LAN-bound; Tailscale provides TLS +
private routing, and the live socket (Phase 2) is the push channel, so no FCM is
needed. See the README for the full self-host walkthrough.

**Private API (optional).** `private_api` is off by default. Enabling it lets the
server send tapbacks (and later typing/read receipts) by injecting a helper dylib
into Messages — there's no bundle to install by hand; the server does the
injection once two macOS protections are off: **Library Validation** (`sudo
defaults write /Library/Preferences/com.apple.security.libraryvalidation.plist
DisableLibraryValidation -bool true`) **and SIP** (`csrutil disable` from
Recovery). Then flip the Private API toggle in the server's Settings; its status
box should report the helper connected, and `GET /server/info` returns
`"private_api": true, "helper_connected": true`. The app reads exactly those two
fields (`BlueBubblesApi.serverInfo`) to decide whether to offer tapback sending —
so users who don't want to disable SIP simply never see the picker. README has the
step-by-step. (Library Validation is the step the BlueBubbles docs bury — SIP-off
alone won't let the dylib load.)
