package redglitchx.nullarmy.core.construct;

import redglitchx.nullarmy.core.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads the strict JSON step list an OpenAI-compatible model is asked for.
 *
 * <pre>
 *   {"steps":[{"null":"Kr4vN","action":"PLACE","x":2,"y":0,"z":-1,"block":"COBBLESTONE"}, ...]}
 * </pre>
 *
 * <p>A bare array is accepted too, and so is a reply that wraps the JSON in a
 * Markdown fence or a sentence - models do that - but the JSON itself is read
 * strictly: every step needs a known action and integer coordinates, PLACE
 * needs a block, and anything unexpected is an error the caller can send back
 * to the model once before falling back to the offline planner.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class BuildPlanParser {

    /** What a parse produced: steps, or the reason there are none. */
    public static final class Result {
        private final List<BuildStep> steps;
        private final String error;

        private Result(List<BuildStep> steps, String error) {
            this.steps = steps == null ? Collections.emptyList() : Collections.unmodifiableList(steps);
            this.error = error;
        }

        public List<BuildStep> steps() { return steps; }
        public String error() { return error; }
        public boolean ok() { return error == null; }
    }

    private BuildPlanParser() {
    }

    /** A result carrying only a reason (a failed request, a timeout). */
    public static Result failure(String error) {
        return new Result(null, error == null ? "unknown error" : error);
    }

    public static Result parse(String reply, int maxSteps) {
        if (reply == null || reply.trim().isEmpty()) {
            return new Result(null, "the reply was empty");
        }
        String json = extractJson(reply);
        if (json == null) {
            return new Result(null, "the reply contains no JSON object or array");
        }
        Object root;
        try {
            root = Json.parse(json, 512 * 1024);
        } catch (RuntimeException bad) {
            return new Result(null, "the JSON could not be read: " + bad.getMessage());
        }
        List<Object> rawSteps;
        try {
            if (root instanceof Map) {
                Object steps = Json.asObject(root).get("steps");
                if (steps == null) {
                    return new Result(null, "the JSON object has no \"steps\" array");
                }
                rawSteps = Json.asArray(steps);
            } else {
                rawSteps = Json.asArray(root);
            }
        } catch (RuntimeException bad) {
            return new Result(null, "\"steps\" is not an array: " + bad.getMessage());
        }
        if (rawSteps.isEmpty()) {
            return new Result(null, "the plan has no steps");
        }
        if (maxSteps > 0 && rawSteps.size() > maxSteps) {
            return new Result(null, "the plan has " + rawSteps.size() + " steps, more than the limit of " + maxSteps);
        }
        List<BuildStep> out = new ArrayList<>();
        for (int i = 0; i < rawSteps.size(); i++) {
            try {
                out.add(step(Json.asObject(rawSteps.get(i))));
            } catch (RuntimeException bad) {
                return new Result(null, "step " + (i + 1) + ": " + bad.getMessage());
            }
        }
        return new Result(out, null);
    }

    private static BuildStep step(Map<String, Object> obj) {
        Object rawAction = obj.get("action");
        if (!(rawAction instanceof String)) {
            throw new IllegalArgumentException("\"action\" must be one of MOVE, BREAK, PLACE, PICKUP, WAIT");
        }
        BuildStep.Action action = BuildStep.Action.parse((String) rawAction);
        if (action == null) {
            throw new IllegalArgumentException("unknown action \"" + rawAction
                    + "\" (allowed: MOVE, BREAK, PLACE, PICKUP, WAIT)");
        }
        String name = "";
        Object rawName = obj.get("null");
        if (rawName != null) {
            if (!(rawName instanceof String)) {
                throw new IllegalArgumentException("\"null\" must be a Null's name");
            }
            name = (String) rawName;
        }
        int x = 0;
        int y = 0;
        int z = 0;
        if (action != BuildStep.Action.WAIT || obj.containsKey("x")) {
            x = integer(obj, "x");
            y = integer(obj, "y");
            z = integer(obj, "z");
        }
        String block = "";
        Object rawBlock = obj.get("block");
        if (rawBlock != null) {
            if (!(rawBlock instanceof String)) {
                throw new IllegalArgumentException("\"block\" must be a block name");
            }
            block = ((String) rawBlock).trim();
            if (block.startsWith("minecraft:")) {
                block = block.substring("minecraft:".length());
            }
        }
        if (action == BuildStep.Action.PLACE && block.isEmpty()) {
            throw new IllegalArgumentException("PLACE needs a \"block\"");
        }
        int ticks = 20;
        if (obj.containsKey("ticks")) {
            ticks = integer(obj, "ticks");
        }
        return new BuildStep(name, action, x, y, z, block, ticks);
    }

    private static int integer(Map<String, Object> obj, String key) {
        Object v = obj.get(key);
        if (!(v instanceof Number)) {
            throw new IllegalArgumentException("\"" + key + "\" must be an integer");
        }
        double d = ((Number) v).doubleValue();
        if (d != Math.rint(d) || Math.abs(d) > 100_000) {
            throw new IllegalArgumentException("\"" + key + "\" must be an integer, got " + d);
        }
        return (int) d;
    }

    /**
     * The first balanced JSON object or array in a reply, ignoring Markdown
     * fences and prose around it. String contents are respected when counting
     * brackets.
     */
    public static String extractJson(String reply) {
        if (reply == null) {
            return null;
        }
        int start = -1;
        for (int i = 0; i < reply.length(); i++) {
            char c = reply.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < reply.length(); i++) {
            char c = reply.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return reply.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** The JSON a plan serialises to, in the same shape the model is asked for. */
    public static String toJson(List<BuildStep> steps) {
        List<Object> list = new ArrayList<>();
        if (steps != null) {
            for (BuildStep step : steps) {
                list.add(step.toJson());
            }
        }
        Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("steps", list);
        return Json.write(root);
    }
}
