/** Fail restored builds instead of silently packaging a broken avatar. */
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const character = path.join(root, 'assets/characters/evelyn');
const model = path.join(character, 'model.pmx');
const required = process.env.MYRAA_REQUIRE_RC5_ASSETS === '1';
if (!fs.existsSync(model)) {
  if (required) throw new Error('RC5 assets are required. Restore them from the original APK before building.');
  console.log('Source-only build: no bundled character; neutral fallback will be used.');
} else {
  const signature = fs.readFileSync(model).subarray(0, 4).toString('ascii');
  if (signature !== 'PMX ') throw new Error('Invalid restored PMX model.');
  const mapping = JSON.parse(fs.readFileSync(path.join(character, 'textures.json'), 'utf8')).textures;
  if (!mapping || typeof mapping !== 'object' || !Object.keys(mapping).length) throw new Error('Character texture mapping is empty.');
  for (const relative of Object.values(mapping)) {
    if (typeof relative !== 'string') throw new Error('Invalid texture path.');
    const file = path.resolve(character, relative);
    if (!file.startsWith(character + path.sep) || !fs.statSync(file).isFile()) throw new Error('Missing or invalid character texture: ' + relative);
  }
  if (required && !fs.existsSync(path.join(root, 'assets/branding/myraa_icon.png'))) throw new Error('The original RC5 icon must also be restored.');
  console.log('Validated restored PMX and ' + Object.keys(mapping).length + ' texture references.');
}
