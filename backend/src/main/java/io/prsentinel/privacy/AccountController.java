package io.prsentinel.privacy;

import io.prsentinel.auth.Viewer;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Droits RGPD de l'utilisateur connecté par GitHub : accès (export) et effacement de son compte. */
@RestController
public class AccountController {

    private final JdbcClient jdbc;
    private final DataPurge purge;
    private final RetentionJob retention;

    public AccountController(JdbcClient jdbc, DataPurge purge, RetentionJob retention) {
        this.jdbc = jdbc;
        this.purge = purge;
        this.retention = retention;
    }

    /** Toutes les données personnelles détenues sur l'utilisateur, en JSON téléchargeable. */
    @GetMapping("/api/me/export")
    public ResponseEntity<Map<String, Object>> export(@RequestAttribute(Viewer.ATTR) Viewer viewer) {
        if (viewer.admin()) {
            return ResponseEntity.badRequest().build(); // l'opérateur n'a pas de compte personnel
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profil", jdbc.sql("""
                SELECT github_id AS "githubId", login, nom, avatar_url AS "avatarUrl", cree_le AS "creeLe",
                       derniere_connexion AS "derniereConnexion"
                FROM app_user WHERE id = ?""").param(viewer.userId()).query().singleRow());
        out.put("depots", viewer.depots());
        out.put("installations", jdbc.sql("""
                SELECT github_installation_id AS "installationId", compte FROM user_installation WHERE user_id = ?
                ORDER BY compte""").param(viewer.userId()).query().listOfRows());
        out.put("avis", jdbc.sql("""
                SELECT finding_id AS "findingId", utile, commentaire, cree_le AS date FROM verdict_feedback
                WHERE auteur_cle = ? ORDER BY cree_le""").param("u:" + viewer.userId()).query().listOfRows());
        out.put("sessionsActives", jdbc.sql("""
                SELECT count(*) FROM user_session WHERE user_id = ? AND expire_le > now()""")
                .param(viewer.userId()).query(Integer.class).single());
        out.put("exporteLe", OffsetDateTime.now());
        out.put("conservationRevuesJours", retention.days());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pr-sentinel-mes-donnees.json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(out);
    }

    /**
     * Supprime le compte et ses sessions (déconnexion immédiate). Les revues appartiennent au compte GitHub qui a
     * installé l'app, pas à la personne : elles restent, jusqu'à leur durée de conservation ou la désinstallation.
     */
    @DeleteMapping("/api/me")
    public ResponseEntity<Void> delete(@RequestAttribute(Viewer.ATTR) Viewer viewer) {
        if (viewer.admin()) {
            return ResponseEntity.badRequest().build();
        }
        purge.user(viewer.userId());
        return ResponseEntity.noContent().build();
    }

    /** Informations publiques de conservation, affichées dans la page « Mon compte ». */
    @GetMapping("/api/me/privacy")
    public Map<String, Object> privacy() {
        return Map.of("conservationRevuesJours", retention.days(), "sousTraitants",
                List.of("GitHub", "OpenAI", "Stripe", "Railway", "Vercel"));
    }
}
