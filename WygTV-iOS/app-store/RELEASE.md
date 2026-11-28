# WygTV App Store preparation — version 1.2.0

This is an unsigned release candidate for the full Capacitor iPhone/iPad app, bundle ID `com.wygtv.ios`. It is not an Apple-signed IPA and cannot be uploaded or installed directly. The separate `Sources/WygTVApp.swift` experiment and `project.yml` are not used by this workflow.

## Prepared

- App Store-only web bundle: removes AltStore/GitHub update checks and installation controls, adds Privacy Policy and Support links, and strips the simulator test runner from the archive.
- Optional OMDb access uses a key entered by the user; the developer's embedded key is removed from this distribution. Desktop and sideload behavior are preserved.
- App privacy manifest is included as an Xcode resource. It describes no tracking or app-owned data collection, and declares UserDefaults (CA92.1) and file timestamps (C617.1) for app settings and local guide/backup files. Confirm all final SDK behavior before attesting to privacy answers.
- Existing HTTP playlist support is retained. Certificates for HTTPS are still validated.
- Draft English listing, review instructions, privacy worksheet and export instructions.
- Dedicated `Prepare WygTV App Store release` workflow: tests, iPhone/iPad simulator screens, unsigned `.xcarchive`, and checks against the actual archived bundle.

## Required before signing or uploading

1. Enroll in the Apple Developer Program. Membership is not yet active, per the owner on November 28, 2026. Enrollment, payment and agreements are completed by the owner.
2. Confirm the legal seller/developer name, public support email and copyright holder. These remain blank in `listing.json`; no identity has been invented.
3. Register `com.wygtv.ios` in the owner's Apple team, or decide on another identifier before creating the App Store Connect record. Availability is not verified. A public AltStore identifier does not reserve it with Apple.
4. Create the iOS App Store Connect record, choose primary language and app name, and set pricing, territories, age-rating questionnaire, content-rights declaration, export compliance and privacy answers. No record has been created by this work.
5. Verify the marketing/privacy URLs from outside the owner's network, publish the policy changes with the app, and provide a support page with usable contact information. The GitHub issues page is the current draft support destination; the website is `https://wyglaf01.github.io/WygTV-AndroidTV/`.
6. Confirm rights and service terms for any review/demo media, screenshots, metadata providers, artwork and open-source components. The app supplies no subscriptions or paid channels. Do not submit Eagle credentials as a public example or bundle any real provider login.
7. Supply a reliable, review-accessible demonstration of Live TV, movies, series and guides. The CI mock server is temporary and is not a service Apple can use. The existing public `docs/demo.m3u` is only a candidate until rights, availability and feature coverage are verified.
8. Review the screenshots. CI uses generated test signals and mock catalogs, never the owner's playlist. The screenshot artifacts are candidates, not approved marketing assets. Confirm required display sizes in App Store Connect and retake screens if necessary.
9. Confirm privacy and required-reason declarations against the final app and every SDK. Test local network access, file import/export, playback, interruptions, VoiceOver and touch navigation on physical iPhone and iPad. The landscape interface is shared with the desktop app and still needs a usability review on a small phone; a passing simulator test is not App Review approval.
10. Complete the open-source license/notice audit, including the repository's GPL-3.0 license and package metadata that currently says MIT. Resolve the intended licensing and App Store distribution obligations with the owner before submission. Do not assume the package.json license supersedes the repository license.

## Build and signing

The preparation workflow creates `WygTV-AppStore-unsigned.xcarchive.zip` and candidate iPhone/iPad screenshots. No signing keys or Apple passwords are stored in this repository.

After membership and signing are configured, use the same commit in Xcode:

```sh
cd WygTV-iOS
npm ci
WYG_DISTRIBUTION=appstore WYG_ENABLE_SMOKE=0 npm run build:web
npx cap sync ios
node scripts/prepare-store.mjs
open ios/App/App.xcodeproj
```

Select the App target and the owner's Apple team, enable signing, choose a generic iOS device and Product → Archive. Use Xcode Organizer to validate and distribute to App Store Connect for TestFlight. Create a fresh signed archive from source; do not treat the unsigned archive as an uploadable distribution build. Xcode/App Store Connect provides the current SDK, signing and export-compliance validation.

Once uploaded, complete TestFlight testing and review the listing with the owner before submitting to App Review. Choose manual release so approval does not automatically publish the app. Upload, review submission and public release have not been performed.

## References

- https://developer.apple.com/app-store/review/guidelines/
- https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app/
- https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/
- https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications
- https://developer.apple.com/news/upcoming-requirements/
- https://capacitorjs.com/docs/apis/preferences
- https://capacitorjs.com/docs/apis/filesystem
