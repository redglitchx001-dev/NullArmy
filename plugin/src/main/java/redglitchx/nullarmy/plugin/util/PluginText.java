package redglitchx.nullarmy.plugin.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Shared NullArmy chat and inventory styling.
 *
 * <p>The prefix is encoded as Minecraft's native hex legacy format because
 * most existing command/listener messages are assembled as strings. The same
 * purple-to-cyan gradient is available as an Adventure component for modern
 * inventory titles and labels.</p>
 */
public final class PluginText {

    private static final int GRADIENT_START = 0xA855F7;
    private static final int GRADIENT_END = 0x22D3EE;
    private static final char SECTION = '\u00A7';
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /** A purple-to-cyan gradient brand prefix, followed by soft gray message text. */
    public static final String PREFIX = legacyGradient("[NullArmy]") + SECTION + 'r' + SECTION + '7' + ' ';

    private PluginText() {
    }

    /** Makes an Adventure component with one interpolated color per Unicode code point. */
    public static Component gradientComponent(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        int[] codePoints = text.codePoints().toArray();
        Component result = Component.empty();
        for (int i = 0; i < codePoints.length; i++) {
            String glyph = new String(Character.toChars(codePoints[i]));
            result = result.append(Component.text(glyph)
                    .color(TextColor.color(interpolate(GRADIENT_START, GRADIENT_END, i, codePoints.length)))
                    .decoration(TextDecoration.ITALIC, false));
        }
        return result;
    }

    /** Makes a Minecraft legacy-format gradient suitable for Bukkit's String chat APIs. */
    private static String legacyGradient(String text) {
        int[] codePoints = text.codePoints().toArray();
        StringBuilder result = new StringBuilder(text.length() * 15);
        for (int i = 0; i < codePoints.length; i++) {
            result.append(legacyColor(interpolate(GRADIENT_START, GRADIENT_END, i, codePoints.length)));
            result.appendCodePoint(codePoints[i]);
        }
        return result.toString();
    }

    private static String legacyColor(int rgb) {
        StringBuilder result = new StringBuilder(14);
        result.append(SECTION).append('x');
        for (int shift = 20; shift >= 0; shift -= 4) {
            result.append(SECTION).append(HEX[(rgb >>> shift) & 0xF]);
        }
        return result.toString();
    }

    private static int interpolate(int start, int end, int index, int length) {
        double amount = length <= 1 ? 0.0 : (double) index / (length - 1);
        int red = channel(start, end, 16, amount);
        int green = channel(start, end, 8, amount);
        int blue = channel(start, end, 0, amount);
        return (red << 16) | (green << 8) | blue;
    }

    private static int channel(int start, int end, int shift, double amount) {
        int from = (start >>> shift) & 0xFF;
        int to = (end >>> shift) & 0xFF;
        return (int) Math.round(from + (to - from) * amount);
    }
}
