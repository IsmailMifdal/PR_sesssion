package demo;

import java.util.List;
import java.util.Map;

public class Diagnostics {
    /** Rapports autorisés : commandes fixes, aucun argument venant de l'utilisateur. */
    private static final Map<String, List<String>> REPORTS = Map.of(
            "disk", List.of("df", "-h", "/var/lib/app"),
            "uptime", List.of("uptime"));

    public String run(String report) throws Exception {
        List<String> command = REPORTS.get(report);
        if (command == null) {
            throw new IllegalArgumentException("rapport inconnu");
        }
        Process p = new ProcessBuilder(command).start();
        return new String(p.getInputStream().readAllBytes());
    }
}
