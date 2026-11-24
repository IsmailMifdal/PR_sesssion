package io.prsentinel.review;

import java.util.regex.Pattern;

/** Neutralisation du texte non fiable (code de la PR, sortie des scanners, réponse du modèle). */
public final class MarkdownSafety {

    public static final int MAX_FIELD_CHARS = 300;
    private static final Pattern MD_SPECIAL = Pattern.compile("([\\\\`*_{}\\[\\]()#+!|~>.:-])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MarkdownSafety() {}

    /** Neutralise Markdown, HTML, @mentions et autoliens ; une seule ligne, longueur bornée. */
    public static String escape(String text, int limit) {
        String t = shorten(WHITESPACE.matcher(text == null ? "" : text).replaceAll(" ").strip(), limit);
        t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        t = MD_SPECIAL.matcher(t).replaceAll("\\\\$1");
        return t.replace("@", "@​");
    }

    public static String escape(String text) {
        return escape(text, MAX_FIELD_CHARS);
    }

    /** Span de code inline : ni Markdown, ni HTML, ni mention, ni autolien n'y sont interprétés. */
    public static String code(String text, int limit) {
        String t = shorten(WHITESPACE.matcher(text == null ? "" : text).replaceAll(" ").strip(), limit);
        return "`" + t.replace("`", "'").replace("|", "\\|") + "`";
    }

    public static String code(String text) {
        return code(text, MAX_FIELD_CHARS);
    }

    /** Bloc {@code suggestion} : le contenu ne peut pas fermer la clôture. */
    public static String fencedSuggestion(String patch) {
        String body = patch == null ? "" : patch.replace("\r", "");
        String fence = "```";
        while (body.contains(fence)) {
            fence += "`";
        }
        return fence + "suggestion\n" + body + (body.endsWith("\n") ? "" : "\n") + fence;
    }

    /** Bloc de code simple (non applicable), que le contenu ne peut pas fermer. */
    public static String fencedCode(String code) {
        String body = code == null ? "" : code.replace("\r", "");
        String fence = "```";
        while (body.contains(fence)) {
            fence += "`";
        }
        return fence + "\n" + body + (body.endsWith("\n") ? "" : "\n") + fence;
    }

    static String shorten(String t, int limit) {
        return t.length() > limit ? t.substring(0, limit - 1) + "…" : t;
    }
}
