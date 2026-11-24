package io.prsentinel.diff;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extrait les numéros de lignes ajoutées (côté droit) d'un patch unifié GitHub. */
public final class UnifiedDiffParser {

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private UnifiedDiffParser() {}

    public static Set<Integer> addedLines(String patch) {
        Set<Integer> lines = new TreeSet<>();
        if (patch == null) {
            return lines;
        }
        int current = -1;
        for (String raw : patch.split("\n", -1)) {
            Matcher hunk = HUNK.matcher(raw);
            if (hunk.find()) {
                current = Integer.parseInt(hunk.group(1));
                continue;
            }
            if (current < 0 || raw.startsWith("\\")) {
                continue; // avant le premier bloc, ou « \ No newline at end of file »
            }
            if (raw.startsWith("+")) {
                lines.add(current++);
            } else if (raw.startsWith(" ") || raw.isEmpty()) {
                current++;
            }
            // les lignes « - » n'existent pas dans le nouveau fichier
        }
        return lines;
    }
}
