// Prints the AltStore source (the list AltStore reads to install and update WygTV-iOS):
//   node scripts/altstore-source.mjs <release download URL> <version> <ipa size in bytes>
const [base, version, size] = process.argv.slice(2);
if (!base || !version) { console.error('usage: altstore-source.mjs <url> <version> <size>'); process.exit(1); }
const test = /wygtv-ios-test/.test(base);
const source = {
  name: test ? 'WygTV (test builds)' : 'WygTV',
  identifier: test ? 'com.wygtv.source.test' : 'com.wygtv.source',
  subtitle: 'Live TV, guide, movies and series',
  sourceURL: base + '/altstore-source.json',
  iconURL: base + '/WygTV-iOS-icon.png',
  website: 'https://wyglaf01.github.io/WygTV-AndroidTV/',
  tintColor: '#CD7F32',
  apps: [{
    name: 'WygTV-iOS',
    bundleIdentifier: 'com.wygtv.ios',
    developerName: 'Eddie',
    subtitle: 'WygTV for iPhone and iPad',
    localizedDescription: 'WygTV for iPhone and iPad: Xtream and M3U playlists, Live TV with a TV guide, Movies and Series with ratings, Favorites, My List and HDHomeRun antenna TV. Channels the app can\'t play itself open in VLC.',
    iconURL: base + '/WygTV-iOS-icon.png',
    tintColor: '#CD7F32',
    category: 'entertainment',
    screenshots: [],
    versions: [{
      version, buildVersion: version.split('.').pop(), date: new Date().toISOString(),
      localizedDescription: 'Build ' + version + '.', downloadURL: base + '/WygTV-iOS.ipa',
      size: Number(size) || 0, minOSVersion: '15.0',
    }],
    appPermissions: {
      entitlements: [],
      privacy: { NSLocalNetworkUsageDescription: 'WygTV-iOS looks for HDHomeRun antenna tuners and plays TV from your own network.' },
    },
  }],
  news: [],
};
console.log(JSON.stringify(source, null, 2));
