package redglitchx.nullarmy.core.agent;

/**
 * The catalogue of AI agent roles NullArmy can bind to an endpoint.
 *
 * <p>Spec 7 defines chat, combat, building and navigation roles. Owner corrections
 * make building deterministic/local, so {@code BuilderAgent} is retained only
 * as a legacy connectivity-test key and is never sent a build-planning request.
 * For active roles, an agent <b>advises</b> and the local validator <b>decides</b>.</p>
 *
 * <p>There is deliberately <b>no moderation role</b>. Spec 8: "Do not let an
 * external endpoint ban, kick, mute, op, execute commands for, or moderate a
 * player." The absence of a moderation capability is enforced here by
 * construction, not by configuration.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public enum AgentRole {

    CHAT_COMMANDER("ChatCommander", OutputType.TEXT,
            "Produces short in-character chat text.",
            "issue commands, change targets, alter inventories, ban players, authorize actions"),

    COMBAT_TACTICIAN("CombatTactician", OutputType.INTENT,
            "Recommends a high-level tactical intent from a strict enum.",
            "deal damage, move an NPC, bypass the local combat validator"),

    BUILDER("BuilderAgent", OutputType.TEXT,
            "Legacy compatibility key for connectivity tests only; build plans are deterministic and local.",
            "design or execute builds, or return an action-bearing block plan"),

    PATHFINDER("PathfinderCore", OutputType.ROUTE,
            "Suggests a destination or route preference from a sanitized snapshot.",
            "move an NPC, expose hidden or through-wall world data"),

    SCOUT_OBSERVER("ScoutObserver", OutputType.OBSERVATIONS,
            "Summarises what the squad can actually see: contacts, terrain, hazards.",
            "receive hidden entities, other players' inventories, or through-wall data"),

    THREAT_ANALYST("ThreatAnalyst", OutputType.THREAT_RANKING,
            "Ranks observed threats from visible evidence (gear, position, numbers).",
            "read hidden health values, inventories, or unobserved targets"),

    QUARTERMASTER("LogisticsQuartermaster", OutputType.ALLOCATION,
            "Proposes loadout priorities, resupply requests and item allocation.",
            "create, duplicate or delete items; mutate the item ledger"),

    MEDIC_TRIAGE("MedicTriage", OutputType.TRIAGE,
            "Proposes triage order and treatment type for reported ally state.",
            "heal directly, grant effects, or know health it was not told"),

    FORMATION_TACTICIAN("FormationTactician", OutputType.FORMATION,
            "Proposes formation type, spacing, orientation and anchor.",
            "override collision, hitboxes or the hard separation constraint"),

    REDSTONE_ANALYST("RedstoneAnalyst", OutputType.CIRCUIT,
            "Interprets redstone from line-of-sight evidence only.",
            "read hidden wiring, bypass the visible-only perception rule"),

    MINING_FOREMAN("MiningForeman", OutputType.MINING_PLAN,
            "Proposes which visible blocks to mine, in what order, with which tool.",
            "x-ray for ore, see through blocks, or invent blocks that are not visible"),

    IDLE_DIRECTOR("IdleBehaviourDirector", OutputType.IDLE_ACTION,
            "Proposes bounded idle behaviours so Nulls never freeze unnaturally.",
            "spam animations, override danger checks or commanded objectives"),

    GUARDIAN_AUDITOR("GuardianAuditor", OutputType.AUDIT_VERDICT,
            "Reviews other agents' proposals for rule violations before validation.",
            "approve its own output, or override the local safety validator");

    /** The shape of an agent's reply. Drives strict schema validation. */
    public enum OutputType {
        /** Free short text. Length-capped and never executed. */
        TEXT,
        /** One value from a closed enum (hold, approach, flank, retreat, ...). */
        INTENT,
        /** Reserved schema type; no active AI role may return a build plan. */
        BLOCK_PLAN,
        /** A destination or route preference. */
        ROUTE,
        /** A list of observed contacts, features and hazards. */
        OBSERVATIONS,
        /** An ordered threat list with confidence values. */
        THREAT_RANKING,
        /** Item allocation and resupply proposal. */
        ALLOCATION,
        /** Triage order and treatment proposal. */
        TRIAGE,
        /** Formation type plus spacing/orientation parameters. */
        FORMATION,
        /** A circuit hypothesis built only from visible components. */
        CIRCUIT,
        /** An ordered mining plan with tool selection. */
        MINING_PLAN,
        /** A single bounded idle action with a duration. */
        IDLE_ACTION,
        /** approve / reject plus a reason, for auditing another agent. */
        AUDIT_VERDICT
    }

    private final String configKey;
    private final OutputType outputType;
    private final String purpose;
    private final String mayNever;

    AgentRole(String configKey, OutputType outputType, String purpose, String mayNever) {
        this.configKey = configKey;
        this.outputType = outputType;
        this.purpose = purpose;
        this.mayNever = mayNever;
    }

    /** The key used under {@code ai.agents:} in config.yml. */
    public String configKey() { return configKey; }

    /** Drives strict schema validation of the reply. */
    public OutputType outputType() { return outputType; }

    public String purpose() { return purpose; }

    /** What this role may never do, regardless of configuration. */
    public String mayNever() { return mayNever; }

    /** @return the matching role, or null if the key is unknown */
    public static AgentRole fromConfigKey(String key) {
        if (key == null) {
            return null;
        }
        for (AgentRole role : values()) {
            if (role.configKey.equalsIgnoreCase(key)) {
                return role;
            }
        }
        return null;
    }

    /**
     * Roles that can act without any endpoint at all.
     *
     * <p>Spec 7: "A missing or unreachable endpoint must never stall the server
     * or stop basic Null behaviour." These degrade to deterministic local logic.
     */
    public boolean hasLocalFallback() {
        // Every role has one; combat and pathing most critically.
        return true;
    }
}
