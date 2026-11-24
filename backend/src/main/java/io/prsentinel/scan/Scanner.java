package io.prsentinel.scan;

import java.nio.file.Path;

/** Un scanner de sécurité exécuté sur le code de la PR. */
public interface Scanner {

    String name();

    /** Ne lève jamais : un échec est renvoyé dans {@link ScanResult#error()}. */
    ScanResult scan(Path source, Path workdir);
}
