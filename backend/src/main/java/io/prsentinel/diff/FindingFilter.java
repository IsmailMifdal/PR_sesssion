package io.prsentinel.diff;

import io.prsentinel.scan.Finding;
import java.util.List;

/**
 * Garde les alertes qui concernent la PR : sur une ligne modifiée, ou sans ligne (dépendance vulnérable,
 * fichier entier) dans un fichier modifié. Le bruit du code existant n'intéresse pas le relecteur.
 */
public final class FindingFilter {

    private FindingFilter() {}

    public static boolean concernsPullRequest(Finding f, PullRequestDiff diff) {
        if (f.line() == null) {
            return diff.touchesFile(f.path());
        }
        return diff.touchesLine(f.path(), f.line());
    }

    public static List<Finding> keepChanged(List<Finding> findings, PullRequestDiff diff) {
        return findings.stream().filter(f -> concernsPullRequest(f, diff)).toList();
    }
}
