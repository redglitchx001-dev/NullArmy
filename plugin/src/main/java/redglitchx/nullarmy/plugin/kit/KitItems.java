package redglitchx.nullarmy.plugin.kit;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionType;

import redglitchx.nullarmy.core.kit.DefaultKit;

import java.util.List;
import java.util.Map;

/**
 * Turns a kit entry into a real item: material, count, enchantments, potion.
 *
 * <p>This is what the old kit path lost: {@code LoadoutSlot} carries a material
 * and a count only, so an enchanted sword arrived as a plain one. Kits are now
 * written as full {@link ItemStack}s straight into the body's inventory.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class KitItems {

    private KitItems() {
    }

    /**
     * The item for a kit entry, or null when the material does not exist on
     * this server. Unknown enchantments or potion types are reported in
     * {@code problems} (may be null) and skipped.
     */
    public static ItemStack toStack(DefaultKit.Item item, List<String> problems) {
        if (item == null) {
            return null;
        }
        Material material = Material.matchMaterial(item.material());
        if (material == null || material.isAir() || !material.isItem()) {
            report(problems, "material " + item.material() + " does not exist on this server");
            return null;
        }
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(material.getMaxStackSize(), item.count())));
        for (Map.Entry<String, Integer> entry : item.enchants().entrySet()) {
            Enchantment enchantment = enchantment(entry.getKey());
            if (enchantment == null) {
                report(problems, "enchantment " + entry.getKey() + " does not exist");
                continue;
            }
            stack.addUnsafeEnchantment(enchantment, entry.getValue());
        }
        if (!item.potion().isEmpty()) {
            ItemMeta meta = stack.getItemMeta();
            PotionType type = potion(item.potion());
            if (!(meta instanceof PotionMeta)) {
                report(problems, item.material() + " cannot hold a potion type");
            } else if (type == null) {
                report(problems, "potion type " + item.potion() + " does not exist");
            } else {
                ((PotionMeta) meta).setBasePotionType(type);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    static Enchantment enchantment(String key) {
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                    .get(NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;
        }
    }

    static PotionType potion(String key) {
        try {
            return Registry.POTION.get(NamespacedKey.minecraft(key));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Removes an armor trim while preserving every other item property. */
    public static ItemStack withoutArmorTrim(ItemStack stack) {
        if (stack == null) {
            return null;
        }
        ItemMeta raw = stack.getItemMeta();
        if (raw instanceof ArmorMeta) {
            ArmorMeta meta = (ArmorMeta) raw;
            if (meta.getTrim() != null) {
                meta.setTrim(null);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    /**
     * Adds the Commander's white quartz trim to a chestplate. Ordinary Null
     * equipment intentionally never calls this.
     */
    public static ItemStack withCommanderChestplateTrim(ItemStack stack) {
        if (stack == null || !stack.getType().name().endsWith("_CHESTPLATE")) {
            return stack;
        }
        ItemMeta raw = stack.getItemMeta();
        if (!(raw instanceof ArmorMeta)) {
            return stack;
        }
        ArmorMeta meta = (ArmorMeta) raw;
        meta.setTrim(new ArmorTrim(TrimMaterial.QUARTZ, TrimPattern.SENTRY));
        stack.setItemMeta(meta);
        return stack;
    }

    /** Level of an enchantment on a stack, 0 when absent. */
    public static int level(ItemStack stack, String key) {
        Enchantment enchantment = enchantment(key);
        return stack == null || enchantment == null ? 0 : stack.getEnchantmentLevel(enchantment);
    }

    private static void report(List<String> problems, String problem) {
        if (problems != null) {
            problems.add(problem);
        }
    }
}
