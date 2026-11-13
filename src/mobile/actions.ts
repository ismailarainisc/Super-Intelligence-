export type MobileAction = { action: 'navigate'; destination: string } | { action: 'set_alarm'; hour: number; minute: number; label?: string } | { action: 'set_timer'; seconds: number; label?: string } | { action: 'calendar_event'; title: string; startAt: number; endAt: number; location?: string } | { action: 'open_app'; app: string } | { action: 'search_youtube' | 'search_web'; query: string } | { action: 'whatsapp_message'; recipient: string; message: string };
export const MOBILE_TOOL = {
  name: 'mobile_action',
  description: 'Perform an Android action requested by the user: open an installed app, search YouTube, search the web in Chrome/browser, or open a WhatsApp message draft, open map directions, open Clock alarm/timer setup, or open a calendar draft. Calendar times are epoch milliseconds in the device timezone; ask for missing or ambiguous details. WhatsApp drafts are NOT sent; the user taps Send in WhatsApp. Never invent recipients, numbers or successful actions.',
  parameters: { type: 'OBJECT', properties: {
    action: { type: 'STRING', enum: ['open_app', 'search_youtube', 'search_web', 'whatsapp_message', 'navigate', 'set_alarm', 'set_timer', 'calendar_event'] },
    app: { type: 'STRING', description: 'App name, such as WhatsApp (WP), YouTube or Chrome. Only for open_app.' },
    query: { type: 'STRING', description: 'The exact search requested by the user. Required for search actions.' },
    recipient: { type: 'STRING', description: 'Saved contact name as spoken, or a full international phone number with country code. Contacts are resolved locally.' },
    message: { type: 'STRING', description: 'Complete message the user requested. Ask for it if missing.' },
    destination: { type: 'STRING' }, hour: { type: 'INTEGER' }, minute: { type: 'INTEGER' }, seconds: { type: 'INTEGER' },
    label: { type: 'STRING' }, title: { type: 'STRING' }, startAt: { type: 'INTEGER' }, endAt: { type: 'INTEGER' }, location: { type: 'STRING' },
  }, required: ['action'] },
};
/** Exact local typed commands; Live voice uses structured Gemini function calls. */
export function parseMobileAction(input: string): MobileAction | null {
  const text = input.trim().replace(/^(?:hey\s+)?(?:myraa|myra|मायरा)[,\s:]+/iu, '').replace(/^(?:please|can you|could you)\s+/i, '').trim();
  let simple = /^navigate to ([^\n]+)$/i.exec(text);
  if (simple && !/\b(?:and|then)\b/i.test(simple[1])) return { action: 'navigate', destination: simple[1].trim() };
  simple = /^set (?:a )?timer (?:for )?(\d+) (seconds?|minutes?|hours?)[.!]?$/i.exec(text);
  if (simple) { const seconds = Number(simple[1]) * (/^hour/i.test(simple[2]) ? 3600 : /^minute/i.test(simple[2]) ? 60 : 1); if (seconds >= 1 && seconds <= 86400) return { action: 'set_timer', seconds }; }
  simple = /^set (?:an? )?alarm (?:for |at )?(\d{1,2}):(\d{2})[.!]?$/i.exec(text);
  if (simple && Number(simple[1]) < 24 && Number(simple[2]) < 60) return { action: 'set_alarm', hour: Number(simple[1]), minute: Number(simple[2]) };
  let match = /^(?:open\s+)?(?:youtube|you tube)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+on\s+youtube$/i.exec(text);
  if (match) return { action: 'search_youtube', query: match[1].trim() };
  match = /^(?:open\s+)?(?:chrome|google chrome|browser)(?:\s+and)?\s+search(?:\s+for)?\s+(.+)$/i.exec(text) || /^search(?:\s+for)?\s+(.+?)\s+(?:on|in)\s+(?:chrome|google|browser)$/i.exec(text);
  if (match) return { action: 'search_web', query: match[1].trim() };
  match = /^(?:message|msg|text)\s+(.+?)\s+(?:on|using)\s+(?:whatsapp|wp|wa)\s*:\s*(.+)$/is.exec(text) || /^(?:whatsapp|wp|wa)\s+(?:message|msg)(?:\s+to)?\s+(.+?)\s*:\s*(.+)$/is.exec(text);
  if (match) return { action: 'whatsapp_message', recipient: match[1].trim(), message: match[2].trim() };
  const openText = text.replace(/[.!?।]+$/u, '');
  match = /^(?:open|launch)\s+([\p{L}\p{N}][\p{L}\p{N} ._-]{0,79})$/iu.exec(openText) || /^(whatsapp|wp|wa|youtube|chrome)\s+(?:kholo|khol do|open karo)$/i.exec(openText);
  if (match && !/\b(?:and|then)\b|और/iu.test(match[1])) return { action: 'open_app', app: match[1].trim() };
  return null;
}

/** Asynchronous task control; a started plan is never a completion claim. */
export const MOBILE_TASK_TOOL = {
  name: 'mobile_task',
  description: 'Start a multi-step Android task from the user’s full request, inspect its actual status, continue after the user explicitly acknowledges an external handoff, or cancel. Return task progress truthfully. Starting does not mean completion. Only use continue/cancel when explicitly requested.',
  parameters: { type: 'OBJECT', properties: {
    operation: { type: 'STRING', enum: ['start', 'status', 'continue', 'cancel'] },
    goal: { type: 'STRING', description: 'The complete user request, preserving recipients and message text. Required for start.' },
  }, required: ['operation'] },
};
