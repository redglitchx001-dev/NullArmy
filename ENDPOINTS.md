# NullArmy — Endpoints & Agents

**Where do I add endpoints? How many? How do I wire in a model id and an API key?**

Short answer: **`config.yml`, under `ai.endpoints`.** Add as many as you want — there is no limit.

---

## TL;DR

```yaml
ai:
  enabled: true                      # master switch

  endpoints:
    my-endpoint:                     # any id you like
      base-url: "https://api.openai.com/v1"
      model: "gpt-4o-mini"           # <-- model id
      auth-key-env: "OPENAI_API_KEY" # <-- NAME of env var, never the key
      enabled: true

  agents:
    CombatTactician:
      endpoint: "my-endpoint"
      fallbacks: ["my-backup"]
      enabled: true
```

Three steps: define endpoints → bind agent roles to them → enable. Anything disabled,
unreachable or rate-limited falls back to deterministic local logic. **The server tick is
never blocked on a network call.**

---

## 1. Endpoint reference

Each entry under `ai.endpoints` needs a unique id. Fields:

| Field | Required | Default | Meaning |
| --- | :---: | --- | --- |
| `base-url` | ✅ | — | OpenAI-compatible endpoint. Must start with `http://` or `https://`. |
| `model` | ✅ | — | The **model id** sent with every request. |
| `auth-key-env` | ✅ | — | The **name** of the environment variable holding your API key. Not the key itself. |
| `timeout-millis` | ❌ | `3000` | Hard timeout. A slow endpoint is abandoned, never awaited. |
| `calls-per-minute` | ❌ | `20` | Rate limit for this endpoint. |
| `max-json-bytes` | ❌ | `8192` | Responses larger than this are rejected outright. |
| `max-retries` | ❌ | `2` | Bounded retries with backoff. |
| `enabled` | ❌ | `false` | Disabled endpoints are skipped silently. |

### 🔒 Why `auth-key-env` and not the key

Spec §7.2: *"Never write API keys into commands, AI prompts, chat, debug logs, exception
traces, or persisted gameplay data."*

So you put the **variable name** in `config.yml`, and the secret in your environment:

```bash
export OPENAI_API_KEY="sk-…"      # Linux / macOS
setx OPENAI_API_KEY "sk-…"        # Windows
```

Consequences: the key never lands in the config file, the git repo, the jar, a chat message,
or a log line. `/null status` prints the **variable name** and whether it resolved — never the value.

An endpoint whose key does not resolve is simply treated as unusable and skipped.

---

## 2. How many endpoints can I add?

**As many as you want.** Idiomatic setups:

| Setup | How |
| --- | --- |
| **One endpoint, all agents** | Define one, point every agent at it. |
| **Cheap/fast split** | Local Ollama for chatty/low-stakes roles, a hosted model for planning roles. |
| **Redundancy** | Each agent gets a primary plus a `fallbacks:` list, tried in order. |
| **Per-role specialisation** | A coding-strong model for `BuilderAgent`, a fast one for `CombatTactician`. |
| **Zero endpoints** | Leave `ai.enabled: false`. Everything runs on local deterministic logic — the plugin is fully functional. |

### Add your own (copy-paste)

```yaml
  endpoints:
    my-endpoint:
      base-url: "https://my.provider/v1"
      model: "my-model-id"
      auth-key-env: "MY_API_KEY"
      timeout-millis: 3000
      calls-per-minute: 20
      max-json-bytes: 8192
      max-retries: 2
      enabled: true
```

Any OpenAI-compatible API works — OpenAI, Anthropic (via a compatible gateway), OpenRouter,
Ollama, LM Studio, vLLM, llama.cpp server, or your own proxy.

---

## 3. The 13 agent roles

The original spec defined **4**. NullArmy now ships **13**.

| # | Agent | Output schema | Purpose | May **never** |
| ---: | --- | :---: | --- | --- |
| 1 | **ChatCommander** | `TEXT` | Short in-character chat | issue commands, change targets, alter inventories, **ban players**, authorize actions |
| 2 | **CombatTactician** | `INTENT` | Tactical intent from a strict enum | deal damage, move an NPC, bypass the combat validator |
| 3 | **BuilderAgent** | `BLOCK_PLAN` | Bounded block-plan JSON | place blocks, skip inventory/support/protection/cost checks |
| 4 | **PathfinderCore** | `ROUTE` | Route preference from a sanitized snapshot | move an NPC, expose hidden or through-wall data |
| 5 | **ScoutObserver** | `OBSERVATIONS` | Summarise what the squad can actually see | receive hidden entities, inventories, through-wall data |
| 6 | **ThreatAnalyst** | `THREAT_RANKING` | Rank threats from visible evidence | read hidden health, inventories, unobserved targets |
| 7 | **LogisticsQuartermaster** | `ALLOCATION` | Loadout priorities, resupply, allocation | create/duplicate/delete items, mutate the ledger |
| 8 | **MedicTriage** | `TRIAGE` | Triage order and treatment type | heal directly, grant effects, know health it wasn't told |
| 9 | **FormationTactician** | `FORMATION` | Formation type, spacing, orientation, anchor | override collision, hitboxes, or hard separation |
| 10 | **RedstoneAnalyst** | `CIRCUIT` | Interpret redstone from line-of-sight only | read hidden wiring, bypass visible-only perception |
| 11 | **MiningForeman** | `MINING_PLAN` | Which visible blocks to mine, order, tool | x-ray for ore, see through blocks |
| 12 | **IdleBehaviourDirector** | `IDLE_ACTION` | Bounded idle behaviours so Nulls never freeze | spam animations, override danger checks |
| 13 | **GuardianAuditor** | `AUDIT_VERDICT` | Review *other agents'* proposals before validation | approve its own output, override the validator |

The `config.yml` key is the agent name exactly as written above (case-insensitive when parsed).

---

## 4. Authority model

```
   Agent (any)  ──recommendation──▶  GuardianAuditor  ──verdict──▶  LOCAL VALIDATOR  ──▶ world
                                                                          │
                                                          rejects: illegal items,
                                                          no safe path, protection,
                                                          stale target, cooldown, bad JSON
```

- An agent **advises**. The local validator **decides**. Spec §7.7.
- Every recommendation carries an **expiry**, request id, scope and confidence. A stale reply
  is discarded, not executed. Spec §7.5.
- **GuardianAuditor is a second pair of eyes, not a bypass.** It can only reject; it can never
  approve its own output or override the validator.

### Hard limits that configuration cannot lift

| Rule | Source |
| --- | --- |
| No agent can **ban, kick, mute, op** or moderate a player | Spec §8 |
| No agent can execute console/server commands | Spec §7 |
| No agent can move an NPC or place a block directly | Spec §7 |
| No agent can create, duplicate or delete items | Spec §1.2 |
| No agent sees hidden entities, inventories or through-wall data | Spec §5 |
| No HTTP request ever runs on the tick thread | Spec §7.6 |

There is deliberately **no moderation role** — that capability is absent by construction, not
by config. A test (`testNoModerationRole`) fails the build if one is ever added.

---

## 5. Reliability

Each agent resolves to an ordered endpoint chain: **primary → fallbacks → local logic.**

| Failure | Behaviour |
| --- | --- |
| Endpoint disabled | Skipped, next in chain |
| API key missing | Treated unusable, skipped |
| Timeout | Bounded retries with backoff, then next in chain |
| Rate limit hit | Token bucket delays, then next in chain |
| Repeated failures | **Circuit breaker** opens; calls rejected without touching the network |
| Malformed / oversized / out-of-schema JSON | Rejected; never partially applied |
| Everything down | Deterministic local logic. **Basic Null behaviour never stops.** |

---

## 6. Operations

```
/null status
```

Shows each endpoint: model, base URL, the **env-var name**, whether it resolved, enabled
state, rate limit and timeout. **Never the key value.**

Config problems are reported at startup with severity:

- `ERROR` — e.g. an agent references an undefined endpoint, or a role is bound twice
- `WARNING` — e.g. an agent is enabled but every endpoint it points at is disabled

An unknown agent key in `config.yml` is ignored with a warning listing the valid keys.

---

## 7. FAQ

**Do I need any endpoints at all?**
No. With `ai.enabled: false` the plugin runs entirely on local deterministic logic. Endpoints
are an optional enhancement, never a requirement.

**Can one endpoint serve several agents?**
Yes — point as many agents at the same endpoint id as you like. Rate limits are per endpoint,
so shared endpoints share the budget.

**Can one agent use several endpoints?**
Yes, as a fallback chain (`endpoint:` + `fallbacks:`). Not as a load-balanced pool — the first
usable endpoint in order wins.

**Can I hot-reload endpoints?**
Config reload lands with the command layer (Phase 3). Until then, restart the server.

**Is my API key sent anywhere except my endpoint?**
No. Requests go only to the `base-url` you configured. There is no telemetry, no phone-home,
and no third-party service.

---

**Copyright (c) RedGlitchX. All rights reserved.**
