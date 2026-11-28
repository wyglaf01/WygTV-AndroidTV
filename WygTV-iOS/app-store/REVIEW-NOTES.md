# Draft review information

App: WygTV 1.2.0 — iPhone and iPad, landscape orientation.

WygTV is a player for playlists and compatible home-network tuners supplied by the user. It does not sell or supply television subscriptions, movie services or paid channels. No WygTV account is required.

## Reviewer access — owner must complete before submission

Provide a stable demonstration playlist or dedicated review account that the owner is authorized to share with Apple. It must stay reachable throughout review and cover all listed features. Enter any private credentials only in App Store Connect's private App Review Information fields, not in this public repository. The CI mock provider is not available to Apple.

Reviewer steps once the demonstration is confirmed:

1. Open Playlists/Settings and add the supplied M3U link or Xtream login.
2. Open Live TV, select a category and play an HLS test channel.
3. Open the TV guide and inspect current/upcoming program information.
4. Open Movies and Series, inspect the supplied demo titles and play an episode.
5. Add a favorite and verify it in Favorites/My List.
6. Open Settings → About WygTV for Privacy Policy and Support.

Some source formats cannot be decoded by iOS; WygTV can hand these to a compatible external player. The main review demonstration must use HLS/MP4 streams playable in the app and must not require buying another app or owning a tuner. Tuners are optional; provide a physical-device demonstration video if Apple requests one for hardware-specific functionality.

## Transport exception explanation

The app accepts provider URLs and private home-network tuner addresses entered by the user. These independent endpoints may use HTTP and cannot be enumerated ahead of time. The broad ATS allowance supports those user-selected playlists, XMLTV guides and local tuners. HTTPS requests continue to use default certificate validation. The app neither automatically downgrades HTTPS requests nor accepts invalid certificates. This exception needs explanation to Apple; acceptance is not guaranteed.

## Export compliance draft

The app uses operating-system networking/HTTPS and does not implement a proprietary encryption system. The existing `ITSAppUsesNonExemptEncryption=false` declaration must be confirmed by the owner against the final dependencies and Apple's export-compliance questions; it is not a legal determination.

No in-app purchases, account signup or advertising are currently implemented. Any future monetization or tracking requires a fresh review of the listing, privacy answers and app behavior.
