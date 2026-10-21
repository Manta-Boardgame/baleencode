// Which companies are allowed to run the model for you.
//
// DeepSeek V4.1 Flash is an open-weights model, so many companies host it.
// On Vercel AI Gateway the default routing includes providers in China
// (Alibaba Cloud and DeepSeek itself). This app never uses them: every request
// is sent with an explicit `only` allowlist, and the gateway returns an error
// rather than falling back to a provider outside that list.
//
// Each entry was checked against the company's published headquarters.
// Edit this file if your own policy differs; the Android app has the same list
// in android/app/src/main/java/com/deepseek/chat/Providers.java.

export const MODEL = 'deepseek/deepseek-v4.1-flash-1017';
export const GATEWAY = 'https://ai-gateway.vercel.sh';

/** Providers this app is allowed to use. */
export const ALLOWED_PROVIDERS = [
  { slug: 'togetherai', name: 'Together AI', country: 'United States (San Francisco, CA)' },
  { slug: 'fireworks', name: 'Fireworks AI', country: 'United States (Redwood City, CA)' },
  { slug: 'baseten', name: 'Baseten', country: 'United States (San Francisco, CA)' },
  { slug: 'deepinfra', name: 'DeepInfra', country: 'United States (Palo Alto, CA)' },
  { slug: 'relace', name: 'Relace', country: 'United States (San Francisco, CA)' },
];

/**
 * Screen control ("Screen" mode) uses a vision model that is good at pointing at things on screen.
 * Xiaomi MiMo-V2.6-Flash scored 80.8% on OSWorld-Verified (Oct 2026) at $0.14 / $0.28 per million tokens,
 * and in our own check located buttons within ~10 px. It is open-weights; only DeepInfra (US) is allowed to run it.
 * The rule is the same as for the chat model: if DeepInfra cannot serve it, the request fails.
 */
export const SCREEN_MODEL = 'xiaomi/mimo-v2.6-flash';
export const SCREEN_PROVIDERS = ['deepinfra'];

/** Providers that serve this model but are never used, and why. */
export const BLOCKED_PROVIDERS = [
  { slug: 'alibaba', name: 'Alibaba Cloud Model Studio', reason: 'Chinese company (default route if no allowlist is set)' },
  { slug: 'deepseek', name: 'DeepSeek', reason: 'Chinese company (the model creator)' },
  { slug: 'novita', name: 'Novita AI', reason: 'Does not guarantee that prompts are kept out of training' },
  { slug: 'gmicloud', name: 'GMI Cloud', reason: 'No zero-data-retention guarantee' },
  { slug: 'inference-net', name: 'Inference.net', reason: 'No zero-data-retention guarantee' },
  { slug: 'runinfra', name: 'RunInfra', reason: 'No zero-data-retention guarantee' },
  { slug: 'pareto', name: 'Pareto', reason: 'No zero-data-retention guarantee' },
  { slug: 'morph', name: 'Morph', reason: 'Headquarters not verified' },
  { slug: 'parasail', name: 'Parasail', reason: 'Headquarters not verified' },
  { slug: 'particle', name: 'Particle', reason: 'Headquarters not verified' },
  { slug: 'wafer', name: 'Wafer', reason: 'Headquarters not verified' },
];
