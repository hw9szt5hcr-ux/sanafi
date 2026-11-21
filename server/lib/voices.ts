export interface VoiceEntry {
  id: string;
  label: string;
}

/**
 * Turns a voice label (e.g. "mul_abdullah") into the account's voice id.
 * Ids differ per environment, so they are looked up, never hard-coded.
 * Any failure resolves to undefined: the session then uses HUMAIN's default voice.
 */
export function makeResolveVoice(listVoices: () => Promise<VoiceEntry[]>) {
  let cache: Map<string, string> | undefined;
  return async (label?: string): Promise<string | undefined> => {
    if (!label) return undefined;
    if (!cache) {
      try {
        cache = new Map((await listVoices()).map((voice) => [voice.label, voice.id]));
      } catch {
        return undefined;
      }
    }
    return cache.get(label);
  };
}
