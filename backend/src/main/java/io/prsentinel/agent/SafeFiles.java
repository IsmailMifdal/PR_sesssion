package io.prsentinel.agent;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Lecture prudente des fichiers du dépôt cloné : confinement, taille bornée, fichiers sensibles refusés. */
public final class SafeFiles {

    public static final long MAX_FILE_BYTES = 1_000_000;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(^|/)(\\.git(/|$)|\\.env([._-].*)?$|.*\\.(pem|key|p12|pfx|jks|keystore)$|id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?$|"
                    + "\\.npmrc$|\\.pypirc$|\\.netrc$|credentials(\\.json)?$)");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f\\x7f]");

    private SafeFiles() {}

    public static boolean isSensitive(String relativePath) {
        return SENSITIVE.matcher(relativePath.toLowerCase(Locale.ROOT)).find();
    }

    /** Chemin affichable : sans caractère de contrôle, borné. */
    public static String displayPath(String path) {
        String clean = CONTROL.matcher(path == null ? "" : path).replaceAll("?");
        return clean.length() > 200 ? clean.substring(0, 200) + "…" : clean;
    }

    /** Résout un chemin dans {@code root} (liens symboliques résolus) ; vide s'il en sort ou n'est pas lisible. */
    public static Optional<Path> resolve(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            return Optional.empty();
        }
        try {
            Path realRoot = root.toRealPath();
            Path candidate = realRoot.resolve(relative).normalize();
            if (!candidate.startsWith(realRoot) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            Path real = candidate.toRealPath();
            return real.startsWith(realRoot) ? Optional.of(real) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Lignes du fichier, ou vide s'il est trop gros, binaire ou illisible. */
    public static Optional<List<String>> readLines(Path file) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return Optional.empty();
            }
            return Optional.of(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (MalformedInputException e) {
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
