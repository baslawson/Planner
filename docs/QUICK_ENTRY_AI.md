# Quick entry with Gemini or OpenAI

Planner connects directly from your phone to the selected provider using your personal API key. No backend,
pairing or account sign-in is needed. API quota and billing belong to your provider account; OpenAI API
billing is separate from a ChatGPT subscription.

**Settings → Quick entry → Enable AI features** is the master switch. Turn it off to hide provider setup,
AI buttons and clarification prompts in both entry modes and stop/cancel AI requests. Already-sent requests
may still be processed by the provider. Saved keys and existing draft corrections remain;
reviewed draft fields can still be edited and saved offline. Turn it back on to restore the controls.
This device-local preference persists across restarts and is not changed by importing planner backups.
It starts on to preserve existing UI availability; typing still never sends an AI request automatically.

1. In **Settings → Quick entry**, turn on **Enable AI features**, then open **AI assistance**.
   If already enabled, you can also open AI settings from Quick entry.
2. Select **Gemini** or **OpenAI**, then use **Get a … API key** to open that provider's key page.
3. Paste your key. Defaults are `gemini-3.5-flash-lite` and `gpt-4.1-mini` respectively. An alternative model
   must support the provider's structured JSON output API (Gemini generateContent or OpenAI Responses).
4. Tap **Save AI settings**. Saving makes no request; there is no additional provider switch.
5. Enter a plan, tap **Understand with AI**, check/correct the preview, and explicitly tap Add.

Each provider retains its own encrypted key and model. **Enable AI features** is the only AI switch. Switching changes the active provider and
leaves the other saved profile intact. Unsaved settings edits are discarded when switching. An unconfigured
provider never falls back to another provider. The provider button beside Quick entry's AI
controls opens the same settings, and clears any pending clarification context.

Single and Multiple modes reuse the existing preview, selected-row review, conflict checks and save flow.
AI cannot save entries itself. Clarifications require explicitly tapping Send answer. Closing, cancelling,
editing input or changing modes rejects pending results. Provider/key/quota errors leave your draft
available for editing and offline saving.

Saving or replacing a key makes AI available when the master switch is on; it never sends a request. **Remove API key** deletes only the
selected provider's local key; revoke the actual key through the provider. Changing just the model retains
the key. Keys are encrypted with Android Keystore in `noBackupFilesDir`, excluded from backups, never
compiled into the APK, logged, prefilled or saved into activity state. Requests use fixed HTTPS endpoints,
header credentials, no redirects and no automatic retries. A compromised phone can still expose a key.

Only submitted text, reference date, timezone, mode/type and explicit clarification answers are sent.
Calendar contents, scans, attachments and manual corrections are not sent. Provider data policies and
billing apply. OpenAI requests set `store: false`; this is not a promise of zero provider retention.
Manage quotas/spending with the provider. Cancelling may not stop a request already received.

Existing Gemini settings and reviewed drafts remain compatible. No SDK, database or permission change.
Validation uses synthetic HTTPS responses; no live key was supplied. Live account/model access and
interpretation quality need checking with your account. Always review the editable result.

References: [Gemini structured output](https://ai.google.dev/api/generate-content),
[Gemini keys](https://ai.google.dev/gemini-api/docs/api-key),
[OpenAI Responses](https://developers.openai.com/api/docs/guides/migrate-to-responses),
[OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs),
[GPT-4.1 mini](https://developers.openai.com/api/docs/models/gpt-4.1-mini).
