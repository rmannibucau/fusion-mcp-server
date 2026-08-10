# 2026-07-28 conformance: modern core + Tasks (dual-era, strict-by-version)

// opencode -s ses_01d9756d6ffed1vK6kkQHma1uM

Status: planning. The first dual-era phase (add the stateless 2026-07-28 path: `server/discover`,
`subscriptions/listen`, MRTR `input_required`/`inputRequests`/`requestState`, `_meta` envelope, per-result
`resultType`, modern subscriptions) is implemented and green. This plan is the follow-up that brings the
modern path to strict conformance with the released `2026-07-28` specification while keeping the legacy
(`2025-03-26` / `2025-06-18` / `2025-11-25` `initialize`) path green, and adds the Tasks extension.

Reference docs (primary source of truth):
- https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning
- https://modelcontextprotocol.io/specification/2026-07-28/basic/index (messages, `_meta`, error codes)
- https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/mrtr
- https://modelcontextprotocol.io/specification/2026-07-28/server/discover
- https://modelcontextprotocol.io/extensions/tasks/overview

## Decisions (confirmed with the user)

1. **Modern core + keep legacy green** (dual-era server).
2. **Include the Tasks extension** in this pass.
3. **Strictness is version-gated**: only a true `2026-07-28` modern request gets strict spec enforcement
   (required `_meta`, `resultType`, error policy). A modern request negotiated/opened as `2025-06-18`
   (or otherwise tolerated) stays tolerant. Legacy `initialize` stays tolerant as today.
4. **Versioning**: add `2025-11-25` to the legacy `SUPPORTED_VERSIONS`; `2026-07-28` remains the sole
   modern/stateless version. Gate 2026-only behaviour on the negotiated version.
5. **Tasks shape** (released extension): `io.modelcontextprotocol/tasks` capability, `tasks/list`,
   long-running requests returning a `CreateTaskResult` (`resultType: "task"`), `tasks/get`, `tasks/update`,
   `tasks/cancel`, push via `notifications/tasks` over `subscriptions/listen`.
6. **Not in scope** (follow-ups): OpenTelemetry trace propagation (`traceparent`/`tracestate`/`baggage`),
   the HTTP Authorization framework.

## Progress

- **Phase 1 - DONE.** `SUPPORTED_VERSIONS` -> `[2025-11-25, 2025-06-18, 2025-03-26]`; `STRICT_MODERN_VERSIONS = [2026-07-28]`;
  strict required `_meta` (`protocolVersion` + `clientCapabilities`) on true 2026-07-28 requests -> `-32602`/400, tolerant
  elsewhere; `UnsupportedProtocolVersionError` (`-32022`) returns `data.supported`/`data.requested`; `MCPClient` injects
  the required `_meta` on stateless requests. Green.
- **Phase 2 - DONE.** MRTR hardening: `HmacRequestStateCodec` embeds an expiry in the signed payload and rejects
  tampered/expired `requestState`; `applyRequestMetadata` turns a decode failure into a `-32602`; typed
  `completions` capability advertised as `{"completions": {}}` (fullCompletions not claimed).
- **Phase 3 - PARTIAL.** `Icon` model created; client `Capabilities` gained an `extensions` field. `Content` gained
  `tool_use`/`tool_result` block types + factories.
- **Phase 4 - DONE (core).** Tasks extension: `io.modelcontextprotocol/tasks` advertised in `ServerCapabilities.extensions`;
  `Task`/`TaskStatus`/`CreateTaskResult`/`ListTasksResponse` models; `TaskRegistry` (durable, in-memory, TTL);
  `tasks/list`, `tasks/get`, `tasks/update`, `tasks/cancel` handlers.
- **Phase 5 - DONE (conformance pass).** Modern protocol hardening: `server/discover` returns `serverInfo` both
  top-level and in `_meta`; `subscriptions/listen` acks with `notifications/subscriptions/acknowledged` (a notification,
  not a response); the legacy-only utilities `ping`, `logging/setLevel`, `resources/subscribe` and
  `resources/unsubscribe` are rejected with `-32601` over the stateless protocol; `MCPSession.requireClientCapability`
  is public and returns `-32021` `MissingRequiredClientCapabilityError` with `data.requiredCapabilities` on stateless
  requests; `MCPClient` injects `clientInfo` in its stateless `_meta`. `conformance` module gained SEP-2575 diagnostic
  tools: `test_missing_capability`, `test_streaming_elicitation`, `test_logging_tool`, `test_trigger_tool_change`,
  `test_trigger_prompt_change`.
- Build: **reactor `mvn verify` green**, spotless clean, jacoco gates (0.99 instr / 0.93 branch) restored with new
  tests for every gate.

## Remaining

- Nothing blocking. Optional later: full `2026-07-28` conformance suite run unpinned (needs network to the npx
  tool); per-method `listChanged` string-list variants; OTel trace propagation; HTTP authorization.

## Conformance & Demo

- `demo` serves the modern stateless `2026-07-28` protocol in addition to the legacy one (proved by
  `DemoTest.statelessDiscoverServesThe2026Protocol`).
- New `conformance` module: a runnable server (`mvn package -pl conformance exec:exec` -> `http://localhost:8080/mcp`)
  exposing a representative MCP surface (tools, prompt, resources + templates, completions, a long-running
  `io.modelcontextprotocol/tasks` tool), validated by smoke tests over both `2026-07-28` (strict `_meta`, `server/discover`,
  `tools/list`/`tools/call`, `400` on missing `clientCapabilities`) and the `2025-11-25` `initialize` handshake.
- Docs: `documentation/.../deployment/conformance.adoc` explains how to run
  `npx @modelcontextprotocol/conformance server --url http://localhost:8080/mcp --spec-version 2026-07-28 --suite all --verbose`
  (and `--spec-version 2025-11-25`), wired into the minisite nav.

## Phase 1 - Versioning, required `_meta`, error policy

Files: `MCPProtocol`, `MCPEndpoint`, `MCPJSONRPCProtocol`, `MCPClient`.

- `SUPPORTED_VERSIONS` -> `[2025-06-18, 2025-11-25]`; `STATELESS_VERSIONS` stays `[2026-07-28]`.
  Verify a `2025-11-25` client negotiates cleanly (`negotiate()` is legacy-only; modern has no handshake).
- **Strict required `_meta` (2026-07-28 modern only):** a modern request must carry
  `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities`, else
  JSON-RPC `-32602` + HTTP `400`. Honor `clientInfo` when present. This is gated on the actual modern
  version being `2026-07-28` (not on a tolerated legacy-modern request).
- **UnsupportedProtocolVersionError:** unsupported modern version -> `-32022` with
  `data: { "supported": [...], "requested": "..." }` instead of a bare 400. Tighten
  `MCPEndpoint.validateMetaVersion`.
- **Error-code alignment (2026-07-28 modern):** resource-not-found -> `-32602`, not `-32002`
  (which is retired on this version). Legacy keeps returning/accepting `-32002`.
- **Version gate:** 2026-only behaviours (strict `_meta`, `resultType` mandatory, error policy,
  `extensions`) keyed on the per-request modern protocol version.

## Phase 2 - MRTR hardening and capability model

Files: `MCPSession`, `MCPResult`, `InputRequest`, `MCPRequestStateCodec` + `HmacRequestStateCodec`,
`MCPJSONRPCProtocol`, `InitializeResponse`, `ServerCapabilities`.

- `requestState` integrity: extend `HmacRequestStateCodec` to bind the server state to
  (a) principal, (b) short TTL, (c) a digest of the originating request (method + salient params).
  On receipt, verify and reject tampered / expired / cross-user / foreign-request state (MRTR spec
  server requirements #4-#6). Do not rely on the unsigned opaque codec for security-sensitive state.
- Restrict `InputRequiredResult` to `prompts/get`, `resources/read`, `tools/call`. Enforce:
  at least one of `inputRequests` or `requestState`; never include `inputRequests` the client did not
  declare; support the bare-`requestState` retry (client retries the same request immediately).
- Typed `completions` capability `{ "completions": {}, "fullCompletions": {} }`; add full-completion
  handling. Add `elicitation` to `InitializeResponse.Capabilities` (legacy `initialize`).
- String-list `listChanged` variants on tools / prompts / resources for the modern path
  (`boolean | string[]` such as `["resource_templates","prompts","tools"]`).

## Phase 3 - Icons, content blocks

Files: models (`Icon`, `Implementation`, `Tool`, `Prompt`, `Resource`, `Content`, `PromptResponse`).

- `Icon` model (`src`, `mimeType`, `sizes`, `theme`); attach to `Implementation`, `Tool`, `Prompt`,
  `Resource`. Enforce HTTPS / data URI and the icon security policy (same-origin, allowlisted MIME:
  PNG/JPEG required, SVG/WebP with care).
- `Content`: add `tool_use` and `tool_result` block types. `PromptResponse.Message.content` may be a
  single block or an array of blocks.

## Phase 4 - Tasks extension

Files: models (`Task`, `CreateTaskResult`, `TaskStatus`, `TasksUpdateParams`, ...), `MCPJSONRPCProtocol`
(handlers), `MCPSessions`/registry, `MCPNotifier`.

- Negotiate `io.modelcontextprotocol/tasks` in `clientCapabilities.extensions` and
  `ServerCapabilities.extensions`.
- Endpoints: `tasks/list`, `tasks/create` (returns `CreateTaskResult`), `tasks/get`, `tasks/update`,
  `tasks/cancel`. Statuses `working`, `input_required`, `completed`, `failed`, `cancelled`.
- `CreateTaskResult` uses `resultType: "task"` and carries `taskId`, `status`, `ttlMs`, `pollIntervalMs`;
  terminal states carry `result` (completed) or `error` (failed).
- Durable in-memory `Task` registry keyed by `taskId`, with TTL eviction.
- A long-running request may return a task handle instead of blocking (server-directed), but **only**
  when the client declared the extension.
- Push `notifications/tasks` on the existing `subscriptions/listen` stream; polling remains the default.

## Phase 5 - Tests and build gate

- Rework existing modern tests for strict/versioned `_meta`, `resultType`, and error-code rules. Legacy
  tests must stay untouched and green.
- Add coverage for every new path: `_meta` validation, `UnsupportedProtocolVersionError`, `-32602`
  resource lookup, MRTR verify / restrict / bare-`requestState`, typed completions, string-list
  listChanged, icons, `tool_use`/`tool_result` content, extensions negotiation, and the full Tasks
  lifecycle.
- Keep jacoco (0.99 instruction / 0.96 branch), run `mvn spotless:apply`, `mvn -pl mcp-server -am verify`,
  and the full reactor `mvn verify`.

## Out of scope (possible later passes)

- OpenTelemetry trace context propagation.
- HTTP Authorization framework.
