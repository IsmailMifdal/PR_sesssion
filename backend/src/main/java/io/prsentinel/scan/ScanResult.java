package io.prsentinel.scan;

import java.util.List;

/** Résultat d'un scanner : alertes, ou erreur (l'échec d'un outil ne bloque pas les autres). */
public record ScanResult(String tool, List<Finding> findings, String error) {

    public static ScanResult ok(String tool, List<Finding> findings) {
        return new ScanResult(tool, List.copyOf(findings), null);
    }

    public static ScanResult failed(String tool, String error) {
        return new ScanResult(tool, List.of(), error);
    }

    public boolean isOk() {
        return error == null;
    }

    public ScanResult withFindings(List<Finding> kept) {
        return new ScanResult(tool, List.copyOf(kept), error);
    }
}
