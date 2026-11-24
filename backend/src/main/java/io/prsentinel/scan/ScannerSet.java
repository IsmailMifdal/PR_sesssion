package io.prsentinel.scan;

import java.util.List;

/** Scanners exécutés sur chaque PR (type dédié plutôt qu'une {@code List} injectée par Spring). */
public record ScannerSet(List<Scanner> scanners) {
    public ScannerSet {
        scanners = List.copyOf(scanners);
    }
}
