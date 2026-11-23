package io.prsentinel.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Masque les secrets avant tout envoi au modèle : formats connus partout, et toute valeur ressemblant à un
 * jeton sur les lignes signalées par Gitleaks.
 */
public final class SecretMasker {

    public static final String MASK = "[SECRET MASQUÉ]";

    private static final List<Pattern> KNOWN = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{40,}\\b"),
            Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"));
    /** Affectation d'un secret évident : password = "…", token: '…', etc. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)((?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\\s*[:=]\\s*)"
                    + "([\"']?)[^\\s\"']{6,}\\2");
    /** Valeur de type jeton (lignes signalées par Gitleaks seulement). */
    private static final Pattern TOKEN_LIKE = Pattern.compile("[A-Za-z0-9+/=_\\-.]{16,}");

    private final Map<String, Set<Integer>> flaggedLines;

    public SecretMasker(Map<String, Set<Integer>> flaggedLines) {
        this.flaggedLines = Map.copyOf(flaggedLines);
    }

    /** Empêche le contenu non fiable de fermer ou d'imiter nos délimiteurs {@code <<<…>>>}. */
    public static String neutralizeDelimiters(String text) {
        return text.replace("<<<", "‹‹‹").replace(">>>", "›››");
    }

    public String maskText(String text) {
        String out = neutralizeDelimiters(text);
        for (Pattern p : KNOWN) {
            out = p.matcher(out).replaceAll(MASK);
        }
        return ASSIGNMENT.matcher(out).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                m.group(1) + m.group(2) + MASK + m.group(2)));
    }

    /** Masque une ligne de fichier ; {@code lineNumber} commence à 1. */
    public String maskLine(String path, int lineNumber, String line) {
        String out = maskText(line);
        Set<Integer> flagged = flaggedLines.get(path);
        if (flagged != null && flagged.contains(lineNumber)) {
            out = TOKEN_LIKE.matcher(out).replaceAll(MASK);
        }
        return out;
    }
}
