import fs from 'node:fs';
const output = process.argv[2];
if (!output) throw new Error('Provide the temporary mock-provider path');
const source = fs.readFileSync('../WygDesktop/test/mock-server.js', 'utf8')
  .replace('EN - The Matrix (1999)', 'WygTV Demo Movie (2026)')
  .replace('Breaking Bad (2008)', 'WygTV Demo Series (2026)');
fs.writeFileSync(output, source);
