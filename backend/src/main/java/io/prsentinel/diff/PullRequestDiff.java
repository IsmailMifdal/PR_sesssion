package io.prsentinel.diff;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lignes ajoutées ou modifiées par la PR (côté nouveau fichier), et patch de chaque fichier.
 * Seules ces lignes intéressent le relecteur ; c'est aussi là que GitHub accepte des commentaires.
 */
public record PullRequestDiff(Map<String, Set<Integer>> changedLines, Map<String, String> patches) {

    public static final PullRequestDiff EMPTY = new PullRequestDiff(Map.of(), Map.of());

    public boolean touchesFile(String path) {
        return changedLines.containsKey(path);
    }

    public boolean touchesLine(String path, Integer line) {
        Set<Integer> lines = changedLines.get(path);
        return lines != null && line != null && lines.contains(line);
    }

    public Optional<String> patch(String path) {
        return Optional.ofNullable(patches.get(path));
    }
}
