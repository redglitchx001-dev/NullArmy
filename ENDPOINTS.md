# Endpoints — add as many AI models as you want

**Where do I add endpoints? How many? How do I wire in a model id and an API key?**

Short answer: **`config.yml`, under `ai.endpoints`.** Each entry is three lines, and you can add
as many as you want — there is no limit.

---

## You do not need any of this

**Endpoints are optional.** Implemented local behaviors have deterministic offline paths. The full
471-mechanic catalogue is not complete, and current working-tree edits still require a build; see
`STATUS.md` before treating any feature as verified.

- ✅ Building uses deterministic local plans and server-side validation; no endpoint receives build goals or authors construction plans.
- ✅ An endpoint may be configured for supported AI-enabled roles such as Commander conversation or typed squad advice.
- ✅ `/null ai test` can check endpoint connectivity without granting the model gameplay authority.
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

### The three fields

| Field | Required | What it is |
| --- | --- | --- |
| `endpoint` | ✅ | Base URL of an OpenAI-compatible API. **Must** start with `http://` or `https://`, or startup fails with a clear message. |
| `model-id` | ✅ | The model identifier sent with each request — `gpt-4o-mini`, `llama3`, `claude-3-5-sonnet-latest`, `gemini-2.0-flash`, whatever your provider calls it. |
| `api-key` | optional | Your key. Two forms — see below. Leave it as `""` for local models that need no auth. |

### Optional extra fields

| Field | Default | What it does |
| --- | --- | --- |
| `enabled` | `false` | Per-endpoint switch. Disabled endpoints are skipped. |
| `timeout-millis` | `3000` | Give up and try the next endpoint after this long. |
| `calls-per-minute` | `20` | Rate limit per endpoint. |
| `max-json-bytes` | `8192` | Reject any response larger than this. |
| `max-retries` | `2` | Bounded retries with backoff, then move on. |

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
diagnostics only ever print the first three characters.

**No key at all**

```yaml
api-key: ""
```

Correct for local models (Ollama, llama.cpp, LM Studio) that need no authentication.

---

## 2. How many endpoints can I add?

**As many as you want. There is no limit and no fixed list.**

Adding more isn't only for redundancy — it's how you make the plugin feel faster for players and
for the Commander. Endpoints are tried in the order you list them: when one is slow, rate-limited,
down, or its circuit breaker is open, the next is used instead.

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

- With only `ai.endpoints` defined (the normal case), every decision uses `ai.default-endpoint`,
  or if that's empty, the **first enabled endpoint** in your list.
- Pin it explicitly:
  ```yaml
  ai:
    default-endpoint: "ollama-local"
  ```
- Disabled, unreachable, rate-limited or open-circuit endpoints are skipped in favour of the next.
- If **no** endpoint answers, the plugin silently uses local deterministic logic. **The server
  tick is never blocked on a network call.**

---

## 3. Optional: per-role routing

**You can skip this entirely.** It exists only if you want, say, combat decisions on a fast local
model and chat on a large cloud model.

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

An unknown role name is logged as a warning with the valid list — never silently ignored.

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
| Endpoint disabled | Skipped, next endpoint tried |
| Key env var not set | Reported as `resolves=false`, endpoint skipped |
| Timeout | Bounded retries with backoff, then next endpoint |
| Rate limit hit | Coalesced and deferred; never a tick-block |
| Malformed / oversized response | Rejected, local fallback used |
| Repeated failures | Circuit breaker opens, endpoint rested |
| Everything down | Deterministic local logic — the plugin keeps working |

---

## 6. Operations

| Command | Permission | Purpose |
| --- | --- | --- |
| `/null features` | `nullarmy.admin` | What works now, and what an AI model would add |
| `/null status` | `nullarmy.admin` | Adapter, live Null count, policy flags, endpoint diagnostics |

`/null status` prints each endpoint with its model id and, for the key, only
`env:VARNAME resolves=true` (or `resolves=false`). It never prints a key value.

---

## 7. FAQ

**Do I need any endpoints at all?**
No. See [You do not need any of this](#you-do-not-need-any-of-this) — the plugin is complete
offline; only five extras need a model.

**Can I mix providers?**
Yes — anything speaking the OpenAI chat-completions shape. Different providers, different keys,
same list.

**Does a slow model lag my server?**
No. Calls are async, time-bounded, rate-limited and cancellable, and the tick never waits.

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
