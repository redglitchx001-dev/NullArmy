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
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;

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
            if (s == null || s.noDeathDrops()) {
                event.getDrops().clear();
                event.setDroppedExp(0);
                event.setShouldDropExperience(false);
                event.setKeepInventory(false);
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
