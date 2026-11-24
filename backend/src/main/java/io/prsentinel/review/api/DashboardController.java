package io.prsentinel.review.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.prsentinel.auth.Viewer;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API en lecture seule du tableau de bord : revues, détail d'une PR, métriques et dernière évaluation.
 * Un utilisateur connecté par GitHub ne voit que les dépôts relevés à sa connexion ; l'opérateur voit tout.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    static final Set<String> GRAVITES = Set.of("CRITIQUE", "HAUTE", "MOYENNE", "BASSE");

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final Path evalReport;

    public DashboardController(JdbcClient jdbc, ObjectMapper mapper,
            @Value("${sentinel.dashboard.eval-report:eval/report.json}") String evalReport) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.evalReport = Path.of(evalReport);
    }

    public record ReviewSummary(long id, String depot, int pr, String commit, String statut, OffsetDateTime debut,
            Double dureeS, BigDecimal coutUsd, long tokens, boolean bloquante, int alertes, int vraisProblemes,
            int aVerifier, int fauxPositifs, String graviteMax) {}

    public record VerdictView(String decision, String gravite, String explication, String correctif,
            BigDecimal confiance, String modele) {}

    /** @param monAvis retour de la personne connectée sur ce verdict : vrai (juste), faux (erroné), null (aucun) */
    public record FindingView(long id, String outil, String regle, String fichier, Integer ligne, String graviteOutil,
            String message, VerdictView verdict, Boolean monAvis) {}

    public record ReviewDetail(ReviewSummary revue, List<FindingView> alertes) {}

    private static final String SUMMARY_SQL = """
            SELECT r.id, p.nom_complet AS depot, r.pr_numero, r.commit_sha, r.statut, r.debut,
                   extract(epoch FROM r.fin - r.debut) AS duree, r.cout_usd, r.tokens_entree + r.tokens_sortie AS tokens,
                   r.bloquante,
                   count(f.id) AS alertes,
                   count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME') AS vrais,
                   count(v.id) FILTER (WHERE v.decision = 'A_VERIFIER') AS a_verifier,
                   count(v.id) FILTER (WHERE v.decision = 'FAUX_POSITIF') AS faux,
                   (array_agg(v.gravite ORDER BY CASE v.gravite WHEN 'CRITIQUE' THEN 0 WHEN 'HAUTE' THEN 1
                        WHEN 'MOYENNE' THEN 2 ELSE 3 END) FILTER (WHERE v.decision <> 'FAUX_POSITIF'))[1] AS gravite_max
            FROM review r
            JOIN repository p ON p.id = r.repository_id
            LEFT JOIN finding f ON f.review_id = r.id
            LEFT JOIN verdict v ON v.finding_id = f.id
            """;

    /** Condition de visibilité sur le dépôt {@code p} (paramètres {@code :admin} et {@code :uid}). */
    static final String VISIBLE = """
            (:admin OR p.nom_complet IN (SELECT depot FROM user_repo WHERE user_id = :uid))""";

    private ReviewSummary summary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double duree = rs.getDouble("duree");
        return new ReviewSummary(rs.getLong("id"), rs.getString("depot"), rs.getInt("pr_numero"),
                rs.getString("commit_sha"), rs.getString("statut"), rs.getObject("debut", OffsetDateTime.class),
                rs.wasNull() ? null : duree, rs.getBigDecimal("cout_usd"), rs.getLong("tokens"), rs.getBoolean("bloquante"),
                rs.getInt("alertes"), rs.getInt("vrais"), rs.getInt("a_verifier"), rs.getInt("faux"),
                rs.getString("gravite_max"));
    }

    @GetMapping("/reviews")
    public List<ReviewSummary> reviews(@RequestAttribute(Viewer.ATTR) Viewer viewer,
            @RequestParam(required = false) String depot,
            @RequestParam(required = false) String gravite, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String g = gravite != null && GRAVITES.contains(gravite) ? gravite : null;
        return jdbc.sql(SUMMARY_SQL + """
                WHERE """ + VISIBLE + """
                  AND (:depot::text IS NULL OR p.nom_complet = :depot)
                  AND (:gravite::text IS NULL OR EXISTS (SELECT 1 FROM finding f2 JOIN verdict v2 ON v2.finding_id = f2.id
                       WHERE f2.review_id = r.id AND v2.gravite = :gravite AND v2.decision <> 'FAUX_POSITIF'))
                GROUP BY r.id, p.nom_complet
                ORDER BY r.debut DESC, r.id DESC
                LIMIT :limit OFFSET :offset""")
                .param("admin", viewer.admin()).param("uid", viewer.userId())
                .param("depot", depot).param("gravite", g)
                .param("limit", Math.clamp(limit, 1, 200)).param("offset", Math.max(0, offset))
                .query(this::summary).list();
    }

    @GetMapping("/reviews/{id}")
    public ResponseEntity<ReviewDetail> review(@RequestAttribute(Viewer.ATTR) Viewer viewer, @PathVariable long id) {
        // Revue d'un dépôt non visible : 404, comme si elle n'existait pas.
        var summary = jdbc.sql(SUMMARY_SQL + " WHERE r.id = :id AND " + VISIBLE + " GROUP BY r.id, p.nom_complet")
                .param("id", id).param("admin", viewer.admin()).param("uid", viewer.userId())
                .query(this::summary).optional();
        if (summary.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<FindingView> findings = jdbc.sql("""
                SELECT f.id, f.outil, f.regle, f.fichier, f.ligne, f.gravite_outil, f.message,
                       v.decision, v.gravite, v.explication, v.correctif, v.confiance, v.modele, fb.utile AS mon_avis
                FROM finding f LEFT JOIN verdict v ON v.finding_id = f.id
                LEFT JOIN verdict_feedback fb ON fb.finding_id = f.id AND fb.auteur_cle = :auteur
                WHERE f.review_id = :id
                ORDER BY CASE v.decision WHEN 'VRAI_PROBLEME' THEN 0 WHEN 'A_VERIFIER' THEN 1 WHEN 'FAUX_POSITIF' THEN 3
                         ELSE 2 END,
                         CASE v.gravite WHEN 'CRITIQUE' THEN 0 WHEN 'HAUTE' THEN 1 WHEN 'MOYENNE' THEN 2 ELSE 3 END, f.id""")
                .param("id", id).param("auteur", FeedbackController.authorKey(viewer))
                .query((rs, i) -> new FindingView(rs.getLong("id"), rs.getString("outil"), rs.getString("regle"),
                        rs.getString("fichier"), (Integer) rs.getObject("ligne"), rs.getString("gravite_outil"),
                        rs.getString("message"), rs.getString("decision") == null ? null
                                : new VerdictView(rs.getString("decision"), rs.getString("gravite"),
                                        rs.getString("explication"), rs.getString("correctif"),
                                        rs.getBigDecimal("confiance"), rs.getString("modele")),
                        (Boolean) rs.getObject("mon_avis")))
                .list();
        return ResponseEntity.ok(new ReviewDetail(summary.get(), findings));
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics(@RequestAttribute(Viewer.ATTR) Viewer viewer) {
        Map<String, Object> m = new LinkedHashMap<>(jdbc.sql("""
                SELECT count(*) AS revues,
                       coalesce(avg(r.cout_usd), 0) AS cout_moyen_usd,
                       avg(extract(epoch FROM r.fin - r.debut)) FILTER (WHERE r.fin IS NOT NULL) AS duree_moyenne_s,
                       count(*) FILTER (WHERE r.bloquante) AS revues_bloquantes,
                       coalesce(sum(r.cout_usd), 0) AS cout_total_usd
                FROM review r JOIN repository p ON p.id = r.repository_id
                WHERE """ + VISIBLE).param("admin", viewer.admin()).param("uid", viewer.userId()).query().singleRow());
        m.putAll(jdbc.sql("""
                SELECT count(f.id) AS alertes,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME') AS vrais_problemes,
                       count(v.id) FILTER (WHERE v.decision = 'A_VERIFIER') AS a_verifier,
                       count(v.id) FILTER (WHERE v.decision = 'FAUX_POSITIF') AS faux_positifs
                FROM finding f JOIN review r ON r.id = f.review_id JOIN repository p ON p.id = r.repository_id
                LEFT JOIN verdict v ON v.finding_id = f.id
                WHERE """ + VISIBLE).param("admin", viewer.admin()).param("uid", viewer.userId()).query().singleRow());
        m.putAll(jdbc.sql("""
                SELECT count(*) FILTER (WHERE fb.utile) AS avis_justes,
                       count(*) FILTER (WHERE NOT fb.utile) AS avis_errones
                FROM verdict_feedback fb JOIN finding f ON f.id = fb.finding_id JOIN review r ON r.id = f.review_id
                JOIN repository p ON p.id = r.repository_id
                WHERE """ + VISIBLE).param("admin", viewer.admin()).param("uid", viewer.userId()).query().singleRow());
        Map<String, Object> parGravite = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT v.gravite, count(*) AS n
                FROM verdict v JOIN finding f ON f.id = v.finding_id JOIN review r ON r.id = f.review_id
                JOIN repository p ON p.id = r.repository_id
                WHERE v.decision <> 'FAUX_POSITIF' AND """ + VISIBLE + " GROUP BY v.gravite")
                .param("admin", viewer.admin()).param("uid", viewer.userId())
                .query((rs, i) -> parGravite.put(rs.getString("gravite"), rs.getLong("n"))).list();
        m.put("par_gravite", parGravite);
        m.put("evaluation", latestEvaluation());
        return m;
    }

    /** Qui est connecté, et quels dépôts il voit. */
    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(Viewer.ATTR) Viewer viewer) {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("login", viewer.login());
        me.put("nom", viewer.nom());
        me.put("avatarUrl", viewer.avatarUrl());
        me.put("admin", viewer.admin());
        me.put("depots", viewer.depots());
        return me;
    }

    private JsonNode latestEvaluation() {
        try {
            return Files.isRegularFile(evalReport) ? mapper.readTree(evalReport.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
