// Writes the opencode configuration that sends opencode's Eden AI models to oillamp's model relay.
//
// opencode already knows Eden AI, but only its global endpoint, and it offers a model list from
// its own catalog, most of which the EU endpoint does not serve. So this replaces both: the
// endpoint becomes the relay inside the sandbox, which oillamp forwards with the key to Eden's EU
// endpoint, and the model list becomes what the EU endpoint actually offers, fetched from it while
// the image is built (the catalog needs no key). All permissions are allowed by default, so a
// freshly started sandbox does not pause for opencode approval prompts. And opencode loads the
// skills the agent writes for itself in ~/.genie/skills, as pi does; opencode expands the ~.
//
// opencode 1 and opencode 2 read different shapes, so the major version that was installed
// decides which one is written:
//
// - opencode 1: the built-in provider `edenai`, pointed at the relay, with `whitelist` keeping
//   opencode's own catalog models out of the list.
// - opencode 2 ignores `whitelist`, and merges its catalog into any provider whose ID it knows. So
//   the EU models go under a provider of oillamp's own, `edenai-eu`, which has no catalog
//   entries, and a policy turns the built-in `edenai` off. It asks for `max_tokens`, as opencode 1
//   did, and does not update itself: `oillamp update` decides which version the sandbox has.
//
// If the catalog cannot be fetched, the endpoint is still changed and opencode keeps its own
// model list. Models in that list that the EU endpoint does not serve fail with an error from Eden
// when used, rather than silently going elsewhere.
//
// Usage: node write-opencode-config.mjs <output file> [<opencode major version, default 2>]

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const EU_BASE_URL = "https://api.eu.edenai.run/v3";
// The model relay inside the sandbox. The same in every sandbox: where it leads is decided on the
// host, and so is the key.
const RELAY_BASE_URL = "http://127.0.0.1:3129/v3";
// Where the agent keeps the skills it writes for itself, in its home.
const GENIE_SKILLS = "~/.genie/skills";
// opencode 2's provider for the EU models. Not `edenai`: opencode 2 would add its own catalog's
// Eden AI models to a provider with that ID.
const EU_PROVIDER = "edenai-eu";
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
const major = Number(process.argv[3] ?? "2");
if (!output || !Number.isInteger(major)) {
    console.error("usage: write-opencode-config.mjs <output file> [<opencode major version>]");
    process.exit(2);
}

// What the EU catalog says about each model, in neither opencode's shape.
async function euModels() {
    const response = await fetch(`${EU_BASE_URL}/models`, { signal: AbortSignal.timeout(30000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const catalog = (await response.json()).data ?? [];
    // Keyed by ID: the catalog may list a model twice, and then its last entry counts.
    const models = new Map();
    for (const model of catalog) {
        const inEu = (model.regions ?? []).some(region => region?.code?.toLowerCase() === "eu");
        const capabilities = model.capabilities ?? {};
        const outputs = capabilities.output_modalities ?? [];
        if (!inEu || !outputs.includes("text")) continue;
        const inputs = capabilities.input_modalities ?? ["text"];
        const pricing = model.pricing ?? {};
        // Eden prices per token, opencode per million tokens. Rounded, so 0.9 is not 0.8999999999999999.
        const perMillion = cost => typeof cost === "number" ? Math.round(cost * 1e12) / 1e6 : 0;
        models.set(model.id, {
            id: model.id,
            context: model.context_length || UNKNOWN_CONTEXT,
            output: maxOutputTokens(model),
            tools: Boolean(capabilities.supports_function_calling),
            reasoning: Boolean(capabilities.supports_reasoning),
            inputs: inputs.map(kind => kind === "file" ? "pdf" : kind),
            cost: {
                input: perMillion(pricing.input_cost_per_token),
                output: perMillion(pricing.output_cost_per_token),
                cacheRead: perMillion(pricing.cache_read_input_token_cost),
            },
        });
    }
    if (models.size === 0) throw new Error("the catalog lists no EU models");
    return [...models.values()].sort((a, b) => a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

// opencode 1: the built-in provider, restricted to the EU models with `whitelist`.
function version1(models) {
    const provider = { options: { baseURL: RELAY_BASE_URL } };
    if (models) {
        provider.models = Object.fromEntries(models.map(model => [model.id, {
            name: model.id,
            limit: { context: model.context, output: model.output },
            tool_call: model.tools,
            reasoning: model.reasoning,
            attachment: model.inputs.some(kind => kind !== "text"),
            modalities: { input: model.inputs, output: ["text"] },
            cost: { input: model.cost.input, output: model.cost.output, cache_read: model.cost.cacheRead },
        }]));
        // Without this, opencode would also offer the models from its own catalog.
        provider.whitelist = models.map(model => model.id);
    }
    return {
        $schema: "https://opencode.ai/config.json",
        provider: { edenai: provider },
        permission: "allow",
        skills: { paths: [GENIE_SKILLS] },
    };
}

// opencode 2: a provider of oillamp's own for the EU models, and the built-in one turned off.
function version2(models) {
    const config = { $schema: "https://opencode.ai/config.json" };
    if (models) {
        config.providers = {
            [EU_PROVIDER]: {
                name: "Eden AI (EU)",
                env: ["EDENAI_API_KEY"],
                package: "@opencode/ai/providers/openai-compatible",
                settings: { baseURL: RELAY_BASE_URL },
                compatibility: { maxTokensField: "max_tokens" },
                models: Object.fromEntries(models.map(model => [model.id, {
                    name: model.id,
                    capabilities: {
                        tools: model.tools,
                        input: model.inputs,
                        output: ["text"],
                        reasoning: { supported: model.reasoning },
                    },
                    cost: { input: model.cost.input, output: model.cost.output, cache: { read: model.cost.cacheRead } },
                    limit: { context: model.context, output: model.output },
                }])),
            },
        };
        // The built-in provider would offer opencode's own catalog of Eden AI models, most of which
        // the EU endpoint does not serve.
        config.policies = [{ action: "provider.use", resource: "edenai", effect: "deny" }];
    } else {
        config.providers = { edenai: { settings: { baseURL: RELAY_BASE_URL } } };
    }
    config.permissions = [{ action: "*", resource: "*", effect: "allow" }];
    config.skills = [GENIE_SKILLS];
    config.update = "disable";
    return config;
}

let models = null;
try {
    models = await euModels();
    console.log(`opencode: ${models.length} Eden AI models from the EU endpoint`);
} catch (error) {
    if (process.env.AGENT_TOOLS_STRICT === "true") {
        console.error(`ERROR: could not refresh Eden AI's EU model list (${error.message})`);
        process.exit(1);
    }
    console.error(`WARNING: could not read Eden AI's EU model list (${error.message}); `
        + "opencode still uses the relay, with its own model list");
}

mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, JSON.stringify(major >= 2 ? version2(models) : version1(models), null, 2) + "\n");
