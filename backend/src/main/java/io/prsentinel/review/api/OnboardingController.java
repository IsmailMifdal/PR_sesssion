package io.prsentinel.review.api;

import io.prsentinel.auth.Viewer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Avancement du démarrage d'un utilisateur : app installée, dépôts choisis, première revue reçue. Les
 * installations et dépôts sont ceux relevés à la connexion ; une nouvelle connexion les rafraîchit.
 */
@RestController
public class OnboardingController {

    private final JdbcClient jdbc;
    private final String installUrl;

    public OnboardingController(JdbcClient jdbc, @Value("${sentinel.github.app-slug:}") String appSlug,
            @Value("${sentinel.github.git-url}") String gitUrl) {
        this.jdbc = jdbc;
        // Le slug vient de la configuration, jamais de la requête ; un slug invalide désactive le lien.
        this.installUrl = appSlug != null && appSlug.matches("[a-z0-9][a-z0-9-]{0,99}")
                ? gitUrl.replaceAll("/+$", "") + "/apps/" + appSlug + "/installations/new" : null;
    }

    @GetMapping("/api/onboarding")
    public Map<String, Object> onboarding(@RequestAttribute(Viewer.ATTR) Viewer viewer) {
        int installations = viewer.admin()
                ? jdbc.sql("SELECT count(*) FROM installation").query(Integer.class).single()
                : jdbc.sql("SELECT count(*) FROM user_installation WHERE user_id = ?").param(viewer.userId())
                        .query(Integer.class).single();
        int depots = viewer.admin()
                ? jdbc.sql("SELECT count(*) FROM repository").query(Integer.class).single()
                : viewer.depots().size();
        var first = jdbc.sql("""
                SELECT r.id, count(*) OVER () AS n FROM review r JOIN repository p ON p.id = r.repository_id
                WHERE r.statut <> 'IGNOREE' AND """ + DashboardController.VISIBLE + """
                 ORDER BY r.debut ASC, r.id ASC LIMIT 1""")
                .param("admin", viewer.admin()).param("uid", viewer.userId())
                .query((rs, i) -> new long[] {rs.getLong("id"), rs.getLong("n")}).optional();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("installUrl", installUrl);
        out.put("installations", installations);
        out.put("depots", depots);
        out.put("revues", first.map(f -> f[1]).orElse(0L));
        out.put("premiereRevueId", first.map(f -> f[0]).orElse(null));
        return out;
    }
}
