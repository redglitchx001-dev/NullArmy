package redglitchx.nullarmy.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a YAML parser error into a safe diagnostic: the file, line, column,
 * a generic syntax hint, and a caret-only location. Source text and parser
 * excerpts are never shown because configuration may contain credentials.
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

    /** Three masked line numbers and a caret location; never includes YAML contents. */
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
        String summary = source == null
                ? "configuration could not be loaded"
                : "invalid YAML syntax (source text hidden)";
        return new YamlProblem(file == null ? "config.yml" : file, line, column, summary,
                snippet(source, line, column));
    }

    /** A position-only report: never echo YAML values or parser excerpts. */
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
            out.add(String.format(java.util.Locale.ROOT, "%4d | [content hidden]", i + 1));
            if (i == index) {
                StringBuilder caret = new StringBuilder("     | ");
                int caretColumn = Math.max(1, Math.min(256, column));
                for (int c = 1; c < caretColumn; c++) {
                    caret.append(' ');
                }
                out.add(caret.append('^').toString());
            }
        }
        return out;
    }
}
