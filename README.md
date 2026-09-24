# Jev for BOSS

Jev is a dynamic BOSS plugin with two surfaces backed by one validation and execution service:

- a native Compose **sidebar panel** (right sidebar, below the top group);
- MCP tools on BOSS's existing MCP server: `jev_decide`, `jev_validate`, and `jev_presets`, plus `jev_draft_*`, `jev_preset_save`, and `jev_compose` for the live panel draft.

Calls go to OpenRouter's SystemOne endpoint with the model chosen from `JevModelCatalog`. Today the catalog holds one model, `typesafe/jev-1.13`. Local and other decision models are meant to join it once the service can route to their backends. The plugin resolves the user's `OPENROUTER` credential lazily from **Secret Manager → AI Providers** for each run. BOSS exposes that connection only after the user also selects any OpenRouter model there. Decisions never use the active chat model, the AI chat gateway, a configurable endpoint, or any secret file. Only the Describe composer uses a chat model, through the AI Gateway.

## Panel

The panel has two parts: **Ask** holds the context and questions, and **Answer** shows the results.

- **At every width:** below 640dp the two parts are tabs. At 640dp and wider they sit side by side. Below 380dp rows stack and labels shorten. On very wide windows the content is capped at a readable width.
- **Header:** the preset title opens saved presets, starters, New blank, and Save as. A dot marks unsaved changes, and ⌘S saves. The model chip on the right shows which model will answer and whether the OpenRouter key is found. It also links to AI Providers.
- **Describe:** type what to decide in plain language, and a chat model from the AI Gateway writes the context and questions. Later messages revise the current draft. Each turn shows your message and a short reply, and one undo reverts the last turn. The next turn after a run includes a summary of that run, so "why did it pick X" works. Running stays manual. The drafting model is picked next to the label, remembered in plugin storage, and never defaults to `openrouter/free`. The composer validates its draft and makes at most one repair call.
- **Context:** the format is detected from the text (JSON object, JSON array, or plain text). Text that looks like JSON but doesn't parse offers "Send as text".
- **Questions:** typed cards for Yes/No (`noul`), Choice, and Score. Switching type keeps what was typed. A Form/JSON toggle syncs both ways. JSON that the form can't show faithfully, such as structured instructions, stays in JSON.
- **Validation:** runs on every edit. Each issue appears under the field that caused it. The Run bar counts the issues, and clicking the count scrolls to the first one.
- **Run bar:** Run (⌘↵), a timeout picker, and Copy as `jev_decide` arguments. Without a key, Run becomes "Connect OpenRouter".
- **Answers:** each card leads with the verdict, then shows the probabilities. Only the winning bar uses the accent color. A chip shows the change since the previous run from the same source, and a flipped verdict is amber.
- **Run history:** a strip of the last 20 runs from the panel and from MCP, with MCP runs tagged. An MCP run can be loaded into the editor. A panel run whose inputs have been edited since offers Restore.

Named presets contain context, questions, detected format, timeout, and model. Presets saved before model choice load with the default model. Run history is shared by the panel and MCP. It stays in memory for the plugin activation and is never persisted. The panel's drafts belong to the plugin activation, so hiding or recreating the sidebar panel keeps them.

## MCP tools

`jev_decide` takes:

- `state`: string, object, or array;
- `questions`: a map of question IDs to `noul`, `choice`, or `score` definitions, **or** `preset`: the name of a rubric saved in the panel;
- optional `model`: an ID from the model catalog (default `typesafe/jev-1.13`);
- optional `timeout_ms`: 1,000–120,000 (default 30,000, or the preset's timeout).

It returns JSON text containing `response` and `latency_ms`. Failures return `isError: true` with a stable code, a safe message, and, where one field is at fault, a `path` such as `questions.route.criteria`. The tool is marked `readOnly: false` because a call can incur provider cost, even though it never executes a selected action.

`jev_validate` takes the same arguments, runs only the local checks, and returns `valid` plus every issue with its path. It never calls OpenRouter and is read-only.

`jev_presets` lists saved presets with their questions, model, and timeout. It never returns saved context text.

These tools act on the draft open in the panel, and each change shows there immediately. They work while the panel is closed.

- `jev_draft_get` (read-only) returns the context (`state`), questions, model, timeout, every issue with its path, and a summary of the last run.
- `jev_draft_set` sets any of `state`, `questions`, `model`, and `timeout_ms`. `mode: "replace"` (the default) swaps the questions. `mode: "merge"` upserts them by ID, and `remove_questions` deletes IDs. It validates and never runs.
- `jev_draft_run` runs the draft exactly as the Run button does, so the answer appears in the Answer pane and the history. It is a paid call.
- `jev_preset_save` saves the draft, or only the given `questions` with no context, as a named preset.
- `jev_compose` runs the Describe composer with a `message` and returns the new draft.

A typical agent loop is `jev_draft_set`, then fix the issues it reports, then `jev_draft_run`, then refine.

## Local safety limits

These are plugin limits, not claims about provider limits:

- 256 KiB serialized request;
- 1 MiB response, enforced while receiving;
- 32 questions per request;
- 4 active calls plus at most 16 admitted waiting calls per plugin activation (`BUSY` beyond that cap);
- 1–120 second timeout;
- no automatic paid retries.

Cancellation cancels the underlying Java HTTP future. Redirects are disabled and credentials are sent only to the fixed `https://openrouter.ai/api/v1/systemone` endpoint. Upstream error bodies are not returned or logged.

## Build and test

```bash
./gradlew test
./gradlew buildPluginJar
```

The installable artifact is `build/libs/boss-plugin-jev-0.2.0.jar`. The plugin API and host-provided serialization runtime are compile-only and are not bundled.

No real API key is required by the tests. HTTP behavior is exercised against an in-process loopback server and decision behavior uses a fake transport.

## Visual check

`JevVisualRenderTest` renders the real panel with BOSS theming at 280, 360, and 520dp (Ask and Answer), at 800 and 1440dp (two panes), and in an invalid-input state. The images are written to `build/reports/visual/`.
