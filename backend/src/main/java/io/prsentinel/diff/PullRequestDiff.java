package io.prsentinel.diff;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lignes ajoutées ou modifiées par la PR (côté nouveau fichier), et patch de chaque fichier.
 *
 * <p>{@code wholeFiles} : fichiers dont GitHub ne fournit pas le patch (diff trop gros). Toutes leurs lignes
 * sont considérées comme modifiées, pour ne jamais écarter une alerte ; GitHub n'y accepte pas de
 * commentaire de ligne.
 */
public record PullRequestDiff(Map<String, Set<Integer>> changedLines, Map<String, String> patches,
        Set<String> wholeFiles) {

    public static final PullRequestDiff EMPTY = new PullRequestDiff(Map.of(), Map.of(), Set.of());

    public PullRequestDiff(Map<String, Set<Integer>> changedLines, Map<String, String> patches) {
        this(changedLines, patches, Set.of());
    }

    public boolean touchesFile(String path) {
        return changedLines.containsKey(path) || wholeFiles.contains(path);
    }

    public boolean touchesLine(String path, Integer line) {
        if (wholeFiles.contains(path)) {
            return true;
        }
        Set<Integer> lines = changedLines.get(path);
        return lines != null && line != null && lines.contains(line);
    }

    /** Ligne où GitHub accepte un commentaire de revue (présente dans un patch). */
    public boolean commentable(String path, Integer line) {
        Set<Integer> lines = changedLines.get(path);
        return !wholeFiles.contains(path) && lines != null && line != null && lines.contains(line);
    }

    public Optional<String> patch(String path) {
        return Optional.ofNullable(patches.get(path));
    }
}
