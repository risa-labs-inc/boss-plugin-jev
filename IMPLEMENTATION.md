# Implementation and contract notes

## Architecture

`JevDecisionService` is the only execution path. It validates a `JevRequest`, resolves the OpenRouter key at call time, applies request/concurrency/timeout limits, calls `JevTransport`, parses the response, and validates that every answer matches the input question type, IDs, options/rubric, ranges, and probability total.

`JevMcpToolProvider` defensively parses `McpToolArgs.raw` so nested objects and arrays retain their types, and resolves `preset` through `JevPresetRepository`. `JevPlaygroundViewModel` builds the same `JevRequest` from form drafts (`JevDrafts.kt`) or JSON text. Both receive the same service instance from `JevPluginServices`, which also owns the one view model per activation so the sidebar panel can be hidden or recreated without losing drafts.

`JevValidation.requestIssues` returns every problem with a JSON path. MCP errors carry the first path; the panel maps paths onto form fields with `fieldFor`. `JevDecisionService.runs` is the in-memory run log shared by the panel and MCP, capped at 20 and cleared on unload. `JevModelCatalog` lists selectable models; a request's `model` must be in it.

The production transport uses Java 17 `HttpClient.sendAsync`, no redirects, a fixed endpoint, a streaming size-limiting subscriber, and cancellation propagation to `CompletableFuture.cancel(true)`. Plugin unload calls `cancelAll` and disposes the panel's view model, cancelling its run job.

## Upstream contract encoded here

- request: `{model,state,questions}`, where `model` comes from `JevModelCatalog`;
- response: optional `id`/`provider`, required `model`, `answers`, and `usage`;
- noul value and confidence/probabilities are finite numbers in `[0,1]`;
- choice answer/options must exactly match the request, and probability values sum to 1 within `1e-6`;
- score is a finite probability-weighted number from `0` through the last requested rubric index (for example `1.05`), with exact legend/probability keys and probabilities summing to 1 within `1e-6`;
- no confidence is invented and no arbitrary decision threshold is applied.

Status mapping is stable: `AUTH_ERROR` for 401/403, `UPSTREAM_INVALID_INPUT` for 422, `RATE_LIMITED` for 429, `UPSTREAM_ERROR` for 5xx/other upstream failures, plus local `INVALID_INPUT`, `INPUT_TOO_LARGE`, `RESPONSE_TOO_LARGE`, `MALFORMED_RESPONSE`, `TIMEOUT`, `NETWORK_ERROR`, `BUSY`, `MISSING_OPENROUTER_KEY`, `PRESET_STORAGE_ERROR`, and `SERVICE_UNAVAILABLE`.

## Compatibility choices

- compile target: BOSS Plugin API `1.0.87` (local sibling jar / CI-downloaded jar);
- minimum API: `1.0.68`, which includes the `NewTabSpec` launcher and required LLM provider types;
- minimum BOSS: `9.4.2`, matching existing plugins that depend on the host LLM relay and MCP contribution surface;
- Compose, Decompose, coroutines, plugin API, and kotlinx serialization are supplied by the host and omitted from the installable jar.

There is intentionally no release workflow or fabricated repository URL. Live verification remains a host/operator step because tests never inspect or use the real secret store and BOSS must not be launched or restarted by this build.
