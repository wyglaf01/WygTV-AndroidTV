# WygTV in Samsung's TV app store

Phase 7: getting WygTV into the **Apps** section of Samsung Smart TVs. Samsung's **Seller Office**
(<https://seller.samsungapps.com/tv>) is where TV apps are submitted, with your Samsung account. Everything to paste
in is below. Samsung changes the forms from time to time, so field names may differ a little.

## Before submitting

- [ ] Everything in [TESTING.md](TESTING.md) works on your own TV.
- [ ] The **TV partnership** is approved (step 1 below). It can take weeks, so apply early.
- [ ] `WygTV.wgt` is built with your Samsung certificate: from the `wygtv-tizen-latest` release, or
      `scripts/sign.sh` (see [README.md](README.md)). If Seller Office asks for a different distributor certificate
      for the store, make that one in Tizen Studio's Certificate Manager, update the two `TIZEN_DISTRIBUTOR_*`
      secrets, and rebuild.
- [ ] The privacy policy is online: <https://wyglaf01.github.io/WygTV-AndroidTV/privacy.html> (open it to check
      the address; it comes from the repository's GitHub Pages).
- [ ] The demo playlist is online: <https://wyglaf01.github.io/WygTV-AndroidTV/demo.m3u>.

## 1. Apply for the TV partnership

Seller Office → **Partnership request** (TV). Suggested answers:

- **App / service name:** WygTV
- **Type:** Video player (free)
- **Description of the service:**
  > WygTV is a free player for live TV, movies and series. It plays the user's own IPTV subscriptions (M3U and
  > Xtream Codes playlists) and free over-the-air channels from HDHomeRun and Tablo network tuners on the home
  > network, with a TV guide, favorites and resume. WygTV comes with no channels and sells no content; users add
  > the services and tuners they already have. It has no accounts, ads or tracking. It is already available for
  > Android TV, Fire TV, Windows, Mac, Ubuntu, iPhone and iPad.
- **Target countries:** where you want it (the US first is simplest).
- **Website:** https://github.com/wyglaf01/WygTV-AndroidTV

## 2. The store listing

- **App name:** WygTV
- **Category:** Video (or Entertainment)
- **Price:** Free
- **Short description** (one line):
  > Your IPTV playlists and antenna TV in one player, with a TV guide.
- **Description:**
  > WygTV plays live TV, movies and series from your own IPTV service, and free antenna channels from an HDHomeRun
  > or Tablo tuner on your home network, all in one place.
  >
  > • Live TV with a preview, favorites, recent channels and a full TV guide
  > • Antenna TV from HDHomeRun and Tablo tuners, with their free TV guide, including sub-channels like 4.1
  > • Movies and series from Xtream Codes services, with posters, ratings and resume
  > • M3U and Xtream Codes playlists, and XMLTV guides (gzip too)
  > • Made for the remote: channel up/down, number keys, colour shortcuts, sound tracks and subtitles
  > • Themes, backups, and no accounts, ads or tracking
  >
  > WygTV doesn't include any channels or subscriptions: it plays the services and tuners you already have.
- **Keywords:** IPTV, M3U, Xtream, live TV, TV guide, EPG, HDHomeRun, Tablo, antenna, OTA, player
- **Privacy policy:** https://wyglaf01.github.io/WygTV-AndroidTV/privacy.html
- **Support:** https://github.com/wyglaf01/WygTV-AndroidTV/issues
- **Age rating:** no content of its own. Answer the questionnaire as "no violence, language, etc. in the app";
  if it asks about user-supplied or web content, say yes (the user adds their own playlists).

### Screenshots and icon

- **Screenshots** (1920×1080): the **wygtv-tizen-store-screenshots** artifact of the latest *Build WygTV for Samsung
  TVs (Tizen)* run on GitHub (Actions), or `npm run store-shots` in `WygTV-Tizen/` (saved in `store/`). They show
  only the demo playlist: Home, Live TV, the TV guide, Movies and Settings.
- **Icon:** Seller Office says which sizes it wants. The source is `docs/img/icon-512.png`; tell me the sizes and
  they can be made from it.

## 3. Notes for Samsung's testers

Seller Office asks how to test the app. WygTV needs something to play, so give them the demo playlist:

> WygTV is a player for the user's own playlists, so it needs one to test. Please use our demo playlist of public
> test streams and Creative Commons films:
> 1. Open WygTV. On the welcome screen choose **M3U playlist** (or Settings → Playlists → Add → M3U).
> 2. Playlist link: `https://wyglaf01.github.io/WygTV-AndroidTV/demo.m3u` (Name and TV guide can stay empty).
> 3. **Live TV** lists 5 demo channels: OK plays a channel in the preview, OK again plays it full screen,
>    CH ▲▼ change channel, number keys 1–5 tune a channel, INFO shows the on-screen bar.
> 4. **Movies** has 2 films (the Sintel trailer and Big Buck Bunny): they play with pause, rewind and fast-forward, and resume where they were stopped.
> 5. Back goes back; Back on Home asks to exit. Colour keys: red Live TV, green TV Guide, yellow Search,
>    blue Settings.
>
> Antenna TV needs an HDHomeRun or Tablo tuner on the network; without one, those screens only offer to add one.

## Self-check against Samsung's usual review points

| What Samsung checks | WygTV |
|---|---|
| Back on the first screen exits (or asks to) | ✔ asks "Exit WygTV?" (tested) |
| The **Exit** key closes the app | ✔ left to the TV (WygTV doesn't take it) |
| Home key and coming back (multitasking) | ✔ the player lets go of the decoder and picks up again (tested with a stand-in) |
| Everything works with the remote, no mouse | ✔ (tested) |
| Volume and mute | ✔ left to the TV |
| No downloading or installing code / self-updating | ✔ none on the TV |
| Privacy policy | ✔ docs/privacy.html covers the TV app |
| Network lost | messages when a playlist can't load; live channels reconnect. **Check on your TV**: unplug the network while a channel plays, then plug it back in |
| Content rights | ✔ no built-in channels; the demo playlist is free test streams and CC films |
| Text readable from the sofa | ✔ the TV app is drawn 1.5× larger by default (laid out at 1280×720 and scaled up); Extra large, Medium and Small with the toggle in Settings → Appearance |
| Starts quickly, no crash | check on your TV (TESTING.md section 1) |

## After approval

Each new version: merge to `app`, download the new `WygTV.wgt` from the `wygtv-tizen-latest` release (its version,
`1.0.<build number>`, always goes up), and submit it in Seller Office as an update. Samsung reviews each update.
