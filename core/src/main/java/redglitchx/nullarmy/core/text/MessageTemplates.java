package redglitchx.nullarmy.core.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every event line the plugin writes, as named templates with named
 * placeholders.
 *
 * <p>The owner once saw "destroyed s Totem Of Null": a line built from a
 * printf-style template whose argument went missing. Templates here use
 * {@code {name}} placeholders only, rendering refuses a missing value instead of
 * printing a half-sentence, and {@link #scan()} - run by the core test suite -
 * rejects any template that still contains printf ({@code %s}), positional
 * ({@code {0}}) or unbalanced placeholders.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class MessageTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_]*)\\}");
    private static final Pattern PRINTF = Pattern.compile("%[-#+ 0,(]*\\d*(?:\\.\\d+)?[sdfxXcbBhHeEgGaAot%n]");
    private static final Pattern POSITIONAL = Pattern.compile("\\{\\d+\\}");

    private static final Map<String, String> TEMPLATES;

    static {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("null.died", "{name} died ({cause})");
        t.put("null.arrived", "{count} Null(s) arrived for {owner}: {note}");
        t.put("null.kit", "{name}: kit {state}");
        t.put("null.unstacked", "unstacked {count} Null(s) near {where}");
        t.put("squad.spawn.failed", "a Null did not spawn for {owner}: {reason}");
        t.put("mission.state", "mission {mission} for {owner}: {state}");
        t.put("shutdown.started", "{who} destroyed the Totem Of Null - {count} Null(s) are leaving one by one");
        t.put("shutdown.empty", "{who} destroyed the Totem Of Null - there were no Nulls left to send out");
        t.put("shutdown.step", "{name} left ({left} still to go)");
        t.put("shutdown.done", "the Totem Of Null shutdown finished: {count} Null(s) gone, summons accepted again");
        t.put("horn.refresh", "{player} pressed the horn again; the open summon prompt was refreshed");
        t.put("zone.outline", "summon zone for {player}: {zone}");
        t.put("zone.refused", "no valid portal site inside the {size}x{size} zone centred on you: {reason}");
        t.put("portal.restored", "portal at {where} closed and its blocks were restored ({blocks} blocks)");
        t.put("config.error", "{file} line {line}, column {column}: {problem}");
        t.put("config.kept", "the last good configuration is still in use ({file} was not applied)");
        t.put("skin.applied", "{name}: skin from {source}");
        t.put("skin.failed", "skin source {source} failed: {reason}");
        t.put("order.ack", "{name} acknowledges: {order}");
        t.put("build.started", "build \"{goal}\" started for {owner}: {plan}");
        t.put("build.finished", "build \"{goal}\" finished: {placed} placed, {broken} broken");
        t.put("build.stopped", "build \"{goal}\" stopped: {reason}");
        t.put("combat.retaliate", "{name} fights back against {target}");
        TEMPLATES = Collections.unmodifiableMap(t);
    }

    private MessageTemplates() {
    }

    /** Every template by key. */
    public static Map<String, String> all() { return TEMPLATES; }

    /** The placeholder names a template uses, in order of first appearance. */
    public static Set<String> placeholders(String template) {
        Set<String> out = new LinkedHashSet<>();
        if (template == null) {
            return out;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Renders a template.
     *
     * @throws IllegalArgumentException for an unknown key or a missing value -
     *     a broken event line is a bug to fix, never a sentence to print
     */
    public static String render(String key, Map<String, ?> values) {
        String template = TEMPLATES.get(key);
        if (template == null) {
            throw new IllegalArgumentException("unknown message template: " + key);
        }
        return renderTemplate(template, values);
    }

    /** Renders any template string with the same rules. */
    public static String renderTemplate(String template, Map<String, ?> values) {
        StringBuilder sb = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(template);
        int last = 0;
        while (m.find()) {
            sb.append(template, last, m.start());
            String name = m.group(1);
            Object value = values == null ? null : values.get(name);
            if (value == null) {
                throw new IllegalArgumentException("template \"" + template + "\" is missing {" + name + "}");
            }
            sb.append(value);
            last = m.end();
        }
        sb.append(template.substring(last));
        return sb.toString();
    }

    /** Convenience: render with alternating name/value arguments. */
    public static String render(String key, Object... nameValuePairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (nameValuePairs != null) {
            for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
                values.put(String.valueOf(nameValuePairs[i]), nameValuePairs[i + 1]);
            }
        }
        return render(key, values);
    }

    /** Problems with a single template string; empty when it is clean. */
    public static List<String> problemsOf(String key, String template) {
        List<String> problems = new ArrayList<>();
        if (template == null || template.trim().isEmpty()) {
            problems.add(key + ": empty template");
            return problems;
        }
        if (PRINTF.matcher(template).find()) {
            problems.add(key + ": printf-style placeholder in \"" + template + "\"");
        }
        if (POSITIONAL.matcher(template).find()) {
            problems.add(key + ": positional placeholder in \"" + template + "\"");
        }
        String stripped = PLACEHOLDER.matcher(template).replaceAll("");
        if (stripped.indexOf('{') >= 0 || stripped.indexOf('}') >= 0) {
            problems.add(key + ": unbalanced or malformed braces in \"" + template + "\"");
        }
        return problems;
    }

    /** Problems across every template; the core test requires this to be empty. */
    public static List<String> scan() {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : TEMPLATES.entrySet()) {
            problems.addAll(problemsOf(entry.getKey(), entry.getValue()));
        }
        return problems;
    }
}
