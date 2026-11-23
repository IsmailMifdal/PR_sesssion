package io.prsentinel.agent;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masque les secrets avant tout envoi au modèle et avant toute publication : formats connus (y compris
 * multilignes, d'où le masquage au niveau du fichier), affectations évidentes, identifiants dans les URL,
 * jetons Bearer, et toute valeur de type jeton sur les lignes signalées par Gitleaks.
 */
public final class SecretMasker {

    public static final String MASK = "[SECRET MASQUÉ]";

    private static final List<Pattern> KNOWN = List.of(
            Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY( BLOCK)?-----[\\s\\S]*?(-----END [A-Z0-9 ]*PRIVATE KEY( BLOCK)?-----|\\z)"),
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b"),
            Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
            // Identifiants dans une URL : scheme://utilisateur:motdepasse@hôte
            Pattern.compile("(?<=://)[^\\s/:@\"']+:[^\\s/@\"']+(?=@)"),
            // En-têtes d'authentification
            Pattern.compile("(?i)(?<=\\b(?:bearer|basic|token)\\s)[A-Za-z0-9+/=_\\-.~]{8,}"));
    /** Affectation d'un secret : password = "…", "api_key": "…", token: '…', secret=… (clé entre guillemets ou non). */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?[A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key"
                    + "|client[_-]?secret|credentials?)[A-Za-z0-9_.-]*[\"']?\\s*[:=]\\s*)([\"']?)([^\\s\"',;]{4,})\\2");
    private static final Pattern TOKEN_LIKE = Pattern.compile("[A-Za-z0-9+/=_\\-.]{16,}");

    private final Map<String, Set<Integer>> flaggedLines;

    public SecretMasker(Map<String, Set<Integer>> flaggedLines) {
        this.flaggedLines = Map.copyOf(flaggedLines);
    }

    /** Empêche le contenu non fiable de fermer ou d'imiter nos délimiteurs {@code <<<…>>>}. */
    public static String neutralizeDelimiters(String text) {
        return text == null ? "" : text.replace("<<<", "‹‹‹").replace(">>>", "›››");
    }

    /** Texte libre (diff, message, réponse du modèle). */
    public String maskText(String text) {
        String out = neutralizeDelimiters(text);
        for (Pattern p : KNOWN) {
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        }
        return ASSIGNMENT.matcher(out).replaceAll(m -> Matcher.quoteReplacement(
                m.group(3).equals(MASK) || m.group(3).startsWith("[SECRET") ? m.group()
                        : m.group(1) + m.group(2) + MASK + m.group(2)));
    }

    /**
     * Fichier entier (une entrée par ligne) : les secrets multilignes (clés PEM) sont masqués avant le
     * découpage, puis les jetons des lignes signalées. Le nombre de lignes est conservé.
     */
    public List<String> maskFile(String path, List<String> lines) {
        String joined = String.join("\n", lines);
        String masked = maskText(joined);
        // Un bloc PEM masqué remplace plusieurs lignes par une : on recolle sur le même nombre de lignes.
        if (masked.split("\n", -1).length != lines.size()) {
            masked = maskPemKeepingLines(joined);
            masked = maskText(masked);
        }
        String[] out = Arrays.copyOf(masked.split("\n", -1), lines.size());
        Set<Integer> flagged = flaggedLines.get(path);
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) {
                out[i] = "";
            }
            if (flagged != null && flagged.contains(i + 1)) {
                out[i] = TOKEN_LIKE.matcher(out[i]).replaceAll(Matcher.quoteReplacement(MASK));
            }
        }
        return List.of(out);
    }

    private static String maskPemKeepingLines(String text) {
        Matcher m = KNOWN.getFirst().matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long newlines = m.group().chars().filter(c -> c == '\n').count();
            m.appendReplacement(sb, Matcher.quoteReplacement(MASK + "\n".repeat((int) newlines)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Une ligne isolée (lignes signalées comprises). Préférer {@link #maskFile} pour un fichier. */
    public String maskLine(String path, int lineNumber, String line) {
        String out = maskText(line);
        Set<Integer> flagged = flaggedLines.get(path);
        if (flagged != null && flagged.contains(lineNumber)) {
            out = TOKEN_LIKE.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        }
        return out;
    }
}
