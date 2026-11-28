# Privacy worksheet — owner confirmation required

This is a source-based assessment for the App Store variant, not a completed App Store Connect declaration.

| Data or service | Observed behavior | Release action |
| --- | --- | --- |
| Playlist URLs and credentials | Entered by user; stored in the app's local web storage; sent directly to their provider | Disclose in policy. Do not claim Keychain encryption or send logins to review fixtures. |
| Favorites, history, My List, progress | Stored locally on the device | No developer server or analytics upload found in this path. Confirm final build. |
| Backups | Exported on user request; can contain provider credentials | State that users control backup sharing and should keep these files private. |
| Playlist/guide/tuner providers | Receive requested URLs, login if required and normal network metadata such as IP address | Independent third-party behavior needs review; no blanket promise that third parties collect nothing. |
| OMDb/TMDb | Optional user-entered keys; movie/series title and ID queries sent directly to the selected API | Confirm service terms and disclosures. The App Store variant has no embedded OMDb key. |
| Artwork/media | Loaded from URLs in user playlists or optional metadata results | Third-party hosts receive normal requests/IP addresses. |
| SiliconDust/Tablo discovery | Contacts vendor discovery endpoints when the feature is used | Confirm vendor data handling and physical-device local-network permission behavior. |
| GitHub updates | Removed from App Store bundle | App Store manages updates. Public support links are user initiated. |
| Advertising/analytics/tracking | No SDK for these purposes identified in the app's listed dependencies | Proposed no tracking; owner must validate final dependency manifests and runtime. |
| Required-reason APIs | Preferences/UserDefaults; local filesystem metadata | Manifest includes CA92.1 and C617.1 for app-owned settings/files. SDK manifests must also be present. |

“Data Not Collected” may be appropriate only if the final app and all integrated partners meet Apple's definition. Do not select it solely because the developer does not operate a backend. Resolve any third-party collection used for analytics or advertising before attesting.

No WygTV account can be created, so there is no WygTV account-deletion flow. Users can remove playlists/settings locally or uninstall the app. Uninstalling does not necessarily remove user-exported backups.

Existing policy candidate: https://wyglaf01.github.io/WygTV-AndroidTV/privacy.html
Marketing website supplied by owner: https://wyglaf01.github.io/WygTV-AndroidTV/
Owner's public contact email: not supplied.
