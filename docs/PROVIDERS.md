# Providers: what is claimed, and how to check it

The README says the approved providers are US-headquartered, report zero data retention, and do not train on prompts.
Those are claims about third parties, so here is where each one comes from, when it was checked, and how to check it again.

## What was checked (2026-10-22)

For `deepseek/deepseek-v4.1-flash-1017`, Vercel AI Gateway's endpoint metadata lists these flags for each approved provider:

| Provider | `has_zdr` (zero data retention) | `has_no_training` |
|---|---|---|
| Together AI (`togetherai`) | true | true |
| Fireworks AI (`fireworks`) | true | true |
| Baseten (`baseten`) | true | true |
| DeepInfra (`deepinfra`) | true | true |
| Relace (`relace`) | true | true |

These flags are **reported by Vercel**, not independently audited. They describe the arrangement between Vercel and the
provider as Vercel publishes it. If you need certainty, read the provider's own terms and data-processing documents.

Headquarters (San Francisco, Redwood City and Palo Alto, California) are as recorded in
[`providers.mjs`](../providers.mjs) from each company's public information. Re-verify before relying on them.

## Check it yourself

```bash
curl -s -H "Authorization: Bearer $AI_GATEWAY_API_KEY" \
  https://ai-gateway.vercel.sh/v1/models/deepseek/deepseek-v4.1-flash-1017/endpoints
```

Look at `provider_name`, `has_zdr` and `has_no_training` in each endpoint. The same endpoint lists every provider that can
serve the model, including those this app refuses.

## Why the others are refused

| Provider | Reason |
|---|---|
| Alibaba Cloud Model Studio, DeepSeek | Companies in China; they are the gateway's default route when no allowlist is given |
| Novita AI | Does not guarantee that prompts are kept out of training |
| GMI Cloud, Inference.net, RunInfra, Pareto | No zero-data-retention guarantee |
| Morph, Parasail, Particle, Wafer | Headquarters not verified |

A different policy is a one-line change in `providers.mjs` (and `Providers.java` on Android).

## The second model (screenshots mode)

The browser "screenshots" mode uses `xiaomi/mimo-v2.6-flash`, allowed **only** on DeepInfra, with the same fail-closed rule.
Screenshots of the assistant's own browser window are sent to that provider. The model's creator is a Chinese company, but
the request is processed by a US provider; as with DeepSeek, the allowlist controls where the text is processed, not who
trained the model.

## What this does not cover

The allowlist controls **where your text is processed**. It does not change who made the model, and a US provider may run
data centres in several countries. It is not a legal or compliance guarantee.
