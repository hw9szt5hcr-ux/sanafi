import type { IncomingMessage, ServerResponse } from 'node:http';
import { TTSClient } from '@humain-voice/sdk';
import { makeCreateSession } from '../lib/humain.js';
import { ConfigError, handle } from '../lib/session.js';
import { makeResolveVoice } from '../lib/voices.js';

/** Reads a setting; a name typed in lowercase in the dashboard (api_key) is accepted too. */
function env(name: string): string {
  const value = process.env[name] ?? process.env[name.toLowerCase()];
  if (!value) throw new ConfigError(`${name} is not set`);
  return value;
}

async function listVoices() {
  const client = new TTSClient({ api_url: env('API_URL'), api_key: env('API_KEY') });
  try {
    return await client.listVoices({ timeoutSeconds: 5 });
  } finally {
    await client.close();
  }
}

const resolveVoice = makeResolveVoice(listVoices);

async function readJson(req: IncomingMessage & { body?: unknown }): Promise<unknown> {
  if (req.body !== undefined) return typeof req.body === 'string' ? safeParse(req.body) : req.body;
  const chunks: Buffer[] = [];
  for await (const chunk of req) chunks.push(chunk as Buffer);
  return safeParse(Buffer.concat(chunks).toString('utf8'));
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export default async function handler(req: IncomingMessage & { body?: unknown }, res: ServerResponse): Promise<void> {
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') {
    res.statusCode = 405;
    res.end(JSON.stringify({ error: 'method_not_allowed' }));
    return;
  }
  const header = req.headers['x-device-secret'];
  let reply;
  try {
    reply = await handle(
      { secret: Array.isArray(header) ? header[0] : header, body: await readJson(req) },
      { createSession: (request) => makeCreateSession(env('API_URL'), env('API_KEY'))(request), resolveVoice },
    );
  } catch {
    reply = { status: 500, body: { error: 'server_misconfigured' } };
  }
  res.statusCode = reply.status;
  res.end(JSON.stringify(reply.body));
}
