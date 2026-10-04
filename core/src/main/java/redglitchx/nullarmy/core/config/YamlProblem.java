package redglitchx.nullarmy.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a YAML parser error into something an owner can act on: the file, the
 * line, the column, what is wrong, and the offending lines with a caret.
 *
 * <p>SnakeYAML's messages carry marks like {@code in 'reader', line 12, column 5:}
 * (1-based). The last mark is the problem; earlier ones are context. Parsing the
 * message keeps this class free of any YAML library, so it runs in the core
 * tests.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class YamlProblem {

    private static final Pattern MARK = Pattern.compile("line (\\d+), column (\\d+)");

    private final String file;
    private final int line;
    private final int column;
    private final String problem;
    private final List<String> snippet;

    private YamlProblem(String file, int line, int column, String problem, List<String> snippet) {
        this.file = file;
        this.line = line;
        this.column = column;
        this.problem = problem;
        this.snippet = snippet;
    }

    public String file() { return file; }

    /** 1-based line, or 0 when the message had no position. */
    public int line() { return line; }

    /** 1-based column, or 0 when the message had no position. */
    public int column() { return column; }

    public String problem() { return problem; }

    /** The offending line with one line of context each side and a caret line. */
    public List<String> snippet() { return snippet; }

    public boolean hasPosition() { return line > 0; }

    /** One line: "config.yml line 12, column 5: mapping values are not allowed here". */
    public String headline() {
        return file + (hasPosition() ? " line " + line + ", column " + column : "") + ": " + problem;
    }

    /**
     * Builds a report from a parser message and the text that was parsed.
     *
     * @param file    the file name to report
     * @param message the parser's message (may be null)
     * @param source  the text that failed to parse (may be null; no snippet then)
     */
    public static YamlProblem locate(String file, String message, String source) {
        String msg = message == null ? "" : message;
        int line = 0;
        int column = 0;
        Matcher m = MARK.matcher(msg);
        while (m.find()) {
            try {
                line = Integer.parseInt(m.group(1));
                column = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                // keep the previous mark
            }
        }
        return new YamlProblem(file == null ? "config.yml" : file, line, column, summarise(msg),
                snippet(source, line, column));
    }

    /**
     * The human part of the message: the lines that are not marks, carets or
     * echoed source, joined with "; ".
     */
    static String summarise(String message) {
        List<String> parts = new ArrayList<>();
        for (String raw : message.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("in '") || line.startsWith("in \"")
                    || line.equals("^") || raw.startsWith("    ")) {
                continue;
            }
            if (line.startsWith("org.yaml.snakeyaml") || line.startsWith("org.bukkit")) {
                int colon = line.indexOf(':');
                if (colon > 0 && colon < line.length() - 1) {
                    line = line.substring(colon + 1).trim();
                }
            }
            if (!line.isEmpty()) {
                parts.add(line);
            }
        }
        if (parts.isEmpty()) {
            return message.trim().isEmpty() ? "the file is not valid YAML" : message.trim();
        }
        return String.join("; ", parts);
    }

    static List<String> snippet(String source, int line, int column) {
        List<String> out = new ArrayList<>();
        if (source == null || line <= 0) {
            return out;
        }
        String[] lines = source.split("\\r?\\n", -1);
        int index = line - 1;
        if (index >= lines.length) {
            return out;
        }
        for (int i = Math.max(0, index - 1); i <= Math.min(lines.length - 1, index + 1); i++) {
            out.add(String.format(java.util.Locale.ROOT, "%4d | %s", i + 1, lines[i].replace('\t', '\u2192')));
            if (i == index) {
                StringBuilder caret = new StringBuilder("     | ");
                for (int c = 1; c < Math.max(1, column); c++) {
                    caret.append(' ');
                }
                out.add(caret.append('^').toString());
            }
        }
        return out;
    }
}
