# Implementation and contract notes

## Architecture

`JevDecisionService` is the only execution path. It validates a `JevRequest` against the `JevModelCatalog`, resolves its provider, applies request/concurrency/timeout limits, calls `JevDecisionBackend`, parses the response, and validates that every answer matches the input question type, IDs, options/rubric, ranges, and probability total. The production backend is `GatewayJevDecisionBackend`, which resolves `AiDecisionAPI` from the context on every call and posts to exactly the resolved provider.

`JevMcpToolProvider` defensively parses `McpToolArgs.raw` so nested objects and arrays retain their types, and resolves `preset` through `JevPresetRepository`. `JevPlaygroundViewModel` builds the same `JevRequest` from form drafts (`JevDrafts.kt`) or JSON text. Both receive the same service instance from `JevPluginServices`, which also owns the one view model per activation so the sidebar panel can be hidden or recreated without losing drafts.

`JevValidation.requestIssues` returns every problem with a JSON path. MCP errors carry the first path; the panel maps paths onto form fields with `fieldFor`. `JevDecisionService.runs` is the in-memory run log shared by the panel and MCP, capped at 20 and cleared on unload. `JevModelCatalog` (one per activation, in `JevPluginServices`) holds the gateway's decision models as a `StateFlow`, with `typesafe/jev-1.13` on OpenRouter always present. It refreshes when the panel opens, on Refresh, on `jev_models`, and before an unknown model id is rejected. A model id with no provider is inferred when unique and is `INVALID_INPUT` at path `provider` when several providers serve it.

Plugin unload closes the service, which cancels active and queued gateway calls, and disposes the panel's view model, cancelling its run job.

## Upstream contract encoded here

- request: `{model,state,questions}`, where `model` comes from `JevModelCatalog`, sent as `AiDecisionRequest.body` to its `providerId`;
- response: optional `id`/`provider`, required `model`, `answers`, and `usage`;
- noul value and confidence/probabilities are finite numbers in `[0,1]`;
- choice answer/options must exactly match the request, and probability values sum to 1 within `max(1e-6, 5e-4 × options)`;
- score is a finite probability-weighted number from `0` through the last requested rubric index (for example `1.05`), with exact legend/probability keys (in any order) and probabilities summing to 1 within the same tolerance;
- no confidence is invented and no arbitrary decision threshold is applied.

Gateway codes map in `JevDecisionErrors`: `UNKNOWN_PROVIDER`, `MISSING_CREDENTIAL`, `LOCAL_UNAVAILABLE`, `MODEL_NOT_FOUND`, `AUTH_ERROR`, `RATE_LIMITED`, `TIMEOUT`, `NETWORK_ERROR`, `RESPONSE_TOO_LARGE` and `UPSTREAM_ERROR` keep their names; `INVALID_INPUT` becomes `UPSTREAM_INVALID_INPUT`; any other code is `UPSTREAM_ERROR`; no registered gateway is `GATEWAY_UNAVAILABLE`. Local codes are `INVALID_INPUT`, `INPUT_TOO_LARGE`, `RESPONSE_TOO_LARGE`, `MALFORMED_RESPONSE`, `TIMEOUT`, `BUSY`, `PRESET_STORAGE_ERROR`, and `SERVICE_UNAVAILABLE`.

## Compatibility choices

- compile target and minimum API: BOSS Plugin API `1.0.96`, the first with `AiDecisionAPI` (local sibling jar / CI-downloaded jar);
- AI Gateway `>=1.1.14`, the first release that registers `AiDecisionAPI`; without it the panel loads and every run is `GATEWAY_UNAVAILABLE`;
- minimum BOSS: `9.4.2`, matching existing plugins that depend on the host LLM relay and MCP contribution surface;
- Compose, Decompose, coroutines, plugin API, and kotlinx serialization are supplied by the host and omitted from the installable jar.

There is intentionally no release workflow or fabricated repository URL. Live verification remains a host/operator step because tests never use a real gateway, secret store, or local runtime, and BOSS must not be launched or restarted by this build.
