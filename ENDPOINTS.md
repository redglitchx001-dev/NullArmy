# Endpoints — add as many AI models as you want

**Where do I add endpoints? How many? How do I wire in a model id and an API key?**

Short answer: **`config.yml`, under `ai.endpoints`.** Each entry has two required connection fields,
an optional API key, and optional controls. You can add as many as you want — there is no hard-coded
provider list.

---

## You do not need any of this

**Endpoints are optional.** Implemented local behaviors have deterministic offline paths. The full
471-mechanic catalogue is not complete, and current working-tree edits still require a build; see
`STATUS.md` before treating any feature as verified.

- ✅ Building uses deterministic local plans and server-side validation; no endpoint receives build goals or authors construction plans.
- ✅ OpenAI Chat Completions-compatible endpoints can be configured for the supported AI-backed roles, including Commander conversation and typed squad advice.
- ✅ `/null ai test [id]` can check an endpoint's connectivity without granting the model gameplay authority.
- ✅ `/null ai endpoints` reports registered/skipped entries, key resolution, and the effective ChatCommander chain.

The plugin sends `POST <endpoint>/chat/completions` with Bearer authentication when a key resolves.
Configure either a base URL (for example, `https://host.example/v1`) or the complete
`/chat/completions` URL. A provider's native API is not automatically compatible just because it
has a model id: native Anthropic `/v1/messages` is not this wire format. Use an OpenAI-compatible
provider endpoint, an official OpenAI-compatibility route, or a gateway such as OpenRouter.
- ✅ The Commander has 25 planning-library entries; the current live combat loop executes only supported mace weapon choices.

Run `/null features` to see the exact breakdown on your server.

### What can use a model

| Optional role | Offline behavior |
| --- | --- |
| Commander/free-form chat | Bounded local replies or a clear disabled response |
| Typed squad advice or other configured role | Deterministic local logic remains authoritative; unavailable or unsafe model output is rejected |
| Endpoint connectivity test | No model-backed behavior is enabled by the test itself |

**Building is not a model-backed role.** The legacy `BuilderAgent` key is retained for compatibility
and connectivity checks only; no build request is sent to it. Other features not yet implemented
remain unavailable regardless of configured endpoints.

> **A model never has authority regardless.** The local validator sits above every model
> decision, and no endpoint can ban, kick or mute a player.

---

## The whole thing in six lines

```yaml
ai:
  enabled: true
  endpoints:
    my-model:                                  # any name you like, unique in this list
      endpoint: "https://api.example.com/v1"   # base URL — http:// or https://
      model-id: "example-flash"                # the model id sent with every request
      api-key:  "env:MY_API_KEY"               # the key — env:VARNAME recommended
      enabled: true
```

**To add another model, copy that block, rename it, change the three fields. Repeat as many
times as you want. There is no limit.**

```yaml
    my-model:
      endpoint: "https://api.example.com/v1"
      model-id: "example-flash"
      api-key:  "env:MY_API_KEY"
      enabled: true

    another-one:
      endpoint: "https://api.other.com/v1"
      model-id: "other-model-large"
      api-key:  "env:OTHER_API_KEY"
      enabled: true

    fast-local:
      endpoint: "http://127.0.0.1:11434/v1"
      model-id: "llama3"
      api-key:  ""
      enabled: true
```

---

## 1. Endpoint reference

### Connection fields

| Field | Required | What it is |
| --- | --- | --- |
| `endpoint` | ✅ | Absolute `http://` or `https://` URL for an OpenAI Chat Completions-compatible API. It may be a base URL or end in `/chat/completions`; a base path and query are preserved. Fragments are ignored. |
| `model-id` | ✅ | The model identifier sent with each request — for example, `gpt-4o-mini`, `llama3`, or `gemini-2.0-flash` when using a compatible route. |
| `api-key` | optional | A literal key, `env:VARNAME`, or `""` for a keyless local service. A non-empty resolved key is sent as `Authorization: Bearer …`; no Authorization header is sent when no key is configured. |

Malformed endpoint entries are skipped individually and reported in the startup log and
`/null ai endpoints`; they do not discard unrelated settings or other valid endpoints. Legacy
`base-url`, `model`, `api-key-env`, and `auth-key-env` spellings are still read; the latter two
accept a bare environment-variable name (or `env:NAME`). New configs should use the fields above.

### Optional controls

| Field | Default | What it does |
| --- | --- | --- |
| `enabled` | `true` for an entry where omitted | Per-endpoint switch. The shipped sample entries explicitly use `false`; enable one you have configured. Disabled endpoints are skipped. |
| `timeout-millis` | `ai.defaults.timeout-millis` (shipped default `3000`) | Per-request timeout, clamped to `500..120000` ms. On failure, the next configured ChatCommander endpoint can be tried. |
| `calls-per-minute` | `ai.defaults.calls-per-minute` (shipped default `20`) | Endpoint request budget. |
| `max-json-bytes` | `ai.defaults.max-json-bytes` (shipped default `8192`) | Reject a response larger than this configured limit (clamped to `256..10000000` bytes). |
| `max-retries` | `2` | Number of retries for transient network/HTTP failures before trying the next endpoint. |

### The `api-key` field

**✅ Recommended — `env:VARNAME`**

```yaml
api-key: "env:OPENAI_API_KEY"
```

Only the **name** of an environment variable is stored. The real key is read from the server
process environment at the moment it is needed, so it is never in `config.yml`, the jar, git, a
backup, a paste, or the log.

```bash
# Linux / macOS
export OPENAI_API_KEY="sk-your-real-key"
java -jar paper.jar
```

```bat
:: Windows
set OPENAI_API_KEY=sk-your-real-key
java -jar paper.jar
```

**⚠️ Works, but not recommended — the literal key**

```yaml
api-key: "sk-jeurjwiejbfbf"
```

This works. Be aware the key is **plain text on disk**, and can leak through a config paste, a
screenshot, a support ticket or an accidental `git commit`. A startup warning is logged, and
command diagnostics report only that an inline key is set; they never print its value.

**No key at all**

```yaml
api-key: ""
```

Correct for local models (Ollama, llama.cpp, LM Studio) that need no authentication.

---

## 2. How many endpoints can I add?

**As many as you want. There is no limit and no fixed list.**

Adding more isn't only for redundancy — it's how you make the plugin feel faster for players and
for the Commander. ChatCommander tries enabled endpoints in the configured order. After a provider
fails its bounded transient retries, the next endpoint is used. The current HTTP client does not
maintain a circuit-breaker state; each new request starts at the primary endpoint again.

### Five setups that make sense

**1. Nothing at all** (the default) — fully offline, everything works.
```yaml
ai:
  enabled: false
```

**2. One cloud model**
```yaml
ai:
  enabled: true
  endpoints:
    openai:
      endpoint: "https://api.openai.com/v1"
      model-id: "gpt-4o-mini"
      api-key: "env:OPENAI_API_KEY"
      enabled: true
```

**3. Local only** — zero cost, nothing leaves your machine
```yaml
ai:
  enabled: true
  endpoints:
    ollama-local:
      endpoint: "http://127.0.0.1:11434/v1"
      model-id: "llama3"
      api-key: ""
      enabled: true
```

**4. Fast local first, cloud as backup**
```yaml
ai:
  enabled: true
  endpoints:
    ollama-local:
      endpoint: "http://127.0.0.1:11434/v1"
      model-id: "llama3"
      api-key: ""
      enabled: true
    openai:
      endpoint: "https://api.openai.com/v1"
      model-id: "gpt-4o-mini"
      api-key: "env:OPENAI_API_KEY"
      enabled: true
```

**5. A big pile of models** — add ten, add fifty. Each is another thing that has to fail before
the Commander falls back to local logic.

### Which endpoint gets used?

- With `ai.endpoints` defined and no per-role override, roles use `ai.default-endpoint`, or the
  **first enabled endpoint** in YAML order when the setting is empty, missing, or points to a
  missing/disabled entry.
- ChatCommander uses its primary endpoint followed by the configured `fallbacks`. It tries a
  usable endpoint, then advances on network/HTTP/response failure; transient network errors and
  HTTP 408/425/429/5xx get the configured bounded retries first.
- Pin it explicitly:
  ```yaml
  ai:
    default-endpoint: "ollama-local"
  ```
- Disabled, unreachable or rate-limited endpoints are skipped in favour of the next. There is no
  circuit-breaker memory across separate chat requests.
- If **no** endpoint answers, the Commander uses its bounded local replies or reports that it
  cannot answer; typed squad actions still pass through local validation. **The server tick is never
  blocked on a network call.**

---

## 3. Optional: per-role routing

**You can skip this entirely.** Without a role override, the selected default endpoint is tried
first and every other enabled endpoint is used as a fallback in YAML order. Use this section only
if you want, say, chat on a cloud model while another role uses a fast local model.

```yaml
ai:
  enabled: true
  default-endpoint: "ollama-local"
  agents:
    CombatTactician:
      endpoint: "ollama-local"
      fallbacks: ["openai"]
      enabled: true
    ChatCommander:
      endpoint: "openai"
      fallbacks: ["gemini"]
      enabled: true
```

Valid role names include `ChatCommander`, `CombatTactician`, `PathfinderCore`, `ScoutObserver`,
`ThreatAnalyst`, `LogisticsQuartermaster`, `MedicTriage`, `FormationTactician`, `RedstoneAnalyst`,
`MiningForeman`, `IdleBehaviourDirector`, and `GuardianAuditor`. The legacy `BuilderAgent` key is
accepted for compatibility/connectivity tests only; no build goals or plans are sent to it.

When you add an explicit role entry, its `endpoint` and `fallbacks` replace the automatic default
chain for that role. Include the fallback ids you want; they are tried in order. An unknown role
name is logged as a warning with the valid list — never silently ignored.

---

## 4. Authority model

```
   player / world state
          |
          v
   +--------------+      advice only      +------------------+
   |  AI model    | --------------------> | LOCAL VALIDATOR  |
   +--------------+                       +------------------+
                                                  |
                                                  v
                                            the world
```

A model **advises**. The server owns inventory, movement, damage, blocks, permissions and every
final call. A model response is untrusted input: schema-validated, size-capped, clamped, expiry-
checked, and rejected if it is malformed, stale, out-of-range or unsafe.

There is deliberately **no moderation role** — no endpoint can ban, kick or mute a player.

---

## 5. Reliability

| Failure | Behaviour |
| --- | --- |
| Endpoint disabled | Skipped; the next configured endpoint is considered |
| Key env var not set | Reported as `missing`/`usable=false` and skipped by ChatCommander |
| Timeout / network error | Up to `max-retries` retries (bounded to five) with short asynchronous backoff, then the next endpoint |
| HTTP 408, 425, 429 or 5xx | Same bounded retry policy, then the next endpoint |
| Other non-2xx status, malformed or oversized reply | Rejected; the next endpoint is tried without logging the response body or secret URL query values |
| Endpoint request budget exhausted | That endpoint is skipped and the next fallback is considered |
| Global chat budget exhausted | No provider request is sent; the Commander reports the local rate limit instead |
| Everything down | Commander gives an honest local response; typed squad actions remain locally validated and cannot gain extra authority |

---

## 6. Operations

| Command | Permission | Purpose |
| --- | --- | --- |
| `/null ai` | `nullarmy.admin` | Registered, skipped, enabled and usable counts; Commander readiness |
| `/null ai endpoints` | `nullarmy.admin` | Effective ChatCommander chain, per-endpoint status, key-resolution state and connectivity-test target |
| `/null ai test [id]` | `nullarmy.admin` | Send one connectivity probe to `ai.builder.endpoint`, the effective ChatCommander chain, or a named endpoint |
| `/null features` | `nullarmy.admin` | What works now, and what an AI model would add |
| `/null status` | `nullarmy.admin` | Adapter, live Null count and policy flags |

Endpoint diagnostics show only whether a key is set/resolved (and the environment-variable name);
they never print the key value. URL query values and URL user-info are also redacted.

---

## 7. FAQ

**Do I need any endpoints at all?**
No. See [You do not need any of this](#you-do-not-need-any-of-this) — local mechanics and
construction do not depend on an endpoint; only supported conversation/advice uses one.

**Can I mix providers?**
Yes — anything speaking the OpenAI chat-completions shape. Different providers, different keys,
same list.

**Does a slow model lag my server?**
No. Calls are asynchronous, time-bounded and rate-limited; the server tick never waits.

**What if my key is wrong or the env var isn't set?**
That endpoint is reported `resolves=false` and skipped. Nothing crashes; you fall back to the
next endpoint, then to local logic.

**Can I put the key straight in config.yml?**
Yes, but you'll get a startup warning — see [the `api-key` field](#the-api-key-field).

**Where's the old `base-url` / `model` / `auth-key-env` naming?**
Still accepted, so existing configs keep working. New configs should use
`endpoint` / `model-id` / `api-key`.

---

**Copyright (c) RedGlitchX. All rights reserved.**
