package io.prsentinel.review.api;

import io.prsentinel.auth.Viewer;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retours 👍/👎 sur les verdicts de l'agent. Ils mesurent la précision réelle chez les clients et fournissent
 * les cas à ajouter au banc d'évaluation (verdicts contestés). Seules les alertes des dépôts visibles par la
 * personne connectée peuvent être notées ou listées.
 */
@RestController
public class FeedbackController {

    static final int MAX_COMMENT = 500;

    private final JdbcClient jdbc;

    public FeedbackController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static String authorKey(Viewer viewer) {
        return viewer.admin() ? "operateur" : "u:" + viewer.userId();
    }

    public record FeedbackRequest(Boolean utile, String commentaire) {}

    @PutMapping("/api/findings/{id}/feedback")
    public ResponseEntity<Void> give(@RequestAttribute(Viewer.ATTR) Viewer viewer, @PathVariable long id,
            @RequestBody FeedbackRequest req) {
        if (req == null || req.utile() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!canSee(viewer, id)) {
            return ResponseEntity.notFound().build();
        }
        String comment = req.commentaire() == null ? null : req.commentaire().strip();
        if (comment != null && comment.isEmpty()) {
            comment = null;
        } else if (comment != null && comment.length() > MAX_COMMENT) {
            comment = comment.substring(0, MAX_COMMENT);
        }
        jdbc.sql("""
                INSERT INTO verdict_feedback (finding_id, auteur_cle, auteur_login, utile, commentaire)
                VALUES (:id, :cle, :login, :utile, :commentaire)
                ON CONFLICT (finding_id, auteur_cle) DO UPDATE SET utile = excluded.utile,
                    commentaire = excluded.commentaire, auteur_login = excluded.auteur_login, cree_le = now()""")
                .param("id", id).param("cle", authorKey(viewer)).param("login", viewer.login())
                .param("utile", req.utile()).param("commentaire", comment).update();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/findings/{id}/feedback")
    public ResponseEntity<Void> withdraw(@RequestAttribute(Viewer.ATTR) Viewer viewer, @PathVariable long id) {
        if (!canSee(viewer, id)) {
            return ResponseEntity.notFound().build();
        }
        jdbc.sql("DELETE FROM verdict_feedback WHERE finding_id = ? AND auteur_cle = ?")
                .params(id, authorKey(viewer)).update();
        return ResponseEntity.noContent().build();
    }

    public record Contested(long findingId, long revueId, String depot, int pr, String outil, String regle,
            String fichier, Integer ligne, String decision, String gravite, String modele, boolean utile,
            String commentaire, String auteur, OffsetDateTime date) {}

    /** Derniers retours (par défaut les verdicts jugés faux) : matière première du banc d'évaluation. */
    @GetMapping("/api/feedback")
    public List<Contested> list(@RequestAttribute(Viewer.ATTR) Viewer viewer,
            @RequestParam(defaultValue = "false") boolean utile, @RequestParam(defaultValue = "20") int limit) {
        return jdbc.sql("""
                SELECT fb.finding_id, r.id AS revue_id, p.nom_complet, r.pr_numero, f.outil, f.regle, f.fichier, f.ligne,
                       v.decision, v.gravite, v.modele, fb.utile, fb.commentaire, fb.auteur_login, fb.cree_le
                FROM verdict_feedback fb JOIN finding f ON f.id = fb.finding_id
                JOIN review r ON r.id = f.review_id JOIN repository p ON p.id = r.repository_id
                LEFT JOIN verdict v ON v.finding_id = f.id
                WHERE fb.utile = :utile AND """ + DashboardController.VISIBLE + """
                 ORDER BY fb.cree_le DESC LIMIT :limit""")
                .param("utile", utile).param("admin", viewer.admin()).param("uid", viewer.userId())
                .param("limit", Math.clamp(limit, 1, 100))
                .query((rs, i) -> new Contested(rs.getLong("finding_id"), rs.getLong("revue_id"),
                        rs.getString("nom_complet"), rs.getInt("pr_numero"), rs.getString("outil"), rs.getString("regle"),
                        rs.getString("fichier"), (Integer) rs.getObject("ligne"), rs.getString("decision"),
                        rs.getString("gravite"), rs.getString("modele"), rs.getBoolean("utile"),
                        rs.getString("commentaire"), rs.getString("auteur_login"),
                        rs.getObject("cree_le", OffsetDateTime.class)))
                .list();
    }

    /** L'alerte existe et appartient à un dépôt visible (sinon 404, comme si elle n'existait pas). */
    private boolean canSee(Viewer viewer, long findingId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM finding f JOIN review r ON r.id = f.review_id
                    JOIN repository p ON p.id = r.repository_id
                    WHERE f.id = :id AND """ + DashboardController.VISIBLE + ")")
                .param("id", findingId).param("admin", viewer.admin()).param("uid", viewer.userId())
                .query(Boolean.class).single();
    }
}
