package redglitchx.nullarmy.plugin.body;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;

import redglitchx.nullarmy.core.drops.DeathDrops;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.V3Settings;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What happens to a Null's body in vanilla's event pipeline: it can be hurt, it
 * dies like a player, and none of that reaches public chat.
 *
 * <ul>
 *   <li><b>Death</b> - {@code ServerPlayer.die} fires {@link PlayerDeathEvent}
 *       and then broadcasts the death message unless it is empty. For a Null the
 *       message is cleared, death messages are switched off and (by default) the
 *       drops and experience are removed. The death becomes a console/status
 *       event. A MONITOR check counts anything that would still leak.</li>
 *   <li><b>Advancements</b> - a Null never announces "has made the advancement".</li>
 *   <li><b>Fall damage</b> - real by default; {@code combat.fall-damage: false}
 *       cancels it for Nulls only.</li>
 *   <li><b>Hits</b> - recorded for retaliation and for the self test, and an axe
 *       landing on a Null's raised shield disables the shield for
 *       {@code combat.shield-disable-ticks} (1.5 s by default).</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullLifecycleListener implements Listener {

    /** One recorded hit. */
    public static final class Hit {
        public final UUID victim;
        public final UUID attacker;
        public final double baseDamage;
        public final double finalDamage;
        public final boolean critical;
        public final boolean blocked;
        public final long tick;

        Hit(UUID victim, UUID attacker, double baseDamage, double finalDamage, boolean critical,
            boolean blocked, long tick) {
            this.victim = victim;
            this.attacker = attacker;
            this.baseDamage = baseDamage;
            this.finalDamage = finalDamage;
            this.critical = critical;
            this.blocked = blocked;
            this.tick = tick;
        }
    }

    /** One recorded death of a Null. */
    public static final class Death {
        public final UUID body;
        public final String name;
        public final long tick;
        public final boolean messageCleared;
        public final int dropsLeft;

        Death(UUID body, String name, long tick, boolean messageCleared, int dropsLeft) {
            this.body = body;
            this.name = name;
            this.tick = tick;
            this.messageCleared = messageCleared;
            this.dropsLeft = dropsLeft;
        }
    }

    private final NullArmyPlugin plugin;
    private final Deque<Hit> hits = new ArrayDeque<>();
    private final Deque<Death> deaths = new ArrayDeque<>();
    private final Deque<String> consumed = new ArrayDeque<>();
    private volatile Projectile lastArrow;
    private volatile org.bukkit.util.Vector lastArrowVelocity;
    private volatile UUID lastArrowShooter;
    private volatile long lastArrowTick;

    public NullLifecycleListener(NullArmyPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean isNull(Entity entity) {
        return entity instanceof Player && plugin.adapter() != null
                && plugin.adapter().isNullEntity(entity.getUniqueId());
    }

    private V3Settings settings() {
        return plugin.pluginConfig() == null ? null : plugin.pluginConfig().v3();
    }

    // ------------------------------------------------------------------ death

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Guard.attempt(plugin.getLogger(), "silencing a Null death", () -> {
            Player body = event.getPlayer();
            if (!isNull(body)) {
                return;
            }
            Component original = event.deathMessage();
            String cause = original == null ? "unknown"
                    : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(original);
            event.deathMessage(null);
            event.setShowDeathMessages(false);
            V3Settings s = settings();
            // v4 (P-10): a defeated Null leaves its kit on the ground, like a
            // player. drops.enabled is the only switch - a server that already
            // answered the pre-v4 nulls.no-death-drops keeps that answer because
            // the migration folded it into drops.enabled, not because the old
            // key is consulted here. Two switches for one question is how an
            // update silently reverses an owner's answer.
            boolean drop = s == null || s.dropsEnabled();
            if (!drop) {
                event.getDrops().clear();
                event.setDroppedExp(0);
                event.setShouldDropExperience(false);
                event.setKeepInventory(false);
            } else {
                equipDrops(body, event.getDrops(), s == null ? 1.0D : s.dropsChance());
            }
            if (plugin.chatGate() != null) {
                plugin.chatGate().vanillaSilenced();
                plugin.chatGate().event("null.died", "name", body.getName(), "cause", cause);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeathMonitor(PlayerDeathEvent event) {
        Guard.attempt(plugin.getLogger(), "checking a Null death", () -> {
            Player body = event.getPlayer();
            if (!isNull(body)) {
                return;
            }
            Component message = event.deathMessage();
            boolean cleared = message == null || message.equals(Component.empty())
                    || !event.getShowDeathMessages();
            if (!cleared && plugin.chatGate() != null) {
                plugin.chatGate().vanillaLeaked("death message of " + body.getName());
            }
            synchronized (deaths) {
                deaths.addLast(new Death(body.getUniqueId(), body.getName(), plugin.currentTick(), cleared,
                        event.getDrops().size()));
                while (deaths.size() > 32) {
                    deaths.removeFirst();
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Guard.attempt(plugin.getLogger(), "silencing a Null advancement", () -> {
            if (isNull(event.getPlayer()) && event.message() != null) {
                event.message(null);
                if (plugin.chatGate() != null) {
                    plugin.chatGate().vanillaSilenced();
                }
            }
        });
    }

    // ----------------------------------------------------------------- damage

    /**
     * Puts a defeated Null's equipment on the ground.
     *
     * <p>Whatever the server already put in {@code drops} is kept; the armour,
     * both hands and the inventory that are missing are added, so the loot is
     * complete whichever half of the drop list the server version fills in.
     * {@code chance} is the share of the kit that survives.</p>
     */
    private void equipDrops(Player body, List<ItemStack> drops, double chance) {
        try {
            java.util.Set<String> present = new java.util.HashSet<>();
            for (ItemStack already : drops) {
                if (already != null && already.getType() != Material.AIR) {
                    present.add(already.getType().name());
                }
            }
            PlayerInventory inv = body.getInventory();
            java.util.Random random = new java.util.Random(body.getUniqueId().getLeastSignificantBits()
                    ^ plugin.currentTick());
            for (int slot = 0; slot < 41; slot++) {
                ItemStack stack = inv.getItem(slot);
                if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) {
                    continue;
                }
                if (!DeathDrops.shouldDrop(true, chance, random.nextDouble())) {
                    continue;
                }
                if (present.contains(stack.getType().name())) {
                    continue;
                }
                present.add(stack.getType().name());
                drops.add(stack.clone());
            }
            dropsCleared++;
            lastDropCount = drops.size();
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] could not equip the drops: " + Guard.describe(t));
        }
    }

    /**
     * Two Nulls who belong to the same owner are squad mates whatever the
     * targeting rule concluded, and a squad mate's blow is not a blow.
     *
     * <p>L-04's camp is where this matters: an idle squad spars, jostles and
     * retaliates, and not one of those may cost a body health.</p>
     */
    private boolean sameSquad(Entity attacker, Entity victim) {
        if (plugin.adapter() == null || plugin.squads() == null || !isNull(attacker) || !isNull(victim)) {
            return false;
        }
        NullBody one = plugin.adapter().bodyOf(attacker.getUniqueId());
        NullBody two = plugin.adapter().bodyOf(victim.getUniqueId());
        if (one == null || two == null) {
            return false;
        }
        UUID a = plugin.squads().ownerOf(one);
        UUID b = plugin.squads().ownerOf(two);
        return a != null && a.equals(b);
    }

    /**
     * Friendly fire, cancelled at the lowest priority so nothing else sees it.
     *
     * <p><b>P-04 / P-05.</b> The owner's own arrows and swings used to land on
     * his own Nulls, and a Null hit by its owner hit back. The squads share a
     * scoreboard team with friendly fire off (see {@code SquadManager}), and
     * this is the belt-and-braces half: no Null may ever damage its owner, the
     * Commander, a squad mate, or anyone on {@code policy.protected} - not with
     * a sword, not with an arrow, not in retaliation.</p>
     */
    /**
     * L-08: loot discipline - a Null picks up the drops of the players it
     * defeats. Nothing is taken from a living player and nothing is teleported:
     * the body simply walks over the item, which is why the pickup is the
     * vanilla one, only counted here.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event == null || event.getEntity() == null || plugin.brain() == null) {
            return;
        }
        UUID id = event.getEntity().getUniqueId();
        if (plugin.adapter() == null || !plugin.adapter().isNullEntity(id)) {
            return;
        }
        NullBody body = plugin.adapter().bodyOf(id);
        if (body == null) {
            return;
        }
        plugin.brain().notePickup(body);
        if (plugin.chatGate() != null) {
            plugin.chatGate().event("loot.pickup", "null", event.getEntity().getName(),
                    "item", event.getItem() == null || event.getItem().getItemStack() == null
                            ? "?" : event.getItem().getItemStack().getType().name());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        Entity victimEntity = event.getEntity();
        Entity raw = event.getDamager();
        if (raw == null || victimEntity == null) {
            return;
        }
        Entity attacker = raw;
        if (attacker instanceof Projectile && ((Projectile) attacker).getShooter() instanceof Entity) {
            attacker = (Entity) ((Projectile) attacker).getShooter();
        }
        if (!(attacker instanceof LivingEntity)) {
            return;
        }
        if (!isNull(attacker) && !isNull(victimEntity)) {
            return; // two real players: server PvP rules decide, not us
        }
        boolean blocked;
        try {
            blocked = !plugin.brain().mayTarget(attacker, victimEntity) || sameSquad(attacker, victimEntity);
        } catch (Throwable t) {
            blocked = false;
        }
        if (blocked) {
            event.setCancelled(true);
            friendlyFireBlocked++;
        }
    }

    /** How many squad-on-squad, owner or Commander hits were cancelled (P-04/P-05). */
    public int friendlyFireBlocked() { return friendlyFireBlocked; }

    /** How many Null deaths dropped a kit (P-10). */
    public int dropsCleared() { return dropsCleared; }

    /** Items in the last death's drop list (P-10). */
    public int lastDropCount() { return lastDropCount; }

    private volatile int friendlyFireBlocked;
    private volatile int dropsCleared;
    private volatile int lastDropCount;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !isNull(event.getEntity())) {
            return;
        }
        V3Settings s = settings();
        if (s != null && !s.fallDamage()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Guard.attempt(plugin.getLogger(), "recording a hit", () -> {
            Entity victim = event.getEntity();
            Entity attacker = event.getDamager();
            if (attacker instanceof Projectile && ((Projectile) attacker).getShooter() instanceof Entity) {
                attacker = (Entity) ((Projectile) attacker).getShooter();
            }
            boolean victimIsNull = isNull(victim);
            boolean attackerIsNull = isNull(attacker);
            if (!victimIsNull && !attackerIsNull) {
                noteOwnerAttacked(victim, attacker);
                return;
            }
            boolean blocked = false;
            try {
                blocked = event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING)
                        && event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) < 0.0D;
            } catch (Throwable ignored) {
                // Older modifier API: the final damage below still tells the story.
            }
            Hit hit = new Hit(victim.getUniqueId(), attacker == null ? null : attacker.getUniqueId(),
                    event.getDamage(), event.getFinalDamage(), event.isCritical(), blocked, plugin.currentTick());
            synchronized (hits) {
                hits.addLast(hit);
                while (hits.size() > 64) {
                    hits.removeFirst();
                }
            }
            if (victimIsNull && attacker instanceof LivingEntity && plugin.brain() != null) {
                NullBody body = plugin.adapter().bodyOf(victim.getUniqueId());
                if (body != null) {
                    plugin.brain().noteAttacked(body, (LivingEntity) attacker);
                }
            }
            if (victimIsNull && attacker instanceof LivingEntity) {
                disableShieldOnAxeHit((Player) victim, (LivingEntity) attacker);
            }
        });
    }

    /** A player whose Nulls are nearby was hit: their squad may fight back. */
    private void noteOwnerAttacked(Entity victim, Entity attacker) {
        if (!(victim instanceof Player) || !(attacker instanceof LivingEntity) || plugin.brain() == null) {
            return;
        }
        plugin.brain().noteOwnerAttacked(victim.getUniqueId(), (LivingEntity) attacker);
    }

    /**
     * An axe on a raised shield: the shield goes down and stays on cooldown for
     * {@code combat.shield-disable-ticks}, the rule the PvP brain is built on.
     */
    private void disableShieldOnAxeHit(Player victim, LivingEntity attacker) {
        V3Settings s = settings();
        if (s == null || !s.shields() || s.shieldDisableTicks() <= 0 || !victim.isBlocking()) {
            return;
        }
        ItemStack weapon = attacker.getEquipment() == null ? null : attacker.getEquipment().getItemInMainHand();
        if (weapon == null || !weapon.getType().name().endsWith("_AXE")) {
            return;
        }
        final int ticks = s.shieldDisableTicks();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                victim.clearActiveItem();
                victim.setCooldown(Material.SHIELD, ticks);
            } catch (Throwable ignored) {
                // The body may have died from the same hit.
            }
        });
    }

    // ----------------------------------------------------------- bows and food

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (isNull(event.getEntity()) && event.getProjectile() instanceof Projectile) {
            lastArrow = (Projectile) event.getProjectile();
            lastArrowVelocity = event.getProjectile().getVelocity().clone();
            lastArrowShooter = event.getEntity().getUniqueId();
            lastArrowTick = plugin.currentTick();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isNull(event.getPlayer())) {
            synchronized (consumed) {
                consumed.addLast(event.getPlayer().getName() + ":"
                        + event.getItem().getType().name().toLowerCase(Locale.ROOT) + "@" + plugin.currentTick());
                while (consumed.size() > 32) {
                    consumed.removeFirst();
                }
            }
        }
    }

    // ------------------------------------------------------------ measurements

    /** Hits recorded since {@code sinceTick}, oldest first. */
    public List<Hit> hitsSince(long sinceTick) {
        List<Hit> out = new ArrayList<>();
        synchronized (hits) {
            for (Hit hit : hits) {
                if (hit.tick >= sinceTick) {
                    out.add(hit);
                }
            }
        }
        return out;
    }

    /** Deaths recorded since {@code sinceTick}, oldest first. */
    public List<Death> deathsSince(long sinceTick) {
        List<Death> out = new ArrayList<>();
        synchronized (deaths) {
            for (Death death : deaths) {
                if (death.tick >= sinceTick) {
                    out.add(death);
                }
            }
        }
        return out;
    }

    /** Items Nulls consumed, as "name:item@tick". */
    public List<String> consumed() {
        synchronized (consumed) {
            return new ArrayList<>(consumed);
        }
    }

    public Projectile lastArrow() { return lastArrow; }

    /** The velocity the last Null arrow was launched with (before drag or impact). */
    public org.bukkit.util.Vector lastArrowVelocity() {
        return lastArrowVelocity == null ? null : lastArrowVelocity.clone();
    }
    public UUID lastArrowShooter() { return lastArrowShooter; }
    public long lastArrowTick() { return lastArrowTick; }
}
