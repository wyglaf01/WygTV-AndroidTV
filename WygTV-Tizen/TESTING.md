# Testing WygTV on a Samsung TV

Phase 6: trying WygTV on a real TV before it goes to Samsung's store. Install it as in [README.md](README.md)
("Trying it on a TV"), then go through the list below with the remote. It takes about 30 minutes.

**When something is wrong:** note what you did and what happened, then open **Settings → About & updates →
🛠 Diagnostics** and take a phone photo of it. It shows the TV model, firmware, Tizen version and WygTV's recent
errors. Playlist logins are hidden, so it is safe to share. A photo of the screen where it went wrong helps too.

Tick each item that works as described.

## 1. Start-up and Home
- [ ] WygTV is in the TV's **Apps** with its icon, and opens to Home in a few seconds.
- [ ] Home looks like the computer version: the tiles line up, nothing overlaps, the text is sharp.
- [ ] The arrows move between tiles; the one with focus grows and has a gold border.
- [ ] **Back** on Home asks "Exit WygTV?"; **Back** again or **Cancel** stays; **OK** closes WygTV.
- [ ] The TV's **Home** button leaves WygTV; opening it again from Apps comes back where you were.

## 2. Playlists
- [ ] **Settings → Playlists → Add**: type an Xtream or M3U playlist with the TV's on-screen keyboard. It loads.
- [ ] An **HDHomeRun**: *Find tuners on my network* finds it (or type its IP address); its channels load.
- [ ] A **Tablo**, if you have one.
- [ ] Close WygTV and open it again: the playlists are still there and load by themselves.

- [ ] In any form (e.g. adding a playlist), **📱 Type on your phone** shows a QR code. Scan it with the phone's
      camera, type there and press **Send to TV**: the form on the TV fills in within a few seconds.

## 3. Live TV
- [ ] **Live TV** lists categories and channels with logos and what's on now.
- [ ] **OK** on a channel plays it **inside the preview box**, the right size, with sound.
- [ ] **OK** again: full screen. The on-screen bar shows the channel, programme and time, then hides.
- [ ] **CH ▲ / CH ▼** and **Up / Down** change channel; the picture comes back within a few seconds.
- [ ] Number keys: **2**, **4**, then wait: channel 24 plays. For an antenna channel: **4**, **-**, **1** plays 4.1.
- [ ] **Back** from full screen returns to the preview box, still playing; **Back** again leaves Live TV and stops it.
- [ ] **▣ Mini player**: the picture shrinks to a corner and keeps playing while you move around WygTV.
- [ ] Leave a channel playing for 15 minutes: no freezing or lost sound.

## 4. Antenna TV (HDHomeRun / Tablo)
- [ ] Antenna channels play with picture **and** sound (they are MPEG-2 with Dolby sound; the computer version
      has to convert them, the TV shouldn't need to).
- [ ] A channel with a second language: **🔊 Sound** lists it and switching works.
- [ ] **CC** turns captions on and off (if the channel has them).

## 5. TV Guide
- [ ] **TV Guide** (or the **green** key) shows programmes for the next hours; scrolling is smooth.
- [ ] **OK** on a programme airing now plays that channel.
- [ ] Close and reopen WygTV: the guide is there at once (kept on the TV), not downloaded again.

## 6. Movies and series (Xtream playlists)
- [ ] **Movies** and **Series** show posters; **OK** opens the details.
- [ ] A movie plays; **◀◀ / ▶▶** skip back and forward; **⏸ / ▶** pause and play.
- [ ] Stop halfway and play it again: it offers to carry on from there.
- [ ] A series episode plays, and the next one starts at the end.

## 7. Remote keys
- [ ] **Red** opens Live TV, **green** the TV Guide, **yellow** Search, **blue** Settings.
- [ ] **GUIDE** opens the TV Guide; **CH LIST** opens Live TV; **INFO** in the player shows the bar.
- [ ] The TV's own **volume** and **mute** keys work while WygTV plays.
- [ ] **Search**: type a channel or film name with the on-screen keyboard; results appear.

## 8. Backups and settings
- [ ] **Settings → Backup → Save a backup** says it's saved on this TV.
- [ ] Change a setting, then **Restore a backup** → the saved one: the setting comes back.
- [ ] Themes in **Settings → Appearance** change the look everywhere.

## 9. Picture and text size
- [ ] Text and menus are easy to read from the sofa (**Large**, the default).
- [ ] **Settings → Appearance → Size of text and menus** is a toggle: each **OK** goes to the next size
      (Small → Medium → Large → Extra large) and the size changes at once. Extra large is the biggest; nothing
      should be cut off at any size. Diagnostics shows the size (e.g. "size large (1.5×, laid out 1280 wide)").
- [ ] Full screen fills the TV; nothing is cut off at the edges, at every size.
- [ ] The Live TV preview picture sits exactly in its box at every size.
- [ ] On a 4K TV the menus are sharp, not blurry.

## Report back

Send:
- the TV's model (on the back of the TV, or **Settings → Support → About This TV**)
- which items didn't work, each with what happened
- the Diagnostics photo, and photos of anything that looked wrong

Everything ticked? Then WygTV is ready for phase 7, Samsung's TV store.
