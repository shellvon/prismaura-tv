const fs = require('fs');
const path = require('path');
const meta = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
const root = path.resolve(__dirname, 'node_modules');
const names = new Set();
for (const file of Object.keys(meta.inputs)) {
  const match = file.match(/node_modules\/((?:@[^/]+\/)?[^/]+)/);
  if (match) names.add(match[1]);
}
let output = 'Third-party JavaScript dependencies bundled in PrismaAura\n\n';
for (const name of [...names].sort()) {
  const dir = path.join(root, name);
  const pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
  const license = fs.readdirSync(dir).find(f => /^licen[cs]e(?:\.(?:md|txt))?$/i.test(f));
  const attribution = license ? fs.readFileSync(path.join(dir, license), 'utf8') : `License: ${pkg.license || 'unspecified'}\nAuthor: ${typeof pkg.author === 'string' ? pkg.author : JSON.stringify(pkg.author || '')}\nRepository: ${typeof pkg.repository === 'string' ? pkg.repository : (pkg.repository?.url || '')}`;
  output += `--- ${name} ${pkg.version} ---\n${attribution}\n\n`;
}
fs.writeFileSync(path.resolve(__dirname, '../../app/src/main/assets/THIRD_PARTY_NOTICES.txt'), output);
