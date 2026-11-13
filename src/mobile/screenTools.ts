export const SCREEN_FEATURES = [
  { id: 'explain', label: 'Explain screen', question: 'What am I looking at? Explain only what is visible.' },
  { id: 'error', label: 'Explain error', question: 'Read the visible error accurately and explain likely causes and safe next steps.' },
  { id: 'guide', label: 'Where to tap', question: 'Identify the likely next control for my question. Describe it; do not tap anything.' },
  { id: 'translate', label: 'Translate screen', question: 'Translate the visible text, preserving names, numbers and warnings.' },
  { id: 'form', label: 'Explain form', question: 'Explain visible form fields without filling or submitting anything.' },
  { id: 'tutorial', label: 'Tutorial · one step', question: 'Give only one next step for using this app, then wait for my confirmation and a new approved observation.' },
  { id: 'followup', label: 'Follow-up', question: 'Answer using the approved screen and current session context. Say if the relevant option is not visible.' },
] as const;
export type ScreenFeature = typeof SCREEN_FEATURES[number]['id'];

/** Voice tools cannot grant MediaProjection consent or approve an image upload. */
export const SCREEN_TOOL = {
  name: 'screen_companion',
  description: 'Check actual screen-sharing status, request a screen review for explicit approval, pause/resume analysis, or stop sharing. Starting by voice only tells the user to open Screen Companion and tap Start Screen Sharing to approve Android permission. Never claim you see a screen without an approved observation. Analyze/review requests require the user to review the exact screenshot and explicitly send it from the app. Never bypass privacy approval or tap controls.',
  parameters: { type: 'OBJECT', properties: {
    operation: { type: 'STRING', enum: ['status', 'start', 'stop', 'pause', 'resume', 'review', 'analyze'] },
    feature: { type: 'STRING', enum: SCREEN_FEATURES.map(feature => feature.id) },
    question: { type: 'STRING', description: 'The user’s actual question about their screen. Preserve exact details.' },
    language: { type: 'STRING', enum: ['en', 'hi', 'ur'] },
  }, required: ['operation'] },
};
