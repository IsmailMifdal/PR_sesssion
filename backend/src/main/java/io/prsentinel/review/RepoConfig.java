package io.prsentinel.review;

import io.prsentinel.scan.Finding;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Réglages du dépôt, lus dans {@code .prsentinel.yml} <strong>à la base de la PR</strong> (jamais à sa tête : une
 * PR ne peut pas exclure son propre code de l'analyse). Format :
 *
 * <pre>
 * ignorer:
 *   - "eval/**"        # cas volontairement vulnérables d'un banc de test
 *   - "vendor/**"
 * </pre>
 *
 * Les secrets (Gitleaks) ne sont jamais ignorés : un secret commité est une fuite, quel que soit le dossier.
 */
public record RepoConfig(List<String> ignore, List<PathMatcher> matchers) {

    public static final String FILE = ".prsentinel.yml";
    public static final int MAX_BYTES = 16 * 1024;
    static final int MAX_PATTERNS = 50;
    public static final RepoConfig EMPTY = new RepoConfig(List.of(), List.of());

    /** Configuration invalide (syntaxe, type, taille) : exception, pour que l'appelant n'ignore rien. */
    public static class InvalidConfigException extends RuntimeException {
        public InvalidConfigException(String message) {
            super(message);
        }
    }

    public static RepoConfig parse(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return EMPTY;
        }
        if (yaml.length() > MAX_BYTES) {
            throw new InvalidConfigException("fichier trop gros");
        }
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(10);
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException e) {
            throw new InvalidConfigException("YAML illisible");
        }
        if (root == null) {
            return EMPTY;
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new InvalidConfigException("la racine doit être un dictionnaire");
        }
        Object raw = map.get("ignorer");
        if (raw == null) {
            return EMPTY;
        }
        if (!(raw instanceof List<?> list) || list.size() > MAX_PATTERNS) {
            throw new InvalidConfigException("« ignorer » doit être une liste de " + MAX_PATTERNS + " motifs au plus");
        }
        List<String> patterns = new ArrayList<>();
        List<PathMatcher> matchers = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank() || s.length() > 200) {
                throw new InvalidConfigException("motif invalide dans « ignorer »");
            }
            String p = s.strip().replaceFirst("^\\./", "").replaceFirst("^/", "");
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            } catch (RuntimeException e) {
                throw new InvalidConfigException("motif invalide : " + p);
            }
            patterns.add(p);
        }
        return new RepoConfig(List.copyOf(patterns), List.copyOf(matchers));
    }

    /** Vrai si l'alerte est dans un chemin ignoré (jamais pour un secret). */
    public boolean ignores(Finding f) {
        if (matchers.isEmpty() || "gitleaks".equals(f.tool()) || f.path() == null) {
            return false;
        }
        Path path;
        try {
            path = Path.of(f.path());
        } catch (RuntimeException e) {
            return false;
        }
        return matchers.stream().anyMatch(m -> m.matches(path));
    }

    public List<Finding> keep(List<Finding> findings) {
        return matchers.isEmpty() ? findings : findings.stream().filter(f -> !ignores(f)).toList();
    }
}
