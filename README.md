# Jev for BOSS

Jev is a dynamic BOSS plugin with two surfaces backed by one validation and execution service:

- a native Compose **sidebar panel** (right sidebar, below the top group);
- MCP tools on BOSS's existing MCP server: `jev_decide`, `jev_validate`, `jev_presets`, and `jev_models`, plus `jev_draft_*`, `jev_preset_save`, and `jev_compose` for the live panel draft.

Every decision goes through the AI Gateway's `AiDecisionAPI` (boss-plugin-api 1.0.96, AI Gateway 1.1.14). The plugin never calls a model API or reads a credential itself. The model catalog comes from the gateway's `decisionProviders()`: `typesafe/jev-1.13` on OpenRouter, which is always listed and is the default, and the models a local runtime such as Ollaya has pulled (`laya:en`, `laya:multilingual`). A call goes only to the chosen model's provider. Neither Jev nor the gateway falls back to another provider, so state sent to a local model never leaves the machine. OpenRouter needs a key in **Secret Manager → AI Providers**; a local model needs none and costs nothing. The Describe composer uses a chat model, also through the AI Gateway, and never lists decision models.

## Panel

The panel is conversation-first. **Chat** is where you describe the decision and see what changed. **Draft** is the full editor for context and questions. **Answer** shows the full result of a run.

- **At every width:** below 640dp, Chat, Draft and Answer are tabs. At 640dp and wider, Chat is on the left, and the Draft or the latest answer is on the right. Below 380dp, rows stack and labels shorten.
- **Header:** the preset title opens saved presets, starters, New blank, and Save as. A dot marks unsaved changes, and ⌘S saves. The model chip on the right shows which model will answer and whether it can. Its menu groups models by provider, marks local ones "on this machine", and disables unreachable ones under their provider's hint (the missing OpenRouter key, or `ollaya serve`). It also has Refresh models and a link to AI Providers. Opening the panel refreshes the catalog.
- **Chat:** an empty panel asks "What do you want to decide?" and offers three example prompts and the templates. Each message goes to a chat model from the AI Gateway, which writes or revises the draft. Each turn shows a one-line reply and the exact changes, computed locally (`+ cost (Score)`, `~ route: options +billing −support`, `− urgent`, `context: 2 fields added`). It also offers up to three suggested follow-ups, and the latest turn has Undo. While drafting, a card shows the model, the stage ("Drafting", then "Fixing 2 issues" during the single repair call), the elapsed seconds and Cancel. Each model call stops at 60 s, and a failure shows Retry. Manual and MCP edits add one "edited the draft" line. A run adds a compact answer card with "Open full answer", and the next turn gets a summary of that run. The composer and Run are pinned at the bottom: Enter sends, Shift+Enter adds a line, ⌘↵ runs. The drafting model is chosen under the composer, remembered, and never defaults to `openrouter/free`.
- **Placeholders:** `<customer name>` style values in the context are listed in a "Fill before running" form. Run reads "Fill 2 fields" and stays disabled until they are filled. Over MCP they appear as issues at their `state.` paths.
- **Context:** the format is detected from the text (JSON object, JSON array, or plain text). Text that looks like JSON but doesn't parse offers "Send as text".
- **Questions:** typed cards for Yes/No (`noul`), Choice, and Score. Switching type keeps what was typed. A Form/JSON toggle syncs both ways. JSON that the form can't show faithfully, such as structured instructions, stays in JSON.
- **Validation:** runs on every edit. Each issue appears under the field that caused it. The Run bar counts the issues, and clicking the count scrolls to the first one.
- **Run bar:** Run (⌘↵) and a timeout picker, pinned under the composer. Copy as `jev_decide` arguments is in the Draft header, next to the question chips and the validation status. With an OpenRouter model and no key, Run becomes "Connect OpenRouter". With an unreachable local model, it becomes Refresh, and the Answer pane shows the provider's hint.
- **Answers:** each card leads with the verdict, then shows the probabilities. Only the winning bar uses the accent color. A chip shows the change since the previous run from the same source, and a flipped verdict is amber.
- **Run history:** a strip of the last 20 runs from the panel and from MCP, with MCP runs tagged. An MCP run can be loaded into the editor. A panel run whose inputs have been edited since offers Restore.

Named presets contain context, questions, detected format, timeout, model, and the model's provider. Presets saved before model choice load with the default model. Run history is shared by the panel and MCP. It stays in memory for the plugin activation and is never persisted. The panel's drafts belong to the plugin activation, so hiding or recreating the sidebar panel keeps them.

## MCP tools

`jev_decide` takes:

- `state`: string, object, or array;
- `questions`: a map of question IDs to `noul`, `choice`, or `score` definitions, **or** `preset`: the name of a rubric saved in the panel;
- optional `model`: an ID from `jev_models` (default `typesafe/jev-1.13`);
- optional `provider`: needed only when two providers serve the same model ID; otherwise it is inferred;
- optional `timeout_ms`: 1,000–120,000 (default 30,000, or the preset's timeout).

It returns JSON text containing `response`, `provider`, `local`, and `latency_ms`. Failures return `isError: true` with a stable code, a safe message, and, where one field is at fault, a `path` such as `questions.route.criteria`. The tool is marked `readOnly: false` because an OpenRouter call has a cost, even though it never executes a selected action. Gateway failures keep the gateway's code (`MISSING_CREDENTIAL`, `LOCAL_UNAVAILABLE`, `MODEL_NOT_FOUND`, `AUTH_ERROR`, `RATE_LIMITED`, `TIMEOUT`, `NETWORK_ERROR`, `RESPONSE_TOO_LARGE`, `UPSTREAM_ERROR`); its `INVALID_INPUT` becomes `UPSTREAM_INVALID_INPUT`, and no gateway is `GATEWAY_UNAVAILABLE`.

`jev_validate` takes the same arguments, runs only the local checks, and returns `valid` plus every issue with its path. It never calls a model and is read-only.

`jev_presets` lists saved presets with their questions, model, provider, and timeout. It never returns saved context text.

`jev_models` refreshes the catalog and lists each model's `id`, `provider`, `local`, `reachable`, and `detail`, plus each provider's status.

These tools act on the draft open in the panel, and each change shows there immediately. They work while the panel is closed.

- `jev_draft_get` (read-only) returns the context (`state`), questions, model, timeout, every issue with its path, and a summary of the last run.
- `jev_draft_set` sets any of `state`, `questions`, `model`, `provider`, and `timeout_ms`. `mode: "replace"` (the default) swaps the questions. `mode: "merge"` upserts them by ID, and `remove_questions` deletes IDs. It validates and never runs.
- `jev_draft_run` runs the draft exactly as the Run button does, so the answer appears in the Answer pane and the history. It is a paid call on an OpenRouter model and free on a local one.
- `jev_preset_save` saves the draft, or only the given `questions` with no context, as a named preset.
- `jev_compose` runs the Describe composer with a `message` and returns the new draft.

A typical agent loop is `jev_draft_set`, then fix the issues it reports, then `jev_draft_run`, then refine.

## Local safety limits

These are plugin limits, not claims about provider limits:

- 256 KiB serialized request;
- 1 MiB response, passed to the gateway and checked again on the reply;
- 32 questions per request;
- 4 active calls plus at most 16 admitted waiting calls per plugin activation (`BUSY` beyond that cap);
- 1–120 second timeout;
- no automatic paid retries.

Cancellation and unload cancel the gateway call. Only an `AiDecisionException` message is shown, bounded to 240 characters; other failures show a generic message.

## Build and test

```bash
./gradlew test
./gradlew buildPluginJar
```

The installable artifact is `build/libs/boss-plugin-jev-0.2.0.jar`. The plugin API and host-provided serialization runtime are compile-only and are not bundled.

No gateway, key, or local runtime is required by the tests. Decision behavior uses a fake backend and a fake `AiDecisionAPI`. Recorded Ollaya 0.7.5 responses in `src/test/resources/ollaya/` must pass response validation.

## Visual check

`JevVisualRenderTest` renders the real panel with BOSS theming at 280, 360, and 520dp (Ask and Answer), at 800 and 1440dp (two panes), and in an invalid-input state. The images are written to `build/reports/visual/`.
