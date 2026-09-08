# Chat

An **iMessage client** for the [Light Phone III](https://www.thelightphone.com/),
inspired by and based on the apps created by [vandamd](https://github.com/vandamd). The app works by talking to an always-on, self-hosted [BlueBubbles Server](https://github.com/BlueBubblesApp/bluebubbles-server), reached privately over [Tailscale](https://tailscale.com/).

I built this to replace OpenBubbles on my LPIII, which I found to be both a battery hog and very flaky in terms of displaying and ordering messages correctly.

Open the app to a list of conversations (newest activity first); tap one to read the
thread, or tap **New** to start one (searches your contacts by name/number/email).
Tap **Messages** at the top for settings, **Refresh** to re-pull.

## Prequisites

There are several things you have to have up and running in order for this to work.

1. A LightPhoneIII modified to reveal the full Android layer. For full instructions on this, I highly recommend fully reading [this guide](https://acrobat.adobe.com/id/urn:aaid:sc:US:0c80fa32-de30-406f-85ca-93ccd92c3c4b).
2. A BlueBubbles server up and running on an always-on Mac, which is signed into your iMessage account.
3. Tailscale installed on your Mac *and* and your modified LPIII.

## Setup

1. **Install BlueBubbles Server**. Install it from
   [bluebubbles.app](https://bluebubbles.app/install/) on a Mac that is signed
   into your iMessage account and stays awake. During setup
   it asks you to set a server password — remember it; the app uses it to
   authenticate.
   
   In the setup steps, you should **skip / ignore** the Google Firebase section entirely, as well as the Proxy Service. You can set that to LAN only. Tailscale (in the next step) will handle it from here.

2. **Put the Mac and the phone on the same tailnet.** Install
   [Tailscale](https://tailscale.com/download) on both and sign them into the
   same account. On the Mac, expose the BlueBubbles port (default `1234`) over
   HTTPS with Tailscale Serve:

   `tailscale serve --bg 1234`

   This gives the Mac a stable `https://<machine>.<tailnet>.ts.net` URL with TLS,
   reachable only from your own devices — no public exposure, no certificates to
   manage. (Any other HTTPS reverse proxy works too; Tailscale Serve is just the
   easiest.) Run `tailscale serve status` to see the URL.

3. Install this app. The best way to do it is to put this url into Obtainium.

4. **Configure the app.** On first launch, enter that `https://…ts.net` URL and
   the BlueBubbles server password. The app validates them against the server and
   stores them on the device.

### Optional: enable the Private API (tapbacks, read receipts, typing)

By default BlueBubbles can only send via AppleScript, which can't send tapbacks,
mark chats read, or send typing indicators. For a full list of features enabled by the private API, see this page. Those need BlueBubbles' **Private
API**, which injects a helper into Messages — and that requires turning off two
macOS protections. It's optional; skip this and everything else still works.

1. **Disable Library Validation** (lets the helper load into Messages):

   ```sh
   sudo defaults write /Library/Preferences/com.apple.security.libraryvalidation.plist DisableLibraryValidation -bool true
   ```

2. **Disable System Integrity Protection (SIP).** Boot into Recovery
   (Apple Silicon: hold the power button → *Options*; Intel: hold ⌘R at boot),
   open Terminal, run `csrutil disable`, then reboot. Verify with `csrutil status`
   — it should read `disabled`. (On Apple Silicon this also disables running iOS
   apps on the Mac.) Do this at your own risk; a VM snapshot first is wise.

3. **Flip it on in the server.** BlueBubbles Server → *Settings* → **Private API**
   toggle on. There's no bundle to install by hand — the server injects the helper
   itself. Hit refresh on the **Private API Status** box; it should report the
   helper connected. (`GET /api/v1/server/info` then shows `"private_api": true`
   and `"helper_connected": true` — the app reads this to decide whether to offer
   tapbacks etc.)

### Optional: full-color photo viewing

The Light Phone's grayscale is Android's accessibility color-correction filter,
which apps can lift with a permission only grantable over adb. With it granted,
tapping a photo in a thread shows it in **full color** for exactly as long as
the viewer is open — the phone returns to grayscale the moment you dismiss it
(the same trick as [zero](https://github.com/vandamd/zero)'s red-text mode):

```sh
adb shell pm grant com.craigeley.chat android.permission.WRITE_SECURE_SETTINGS
```

One-time; it survives app updates. Without it, photos simply open in grayscale
like the rest of the phone.

## Staying connected

Chat keeps one websocket open to your server from a small foreground service —
that's how messages arrive instantly without Google push. It is built to hold up
unattended:

- It reconnects the moment a network appears (after a reboot, when Tailscale's
  tunnel comes up, when you leave a dead spot) and goes idle — no retries at all —
  while there is no network, so a bad signal doesn't cost battery.
- Every time it connects it asks the server for anything that arrived while it
  was away, so a message sent while the phone was off or out of range still
  shows up and still buzzes (unless you already read it on another device).
- It restarts itself after a reboot and after you update the app.
- A message that arrives while chat is in the background buzzes (and dings, if
  the volume is up) — on stock LightOS too, where there is no notification shade.
- The ongoing "chat" notification's text shows the state (`Connected`,
  `Reconnecting…`, `Offline — waiting for a network`), and the conversation list
  says "Offline — reconnecting…" if the link has been down for more than a few
  seconds.

For delivery after a reboot without opening the app, enable Tailscale's
**Always-on VPN** on the phone (Android Settings → Network → VPN) and leave
"Block connections without VPN" **off** — otherwise the service waits, idle,
until you open Tailscale yourself. Over a day and a half of the 0.6.0 service on
the Light Phone III, chat used about a minute and a half of CPU and 4 MB of
Wi-Fi data.

## Install

Install the app via Obtainium.

## License

[MIT](LICENSE).
