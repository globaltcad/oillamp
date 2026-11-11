// Writes the opencode configuration that sends its Eden AI provider to oillamp's model relay.
//
// opencode already knows Eden AI, but only its global endpoint, and it offers a model list from
// its own catalog, most of which the EU endpoint does not serve. So this replaces both: the
// endpoint becomes the relay inside the sandbox, which oillamp forwards with the key to Eden's EU
// endpoint, and the model list becomes what the EU endpoint actually offers, fetched from it while
// the image is built (the catalog needs no key). All permissions are allowed by default, so a
// freshly started sandbox does not pause for opencode approval prompts. And opencode loads the
// skills the agent writes for itself in ~/.genie/skills, as pi does; opencode expands the ~.
//
// If the catalog cannot be fetched, the endpoint is still changed and opencode keeps its own
// model list. Models in that list that the EU endpoint does not serve fail with an error from Eden
// when used, rather than silently going elsewhere.
//
// Usage: node write-opencode-config.mjs <output file>

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const EU_BASE_URL = "https://api.eu.edenai.run/v3";
// The model relay inside the sandbox. The same in every sandbox: where it leads is decided on the
// host, and so is the key.
const RELAY_BASE_URL = "http://127.0.0.1:3129/v3";
// Where the agent keeps the skills it writes for itself, in its home.
const GENIE_SKILLS = "~/.genie/skills";
// opencode asks a model for at most `limit.output` tokens per answer, thinking included. Eden's
// catalog gives most models their own limit in `max_output_tokens`, and that is what this writes.
// A model whose entry gives none gets this, the default of pi's Eden AI extension: low enough
// for nearly every model to accept it, where a guessed higher value could have every request
// to a model refused with 400.
const FALLBACK_MAX_OUTPUT_TOKENS = 8192;
const UNKNOWN_CONTEXT = 128000;

function maxOutputTokens(model) {
    const limit = model.max_output_tokens;
    return Number.isInteger(limit) && limit > 0 ? limit : FALLBACK_MAX_OUTPUT_TOKENS;
}

const output = process.argv[2];
if (!output) {
    console.error("usage: write-opencode-config.mjs <output file>");
    process.exit(2);
}

const provider = { options: { baseURL: RELAY_BASE_URL } };

try {
    const response = await fetch(`${EU_BASE_URL}/models`, { signal: AbortSignal.timeout(30000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const catalog = (await response.json()).data ?? [];
    const models = {};
    for (const model of catalog) {
        const inEu = (model.regions ?? []).some(region => region?.code?.toLowerCase() === "eu");
        const capabilities = model.capabilities ?? {};
        const outputs = capabilities.output_modalities ?? [];
        if (!inEu || !outputs.includes("text")) continue;
        const inputs = capabilities.input_modalities ?? ["text"];
        const pricing = model.pricing ?? {};
        // Eden prices per token, opencode per million tokens. Rounded, so 0.9 is not 0.8999999999999999.
        const perMillion = cost => typeof cost === "number" ? Math.round(cost * 1e12) / 1e6 : 0;
        models[model.id] = {
            name: model.id,
            limit: { context: model.context_length || UNKNOWN_CONTEXT, output: maxOutputTokens(model) },
            tool_call: Boolean(capabilities.supports_function_calling),
            reasoning: Boolean(capabilities.supports_reasoning),
            attachment: inputs.some(kind => kind !== "text"),
            modalities: {
                input: inputs.map(kind => kind === "file" ? "pdf" : kind),
                output: ["text"],
            },
            cost: {
                input: perMillion(pricing.input_cost_per_token),
                output: perMillion(pricing.output_cost_per_token),
                cache_read: perMillion(pricing.cache_read_input_token_cost),
            },
        };
    }
    if (Object.keys(models).length === 0) throw new Error("the catalog lists no EU models");
    provider.models = models;
    // Without this, opencode would also offer the models from its own catalog.
    provider.whitelist = Object.keys(models).sort();
    console.log(`opencode: ${provider.whitelist.length} Eden AI models from the EU endpoint`);
} catch (error) {
    if (process.env.AGENT_TOOLS_STRICT === "true") {
        console.error(`ERROR: could not refresh Eden AI's EU model list (${error.message})`);
        process.exit(1);
    }
    console.error(`WARNING: could not read Eden AI's EU model list (${error.message}); `
        + "opencode still uses the relay, with its own model list");
}

mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, JSON.stringify({
    $schema: "https://opencode.ai/config.json",
    provider: { edenai: provider },
    permission: "allow",
    skills: { paths: [GENIE_SKILLS] },
}, null, 2) + "\n");
