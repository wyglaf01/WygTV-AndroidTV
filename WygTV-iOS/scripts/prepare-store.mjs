import fs from 'node:fs';
const file = 'ios/App/App/Info.plist';
let source = fs.readFileSync(file, 'utf8');
source = source.replace(/(<key>CFBundleDisplayName<\/key>\s*)<string>[^<]*<\/string>/, '$1<string>WygTV</string>').replace(/\s*<string>altstore<\/string>/g, '');
fs.writeFileSync(file, source);
console.log('Prepared WygTV display name and App Store URL schemes');
