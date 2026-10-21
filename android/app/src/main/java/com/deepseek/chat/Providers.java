package com.deepseek.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which companies are allowed to run the model for you.
 *
 * DeepSeek V4.1 Flash is an open-weights model, so many companies host it. On Vercel AI
 * Gateway the default routing includes providers in China (Alibaba Cloud and DeepSeek
 * itself). This app never uses them: every request carries an explicit "only" allowlist,
 * and the gateway returns an error rather than falling back to a provider outside it.
 *
 * Keep this list in sync with providers.mjs in the repository root.
 */
final class Providers {
    static final String MODEL = "deepseek/deepseek-v4.1-flash-1017";
    static final String GATEWAY = "https://ai-gateway.vercel.sh";

    /** slug, display name, headquarters */
    private static final String[][] ALLOWED = {
            { "togetherai", "Together AI", "United States (San Francisco, CA)" },
            { "fireworks", "Fireworks AI", "United States (Redwood City, CA)" },
            { "baseten", "Baseten", "United States (San Francisco, CA)" },
            { "deepinfra", "DeepInfra", "United States (Palo Alto, CA)" },
            { "relace", "Relace", "United States (San Francisco, CA)" },
    };

    /** slug, display name, why it is never used */
    private static final String[][] BLOCKED = {
            { "alibaba", "Alibaba Cloud Model Studio", "Chinese company (default route if no allowlist is set)" },
            { "deepseek", "DeepSeek", "Chinese company (the model creator)" },
            { "novita", "Novita AI", "Does not guarantee that prompts are kept out of training" },
            { "gmicloud", "GMI Cloud", "No zero-data-retention guarantee" },
            { "inference-net", "Inference.net", "No zero-data-retention guarantee" },
            { "runinfra", "RunInfra", "No zero-data-retention guarantee" },
            { "pareto", "Pareto", "No zero-data-retention guarantee" },
            { "morph", "Morph", "Headquarters not verified" },
            { "parasail", "Parasail", "Headquarters not verified" },
            { "particle", "Particle", "Headquarters not verified" },
            { "wafer", "Wafer", "Headquarters not verified" },
    };

    private Providers() {}

    static JSONArray allowedSlugs() {
        JSONArray a = new JSONArray();
        for (String[] p : ALLOWED) a.put(p[0]);
        return a;
    }

    /** The same shape the desktop server returns from /api/providers */
    static String asJson() throws Exception {
        JSONObject root = new JSONObject();
        root.put("model", MODEL);
        JSONArray allowed = new JSONArray();
        for (String[] p : ALLOWED) {
            allowed.put(new JSONObject().put("slug", p[0]).put("name", p[1]).put("country", p[2]));
        }
        JSONArray blocked = new JSONArray();
        for (String[] p : BLOCKED) {
            blocked.put(new JSONObject().put("slug", p[0]).put("name", p[1]).put("reason", p[2]));
        }
        root.put("allowed", allowed);
        root.put("blocked", blocked);
        return root.toString();
    }
}
