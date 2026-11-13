/** Actual mobile UI previews with a read-only native-state adapter.
 * No projection, model responses or conversations are simulated. No API key is used.
 * Run a Vite server first; requires Playwright + Chromium in a test environment. */
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const fs = require('node:fs');
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 430, height: 932 }, deviceScaleFactor: 2 });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    // This adapter supplies the source-build defaults without granting native powers.
    window.MyraaNative = {
      getState: () => JSON.stringify({ history: [], keyConfigured: false, spokenReplies: false, characterAvailable: false, quality: 'low', language: 'en-IN', model: 'gemini-3.5-flash', liveModel: 'gemini-3.1-flash-live-preview' }),
      getScreenState: () => JSON.stringify({ state: 'IDLE', hasProjection: false, sessionId: '', frameSequence: 0, message: 'Screen sharing is off. Start sharing and approve Android\'s permission request.', ocrAvailable: false }),
      endLive: () => {}, cancel: () => {}, stopSpeech: () => {},
    };
  });
  try {
    await page.goto((process.env.MYRAA_PREVIEW_ORIGIN || 'http://127.0.0.1:5179') + '/mobile.html');
    await page.waitForSelector('#screen-companion-entry');
    await page.evaluate(() => document.fonts.ready);
    await page.screenshot({ path: 'docs/images/myraa-home.png' });
    await page.locator('#screen-companion-entry').click();
    await page.screenshot({ path: 'docs/images/myraa-screen-companion.png' });
    await page.locator('#screen-close').click();
    await page.locator('#settings').click();
    await page.screenshot({ path: 'docs/images/myraa-settings.png' });
    if (errors.length) throw new Error(errors.join('\n'));
    fs.writeFileSync('docs/images/README.md', '# UI previews\n\nCaptured from the running RC8 mobile web interface in Chromium at 430 × 932 CSS pixels (2× scale). A read-only adapter supplies source-build defaults; native actions, Android capture and AI responses are not simulated. These are browser UI previews, not device or Gemini verification. The neutral M avatar is the actual fallback when private character assets are absent.\n\nReproduce with `scripts/capture-readme-previews.cjs`; see its comments for the browser prerequisites.\n');
    console.log('Captured home, Screen Companion and settings previews. No uncaught browser errors.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
