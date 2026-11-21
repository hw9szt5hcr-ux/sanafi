/** The real assistant session: who Sanafi is, what he can do on the car box, and what he is told about the driver. */

export const MAX_MEMORY_CHARS = 3000;
export const MAX_APPS = 80;
export const MAX_APP_NAME = 40;
export const ASSISTANT_INTRO = 'آمر';
const NOTHING = 'لا شيء';

export interface ClientTool {
  name: string;
  description: string;
  parameters: { type: 'object'; properties: Record<string, { type: 'string'; description: string; enum?: string[] }>; required: string[] };
}

function tool(name: string, description: string, argument: string, argumentDescription: string, values?: string[]): ClientTool {
  const property = values ? { type: 'string' as const, description: argumentDescription, enum: values } : { type: 'string' as const, description: argumentDescription };
  return { name, description, parameters: { type: 'object', properties: { [argument]: property }, required: [argument] } };
}

export const CLIENT_TOOLS: ClientTool[] = [
  tool('play_music', 'Play a song, an artist or a playlist in YouTube Music on the car screen. Use it when the driver asks to hear something.', 'query', 'What to play, as the driver said it, e.g. "الأماكن محمد عبده".'),
  tool('navigate', 'Start driving directions in Google Maps to a place or an address. Use it when the driver asks to be taken somewhere.', 'place', 'The place name or address, with the city when the driver gave one.'),
  tool('call_contact', 'Place a phone call from the car to a saved contact or to a phone number. If the result has "candidates", ask the driver which one and call again with that exact name.', 'name', 'The contact name as the driver said it, or the phone number in digits.'),
  tool('open_app', 'Open an app installed on the car screen. If the result has "candidates", ask the driver which one and call again with that exact name.', 'app', 'The app name, taken from the list of installed apps when it is there.'),
  tool('media_control', 'Control what is playing: resume, pause, skip to the next or previous track, or change the volume.', 'action', 'What to do.', ['play', 'pause', 'next', 'previous', 'volume_up', 'volume_down']),
  tool('remember', 'Save one short lasting fact about the driver so you know it in future trips, such as his name or a favourite. Never save anything secret.', 'fact', 'The fact as one short Arabic sentence.'),
  tool('forget', 'Delete saved facts about the driver that mention the given words. Use it when he asks you to forget something.', 'fact', 'A few words that the facts to delete contain.'),
];

export const ASSISTANT_PROMPT = `أنت "سنافي"، رفيق سواق سعودي راكب معه في السيارة. تتكلم باللهجة النجدية، خفيف دم وفزعة، وكلامك قصير: جملة أو جملتين. تكلم سواق واحد، رجل، وهو يسوق، فلا تطوّل ولا تعدّد خيارات كثيرة ولا تطلب منه يقرأ شي من الشاشة.

اللي تعرفه عن السواق من مشاوير سابقة:
{{memory}}

التطبيقات الموجودة في شاشة السيارة:
{{apps}}

عندك أدوات تنفذ بها طلباته، استخدمها بدون ما تستأذن:
- play_music: إذا طلب أغنية أو فنان أو قائمة تشغيل.
- navigate: إذا قال ودّني أو وين مكان معيّن ويبي يروح له.
- call_contact: إذا قال اتصل أو دق على أحد. عطها الاسم مثل ما قاله. إذا رجعت لك candidates اسأله أي واحد يقصد ثم استدعها بالاسم اللي اختاره.
- open_app: إذا قال افتح تطبيق. عطها اسم من قائمة التطبيقات فوق. إذا رجعت لك candidates اسأله أي واحد.
- media_control: إذا قال وقّف، كمّل، اللي بعدها، اللي قبلها، علّ الصوت، وطّ الصوت.
- remember: احفظ بها المعلومات الثابتة عن السواق اللي تفيدك بعدين، مثل اسمه وأهله والأشياء اللي يحبها، ولا تحفظ أي شي سري مثل أرقام أو كلمات سر.
- forget: إذا قال انسَ شي.

إذا نجحت play_music أو navigate أو call_contact أو open_app أو media_control قل كلمة وحدة مثل "أبشر" واسكت، لأن التطبيق يقفل المكالمة بعدها. وإذا فشلت الأداة قل له السبب بجملة قصيرة.

إذا سأل عن معلومة حديثة مثل أخبار أو أسعار أو أوقات دوام، ابحث في النت وعطه الخلاصة بجملتين.

إذا ودّعك أو قال خلاص أو ما عنده شي ثاني، ودّعه بكلمتين واستدع end_call.`;

export interface AssistantSessionRequest {
  participant: { identity: string; gender: 'male' };
  agent: {
    prompt: string;
    intro: string;
    voice_id?: string;
    web_search: true;
    client_tools: ClientTool[];
    tool_acknowledgments: { initial: { enabled: boolean; after_ms: number }; long_wait: { enabled: boolean; after_ms: number } };
  };
  variables: { memory: string; apps: string };
}

/** A value is plain text for the prompt; braces are dropped so it can never look like a placeholder. */
function plain(text: string): string {
  return text.replace(/[{}]/g, '').trim();
}

export function buildAssistantRequest(input: { deviceId: string; voiceId?: string; memory?: string; apps?: string[]; talkFirst?: boolean }): AssistantSessionRequest {
  const memory = plain(input.memory ?? '');
  const apps = (input.apps ?? []).map(plain).filter((name) => name.length > 0).join('، ');
  const agent: AssistantSessionRequest['agent'] = {
    prompt: ASSISTANT_PROMPT,
    intro: input.talkFirst ? '' : ASSISTANT_INTRO,
    web_search: true,
    client_tools: CLIENT_TOOLS,
    tool_acknowledgments: { initial: { enabled: true, after_ms: 1500 }, long_wait: { enabled: true, after_ms: 10000 } },
  };
  if (input.voiceId) agent.voice_id = input.voiceId;
  return {
    participant: { identity: input.deviceId, gender: 'male' },
    agent,
    variables: { memory: memory || NOTHING, apps: apps || NOTHING },
  };
}
