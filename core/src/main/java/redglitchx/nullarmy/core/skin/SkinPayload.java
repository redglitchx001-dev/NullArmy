package redglitchx.nullarmy.core.skin;

import redglitchx.nullarmy.core.json.Json;

import java.util.List;
import java.util.Map;

/**
 * Reads a skin texture out of whatever a skin proxy returned.
 *
 * <p>{@code skins.proxy-url} may point at anything that hands out a signed
 * texture. Accepted shapes, in order:</p>
 * <ol>
 *   <li>{@code {"value":"...","signature":"..."}}</li>
 *   <li>MineSkin: {@code {"data":{"texture":{"value":"...","signature":"..."}}}}
 *       (also {@code "skin":{"texture":{"data":{...}}}} style nesting)</li>
 *   <li>Mojang session profile: {@code {"properties":[{"name":"textures","value":"...","signature":"..."}]}}</li>
 *   <li>Two lines of text: value, then signature</li>
 *   <li>One raw base64 value (unsigned - reported as such)</li>
 * </ol>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SkinPayload {

    private final String value;
    private final String signature;
    private final String error;

    private SkinPayload(String value, String signature, String error) {
        this.value = value == null ? "" : value;
        this.signature = signature == null ? "" : signature;
        this.error = error;
    }

    public String value() { return value; }
    public String signature() { return signature; }

    /** Why nothing usable was found; null on success. */
    public String error() { return error; }

    /** True when both halves are present: only then will a client show it. */
    public boolean complete() { return error == null && !value.isEmpty() && !signature.isEmpty(); }

    public static SkinPayload of(String value, String signature) {
        return new SkinPayload(value, signature, null);
    }

    public static SkinPayload failure(String error) {
        return new SkinPayload("", "", error == null ? "unknown error" : error);
    }

    public static SkinPayload parse(String body) {
        if (body == null || body.trim().isEmpty()) {
            return failure("the proxy returned an empty body");
        }
        String text = body.trim();
        if (text.startsWith("{")) {
            try {
                Map<String, Object> root = Json.asObject(Json.parse(text, 256 * 1024));
                SkinPayload found = fromObject(root, 0);
                if (found != null) {
                    return found;
                }
                return failure("the JSON has no value/signature pair (expected {\"value\":...,\"signature\":...})");
            } catch (RuntimeException ignored) {
                return failure("the JSON could not be read");
            }
        }
        String[] lines = text.split("\\r?\\n");
        if (lines.length >= 2 && isBase64(lines[0].trim()) && isBase64(lines[1].trim())) {
            return of(lines[0].trim(), lines[1].trim());
        }
        if (lines.length == 1 && isBase64(text)) {
            return new SkinPayload(text, "", "the proxy returned an unsigned texture value; clients only"
                    + " show signed textures");
        }
        return failure("the response is neither JSON nor base64");
    }

    private static SkinPayload fromObject(Map<String, Object> obj, int depth) {
        if (obj == null || depth > 5) {
            return null;
        }
        Object value = obj.get("value");
        Object signature = obj.get("signature");
        if (value instanceof String && !((String) value).isEmpty()) {
            return of((String) value, signature instanceof String ? (String) signature : "");
        }
        Object properties = obj.get("properties");
        if (properties instanceof List) {
            for (Object item : (List<?>) properties) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> property = (Map<String, Object>) item;
                    if ("textures".equals(property.get("name")) && property.get("value") instanceof String) {
                        Object sig = property.get("signature");
                        return of((String) property.get("value"), sig instanceof String ? (String) sig : "");
                    }
                }
            }
        }
        for (String key : new String[] {"data", "texture", "skin", "textures"}) {
            Object nested = obj.get(key);
            if (nested instanceof Map) {
                @SuppressWarnings("unchecked")
                SkinPayload found = fromObject((Map<String, Object>) nested, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static boolean isBase64(String text) {
        return text != null && text.length() >= 16 && text.matches("[A-Za-z0-9+/=_-]+");
    }

}
