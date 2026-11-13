import './screenCompanion.css';
import { SCREEN_FEATURES, type ScreenFeature } from './screenTools';

export interface ScreenBridge {
  getScreenState?(): string;
  screenStart?(mode: string): void;
  screenStop?(): void;
  screenPause?(paused: boolean): void;
  screenReview?(): void;
  screenAnalyze?(frameId: string, feature: string, question: string, language: string): void;
  screenDiscardReview?(): void;
  screenSetOptions?(json: string): void;
}
export interface ScreenWorkspaceOptions {
  bridge?: ScreenBridge;
  language?: string;
  /** The existing Live voice engine, never a new microphone or implicit TTS. */
  onSpeakViaLive?: (text: string, frameId: string) => boolean | void;
}
interface Review {
  frameId: string;
  sessionId: string;
  sequence: number;
  capturedAt: number;
  width: number;
  height: number;
  sensitive: boolean;
}
interface ScreenState {
  state: string;
  hasProjection: boolean;
  sessionId: string;
  frameSequence: number;
  message: string;
  ocrAvailable: boolean;
  windowLabel: string;
  reviewPending: boolean;
}
const INACTIVE = new Set(['DISABLED', 'IDLE', 'STOPPED', 'PERMISSION_DENIED', 'ERROR']);
const BUSY = new Set(['REQUESTING_PERMISSION', 'STARTING', 'STOPPING', 'ANALYZING', 'WAITING_FOR_AI']);
const STATES = new Set(['DISABLED', 'IDLE', 'REQUESTING_PERMISSION', 'STARTING', 'CAPTURING', 'ANALYZING', 'WAITING_FOR_AI', 'SPEAKING', 'PAUSED', 'STOPPING', 'STOPPED', 'PERMISSION_DENIED', 'ERROR']);

/** Native events are authoritative. UI clicks never fabricate capture/provider state. */
export function mountScreenCompanion(options: ScreenWorkspaceOptions) {
  const { bridge, onSpeakViaLive } = options;
  let disposed = false;
  let starting = false;
  let sending = false;
  let review: Review | null = null;
  let approved: Review | null = null;
  let approvedFeature: ScreenFeature | null = null;
  let previewLoaded = false;
  let preserveHighlightOnCompletion = false;
  let geometry = '';
  let selectedFeature: ScreenFeature = 'explain';
  let answerFrameId = '';
  let expireTimer: ReturnType<typeof setTimeout> | undefined;
  let state: ScreenState = { state: bridge?.screenStart ? 'IDLE' : 'DISABLED', hasProjection: false, sessionId: '', frameSequence: 0, message: '', ocrAvailable: false, windowLabel: '', reviewPending: false };
  const entry = document.createElement('button');
  entry.id = 'screen-companion-entry';
  entry.className = 'screen-entry';
  entry.type = 'button';
  entry.setAttribute('aria-haspopup', 'dialog');
  entry.textContent = '◈ Screen Companion';
  (document.querySelector('.footer-actions') || document.querySelector('header') || document.body).appendChild(entry);
  const dialog = document.createElement('dialog');
  dialog.id = 'screen-companion';
  dialog.className = 'screen-workspace';
  dialog.setAttribute('aria-labelledby', 'screen-heading');
  dialog.innerHTML = `
    <div class="screen-title-row"><div><small class="screen-eyebrow">MYRAA · SCREEN COMPANION</small><h2 id="screen-heading">Understand your screen</h2></div><button type="button" id="screen-close" aria-label="Close Screen Companion workspace">×</button></div>
    <div class="screen-state-row"><span id="screen-state" class="screen-state" role="status" aria-live="polite">Disabled</span><span id="screen-window"></span></div>
    <p id="screen-state-message" class="screen-help"></p>
    <p class="screen-help">Share only when you choose. Every image stays in memory until you review it and explicitly send that exact image to Gemini. Protected content cannot be read. No automatic tapping.</p>
    <label for="screen-mode">Observation mode</label><select id="screen-mode"><option value="once">Ask once · review one snapshot</option><option value="guidance">Live guidance · approve each observation</option></select>
    <div class="screen-control-grid"><button type="button" id="screen-start" class="screen-primary">Start Screen Sharing</button><button type="button" id="screen-review">Review current frame</button><button type="button" id="screen-pause">Pause Analysis</button><button type="button" id="screen-stop" class="screen-stop">Stop Sharing</button></div>
    <p id="screen-guidance-note" class="screen-help">Android will ask what to share. Switch to the screen you need help with; return here or use an available system sharing control to review an observation. Returning to MYRAA may change the content of full-screen sharing.</p>
    <section class="screen-features" aria-label="Screen assistance features"><h3>Ask about the screen</h3><div id="screen-features" class="screen-feature-grid"></div></section>
    <label for="screen-question">Your question <span class="screen-help">(optional)</span></label><textarea id="screen-question" rows="2" maxlength="2000" placeholder="What does this error mean?" dir="auto"></textarea>
    <label for="screen-language">Answer language</label><select id="screen-language"><option value="en">English</option><option value="hi">हिन्दी · Hindi</option><option value="ur">اردو · Urdu</option></select>
    <section id="screen-review-panel" class="screen-review-panel" hidden aria-labelledby="screen-review-heading"><h3 id="screen-review-heading">Review before sending</h3><p id="screen-review-info" class="screen-help"></p><div id="screen-preview-wrap" class="screen-preview-wrap"><img id="screen-preview" alt="Actual captured screen awaiting your review"><div id="screen-highlight" hidden role="img" aria-label="Suggested control location; guidance only"></div></div><p id="screen-privacy-warning" class="screen-warning" role="status"></p><p id="screen-ocr" class="screen-help"></p><label class="screen-attestation"><input type="checkbox" id="screen-approve">I reviewed this exact image. It contains no passwords, authentication codes, banking details or other sensitive information I do not want to send to Google.</label><div id="screen-review-actions" class="screen-review-actions"><button type="button" id="screen-send" class="screen-primary" disabled>Send reviewed frame to Gemini</button><button type="button" id="screen-discard">Discard image</button></div></section>
    <section id="screen-answer-panel" class="screen-answer-panel" hidden aria-labelledby="screen-answer-heading"><h3 id="screen-answer-heading">Screen explanation</h3><p id="screen-answer" dir="auto"></p><button type="button" id="screen-speak">Speak through MYRAA Live</button><p id="screen-voice-note" class="screen-help">Use the existing Talk session for natural voice. Android text-to-speech is not enabled by this workspace.</p><button type="button" id="screen-next-step" hidden>Review a new screen for the next step</button></section>
    <section id="screen-error-panel" class="screen-error-panel" hidden aria-labelledby="screen-error-heading"><h3 id="screen-error-heading">Check Screen Companion</h3><p id="screen-error" role="alert" dir="auto"></p><p class="screen-help">Stop sharing, check your connection and configured Gemini model, then start a new session when ready. A denied or ended projection always needs fresh Android consent.</p></section>
    <details class="screen-details"><summary>Privacy, quality and data usage</summary><p class="screen-help">No screenshot is saved to history by default. Sensitivity checks are best effort, so inspect every preview yourself. Live guidance samples at a low frequency and does not continuously upload. Every approved JPEG uses network data; the provider may charge for image input. Understanding can lag behind changes.</p><label for="screen-interval">Local sampling interval</label><select id="screen-interval"><option value="2000">2 seconds</option><option value="5000" selected>5 seconds · default</option><option value="10000">10 seconds · save battery</option></select><label for="screen-quality">JPEG quality</label><select id="screen-quality"><option value="50">50 · less data</option><option value="65" selected>65 · balanced</option><option value="80">80 · more detail</option></select><button type="button" id="screen-options">Apply sampling options</button><p id="screen-diagnostics" class="screen-help"></p><p id="screen-ocr-support" class="screen-help">On-device OCR availability has not been reported. No local OCR result is assumed.</p></details>`;
  document.body.appendChild(dialog);
  const el = <T extends HTMLElement = HTMLElement>(id: string) => dialog.querySelector<T>(`#${id}`)!;
  const message = (text: string) => { el('screen-state-message').textContent = text; };
  const error = (text: string) => { el('screen-error').textContent = text; el('screen-error-panel').hidden = !text; };
  const native = (fn: (() => void) | undefined, missing: string) => {
    if (!fn) { error(missing); return false; }
    try { fn(); return true; } catch { error('Android could not handle that screen request. Stop sharing and try again.'); return false; }
  };
  function erasePreview(notifyNative = false) {
    clearTimeout(expireTimer);
    review = null; approved = null; approvedFeature = null; previewLoaded = false; preserveHighlightOnCompletion = false;
    el<HTMLImageElement>('screen-preview').removeAttribute('src');
    el('screen-highlight').hidden = true;
    el('screen-review-panel').hidden = true;
    el<HTMLInputElement>('screen-approve').checked = false;
    el('screen-ocr').textContent = '';
    el('screen-review-actions').hidden = false;
    el('screen-approve').closest('label')!.hidden = false;
    if (notifyNative) native(bridge?.screenDiscardReview?.bind(bridge), 'Screen review is unavailable in this build.');
    updateControls();
  }
  function eraseAnswer() {
    answerFrameId = '';
    el('screen-answer').textContent = '';
    el('screen-answer-panel').hidden = true;
    el('screen-next-step').hidden = true;
  }
  function updateControls() {
    const active = state.hasProjection && !INACTIVE.has(state.state);
    const busy = BUSY.has(state.state) || sending;
    for (const button of el('screen-features').querySelectorAll<HTMLButtonElement>('button')) button.disabled = busy;
    el<HTMLSelectElement>('screen-mode').disabled = state.hasProjection || starting;
    el<HTMLButtonElement>('screen-start').disabled = starting || state.hasProjection || busy || !bridge?.screenStart;
    el<HTMLButtonElement>('screen-review').disabled = !active || busy;
    el<HTMLButtonElement>('screen-stop').disabled = !state.hasProjection && !starting && !['REQUESTING_PERMISSION','STARTING'].includes(state.state);
    el<HTMLButtonElement>('screen-pause').disabled = !active || ['STOPPING','STARTING'].includes(state.state);
    el('screen-pause').textContent = state.state === 'PAUSED' ? 'Resume Analysis' : 'Pause Analysis';
    el<HTMLButtonElement>('screen-send').disabled = !active || (state.state === 'PAUSED' && !state.reviewPending) || busy || !review || review.sensitive || !previewLoaded || !el<HTMLInputElement>('screen-approve').checked;
    el<HTMLButtonElement>('screen-speak').disabled = !onSpeakViaLive || !answerFrameId || !active;
    el<HTMLButtonElement>('screen-next-step').disabled = !active || busy;
    entry.textContent = state.hasProjection ? '◈ Screen · sharing' : '◈ Screen Companion';
    entry.classList.toggle('is-sharing', state.hasProjection);
    entry.setAttribute('aria-label', state.hasProjection ? 'Open Screen Companion; Android screen sharing is active' : 'Open Screen Companion');
  }
  function setFeature(feature: ScreenFeature) {
    selectedFeature = feature;
    for (const item of el('screen-features').querySelectorAll<HTMLButtonElement>('button')) item.setAttribute('aria-pressed', String(item.dataset.feature === feature));
    el<HTMLTextAreaElement>('screen-question').placeholder = SCREEN_FEATURES.find(item => item.id === feature)!.question;
  }
  function setLanguage(language: string) {
    const code = language.split(/[-_]/)[0].toLowerCase();
    if (['en','hi','ur'].includes(code)) el<HTMLSelectElement>('screen-language').value = code;
  }
  function open() { if (!disposed && !dialog.open) dialog.showModal(); }
  function requestReview() {
    erasePreview(); eraseAnswer(); error('');
    native(bridge?.screenReview?.bind(bridge), 'Screen review requires the updated Android app.');
  }
  entry.onclick = open;
  el('screen-close').onclick = () => dialog.close();
  dialog.addEventListener('close', () => erasePreview(true));
  el('screen-start').onclick = () => {
    if (starting || state.hasProjection || BUSY.has(state.state)) return;
    erasePreview(); eraseAnswer(); error('');
    starting = true; updateControls();
    const mode = el<HTMLSelectElement>('screen-mode').value;
    if (!native(bridge?.screenStart ? () => bridge.screenStart!(mode) : undefined, 'Real Android screen sharing is unavailable in this browser/build.')) { starting = false; updateControls(); }
  };
  el('screen-review').onclick = requestReview;
  el('screen-stop').onclick = () => {
    starting = false; sending = false; erasePreview(); eraseAnswer();
    native(bridge?.screenStop?.bind(bridge), 'Screen capture cannot be controlled by this build.');
    updateControls();
  };
  el('screen-pause').onclick = () => {
    const paused = state.state !== 'PAUSED';
    if (paused) { sending = false; erasePreview(); eraseAnswer(); }
    native(bridge?.screenPause ? () => bridge.screenPause!(paused) : undefined, 'Pause/resume requires the updated Android app.');
  };
  el('screen-discard').onclick = () => erasePreview(true);
  el<HTMLInputElement>('screen-approve').onchange = updateControls;
  el<HTMLImageElement>('screen-preview').onload = () => { previewLoaded = true; updateControls(); };
  el<HTMLImageElement>('screen-preview').onerror = () => { previewLoaded = false; message('The screenshot preview could not be decoded. Discard it and review another frame.'); updateControls(); };
  el('screen-send').onclick = () => {
    if (el<HTMLButtonElement>('screen-send').disabled || !review) return;
    const frame = review;
    if (Date.now() - frame.capturedAt > 60000) { erasePreview(true); error('This preview expired. Review a new screen before sending.'); return; }
    sending = true; approved = { ...frame }; approvedFeature = selectedFeature; review = null;
    el<HTMLInputElement>('screen-approve').checked = false;
    el('screen-highlight').hidden = true;
    updateControls();
    const question = el<HTMLTextAreaElement>('screen-question').value.trim() || SCREEN_FEATURES.find(feature => feature.id === selectedFeature)!.question;
    const language = el<HTMLSelectElement>('screen-language').value;
    if (!native(bridge?.screenAnalyze ? () => bridge.screenAnalyze!(frame.frameId, selectedFeature, question, language) : undefined, 'Gemini image analysis requires the updated Android app.')) { sending = false; erasePreview(true); }
  };
  for (const feature of SCREEN_FEATURES) {
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = feature.label; button.dataset.feature = feature.id;
    button.setAttribute('aria-pressed', String(feature.id === selectedFeature));
    button.onclick = () => {
      setFeature(feature.id);
      if (!review && state.hasProjection && state.state !== 'PAUSED' && !BUSY.has(state.state) && !sending) requestReview();
      else if (!state.hasProjection) message('Start Screen Sharing and approve Android consent before asking about actual screen content.');
    };
    el('screen-features').appendChild(button);
  }
  el('screen-options').onclick = () => {
    const intervalMs = Number(el<HTMLSelectElement>('screen-interval').value);
    const jpegQuality = Number(el<HTMLSelectElement>('screen-quality').value);
    native(bridge?.screenSetOptions ? () => bridge.screenSetOptions!(JSON.stringify({ intervalMs, jpegQuality, maxEdge: 1280 })) : undefined, 'Sampling options are unavailable in this build.');
  };
  el('screen-next-step').onclick = requestReview;
  el('screen-speak').onclick = () => {
    if (!answerFrameId || !state.hasProjection) return;
    const accepted = onSpeakViaLive?.(el('screen-answer').textContent || '', answerFrameId);
    el('screen-voice-note').textContent = accepted === false ? 'Start the existing Talk session to hear this approved screen explanation through MYRAA Live.' : 'The approved explanation was offered to the existing Live voice session. Stop sharing cancels screen speech.';
  };
  function onEvent(event: any): boolean {
    if (disposed || !event || typeof event.type !== 'string' || !event.type.startsWith('screen-')) return false;
    if (event.type === 'screen-open') {
      if (SCREEN_FEATURES.some(feature => feature.id === event.feature)) setFeature(event.feature);
      if (typeof event.question === 'string') el<HTMLTextAreaElement>('screen-question').value = event.question.slice(0, 2000);
      if (typeof event.language === 'string') setLanguage(event.language);
      open();
      return true;
    }
    if (event.type === 'screen-state') {
      const input = event.screen || event.capture || event;
      let nextState = String(input.state || '');
      if (!STATES.has(nextState)) return true;
      const ownsProjection = input.hasProjection === true || input.projectionActive === true;
      const invalidOwnership = ['CAPTURING','ANALYZING','WAITING_FOR_AI','SPEAKING','PAUSED'].includes(nextState) && !ownsProjection;
      if (invalidOwnership) nextState = 'STOPPED';
      const sessionId = String(input.sessionId || '');
      const frameSequence = Number(input.frameSequence ?? input.sequence ?? state.frameSequence);
      const nextGeometry = input.width && input.height ? `${input.width}x${input.height}/${input.rotation ?? ''}` : geometry;
      const geometryChanged = !!geometry && !!nextGeometry && nextGeometry !== geometry;
      geometry = nextGeometry;
      const preserveCompletion = preserveHighlightOnCompletion && nextState === 'PAUSED' && input.reviewPending !== true && !geometryChanged && sessionId === state.sessionId && frameSequence === state.frameSequence;
      if (nextState === 'PAUSED' && input.reviewPending !== true) preserveHighlightOnCompletion = false;
      const changed = geometryChanged || (state.sessionId && sessionId && state.sessionId !== sessionId) || (frameSequence > state.frameSequence && (review || approved || el<HTMLImageElement>('screen-preview').hasAttribute('src')));
      state = {
        state: nextState,
        hasProjection: ownsProjection,
        sessionId,
        frameSequence: Number.isFinite(frameSequence) ? frameSequence : 0,
        message: invalidOwnership ? 'No active Android projection resources. MYRAA cannot see your screen.' : String(input.message || ''),
        ocrAvailable: input.ocrAvailable === true,
        windowLabel: String(input.windowLabel || ''),
        reviewPending: input.reviewPending === true,
      };
      if (['once','guidance'].includes(input.mode)) el<HTMLSelectElement>('screen-mode').value = input.mode;
      if ([2000,5000,10000].includes(Number(input.intervalMs))) el<HTMLSelectElement>('screen-interval').value = String(input.intervalMs);
      if ([50,65,80].includes(Number(input.jpegQuality))) el<HTMLSelectElement>('screen-quality').value = String(input.jpegQuality);
      starting = ['REQUESTING_PERMISSION','STARTING'].includes(nextState);
      if (nextState !== 'WAITING_FOR_AI') sending = false;
      if (changed || !state.hasProjection || nextState === 'STOPPING' || (nextState === 'PAUSED' && !state.reviewPending && !preserveCompletion)) {
        erasePreview();
        if (!state.hasProjection || nextState === 'STOPPING') eraseAnswer();
      }
      el('screen-state').textContent = nextState.replaceAll('_', ' ').toLowerCase();
      el('screen-state').dataset.active = String(state.hasProjection);
      message(state.message || (state.hasProjection ? 'Android projection is active. Review an image before sending it.' : 'No active Android screen projection.'));
      el('screen-window').textContent = state.windowLabel || (state.hasProjection ? 'App/window identity not provided by Android' : '');
      el('screen-ocr-support').textContent = state.ocrAvailable ? 'On-device OCR is available. Local recognized text is labelled separately from Gemini analysis.' : 'On-device OCR is unavailable in this build. No local OCR result is assumed. Gemini image analysis requires your approval.';
      el('screen-diagnostics').textContent = `${state.hasProjection ? 'Projection resources owned' : 'No projection resources'} · frame ${state.frameSequence || 'not acquired'}${input.width && input.height ? ` · ${input.width} × ${input.height}` : ''}${input.intervalMs ? ` · ${input.intervalMs} ms sampling` : ''}`;
      if (nextState === 'ERROR' || nextState === 'PERMISSION_DENIED') error(state.message || 'Screen sharing could not continue. Start a new session when ready.');
      updateControls(); return true;
    }
    if (event.type === 'screen-review') {
      if (!state.hasProjection || (state.state === 'PAUSED' && !state.reviewPending) || (event.sessionId && event.sessionId !== state.sessionId)) return true;
      erasePreview(); eraseAnswer(); sending = false;
      const jpeg = typeof event.preview === 'string' ? event.preview : typeof event.jpeg === 'string' ? `data:image/jpeg;base64,${event.jpeg}` : '';
      if (!/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(jpeg) || jpeg.length > 3000000 || typeof event.frameId !== 'string' || !event.width || !event.height) {
        error('Android did not provide a valid bounded image preview. Nothing was uploaded.'); return true;
      }
      review = { frameId: event.frameId, sessionId: String(event.sessionId || state.sessionId), sequence: Number(event.sequence || state.frameSequence), capturedAt: Number(event.capturedAt || Date.now()), width: Number(event.width), height: Number(event.height), sensitive: event.sensitive === true };
      el('screen-review-panel').hidden = false;
      el('screen-review-info').textContent = `Actual capture · ${review.width} × ${review.height} · ${new Date(review.capturedAt).toLocaleTimeString()}. Only this image is approved by the send button.`;
      el('screen-privacy-warning').textContent = review.sensitive ? String(event.privacyMessage || 'This frame may contain sensitive information. Sending is blocked. Discard it and share a non-sensitive screen.') : 'Check for passwords, authentication codes, financial information, names and private messages. Automated sensitivity detection is best effort and may miss content.';
      el('screen-ocr').textContent = typeof event.ocrText === 'string' && event.ocrText ? `Recognized locally: ${event.ocrText.slice(0, 3000)}` : 'No locally recognized text is available for this image.';
      el<HTMLImageElement>('screen-preview').src = jpeg;
      expireTimer = setTimeout(() => { erasePreview(true); message('The image review expired. Review a new frame before sending.'); }, 60000);
      open(); updateControls(); return true;
    }
    if (event.type === 'screen-answer') {
      if (!state.hasProjection || !approved || typeof event.frameId !== 'string' || event.frameId !== approved.frameId || (event.sessionId && event.sessionId !== approved.sessionId)) return true;
      sending = false; clearTimeout(expireTimer);
      const image = approved;
      const answer = typeof event.answer === 'string' ? event.answer.trim().slice(0, 20000) : '';
      if (!answer) { erasePreview(); error('Gemini returned no readable screen explanation. No result is assumed.'); return true; }
      answerFrameId = event.frameId;
      el('screen-answer').textContent = answer;
      el('screen-answer-panel').hidden = false;
      el('screen-next-step').hidden = approvedFeature !== 'tutorial';
      const h = event.highlight;
      const valid = h && Number(h.confidence) >= 0.85 && [h.x,h.y,h.width,h.height].every((v: unknown) => typeof v === 'number' && Number.isFinite(v)) && h.x >= 0 && h.y >= 0 && h.width > 0 && h.height > 0 && h.x + h.width <= 1 && h.y + h.height <= 1;
      if (valid && image.sequence === state.frameSequence) {
        const highlight = el('screen-highlight');
        highlight.style.left = `${h.x * 100}%`; highlight.style.top = `${h.y * 100}%`; highlight.style.width = `${h.width * 100}%`; highlight.style.height = `${h.height * 100}%`; highlight.hidden = false;
        el('screen-review-info').textContent = 'Guidance on the approved snapshot only. The suggested region is not an interactive control.';
        el('screen-review-actions').hidden = true;
        el('screen-approve').closest('label')!.hidden = true;
        preserveHighlightOnCompletion = true;
        expireTimer = setTimeout(() => erasePreview(), 30000);
      } else erasePreview();
      approved = null; updateControls(); return true;
    }
    if (event.type === 'screen-error') {
      if (event.sessionId && state.sessionId && event.sessionId !== state.sessionId) return true;
      sending = false; starting = false; erasePreview();
      error(String(event.message || 'Screen analysis failed. No successful analysis is assumed.'));
      updateControls(); return true;
    }
    return false;
  }
  if (bridge?.getScreenState) {
    try { onEvent({ type: 'screen-state', ...JSON.parse(bridge.getScreenState()) }); }
    catch { error('Android screen state could not be read. Start a new sharing session; no active capture is assumed.'); }
  }
  if (options.language) setLanguage(options.language);
  updateControls();
  return {
    onEvent,
    open,
    dispose() { disposed = true; erasePreview(true); eraseAnswer(); entry.remove(); dialog.remove(); },
  };
}
