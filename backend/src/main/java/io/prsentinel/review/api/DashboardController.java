package io.prsentinel.review.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API en lecture seule du tableau de bord : revues, détail d'une PR, métriques et dernière évaluation. */
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

    public record FindingView(long id, String outil, String regle, String fichier, Integer ligne, String graviteOutil,
            String message, VerdictView verdict) {}

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

    private ReviewSummary summary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double duree = rs.getDouble("duree");
        return new ReviewSummary(rs.getLong("id"), rs.getString("depot"), rs.getInt("pr_numero"),
                rs.getString("commit_sha"), rs.getString("statut"), rs.getObject("debut", OffsetDateTime.class),
                rs.wasNull() ? null : duree, rs.getBigDecimal("cout_usd"), rs.getLong("tokens"), rs.getBoolean("bloquante"),
                rs.getInt("alertes"), rs.getInt("vrais"), rs.getInt("a_verifier"), rs.getInt("faux"),
                rs.getString("gravite_max"));
    }

    @GetMapping("/reviews")
    public List<ReviewSummary> reviews(@RequestParam(required = false) String depot,
            @RequestParam(required = false) String gravite, @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        String g = gravite != null && GRAVITES.contains(gravite) ? gravite : null;
        return jdbc.sql(SUMMARY_SQL + """
                WHERE (:depot::text IS NULL OR p.nom_complet = :depot)
                  AND (:gravite::text IS NULL OR EXISTS (SELECT 1 FROM finding f2 JOIN verdict v2 ON v2.finding_id = f2.id
                       WHERE f2.review_id = r.id AND v2.gravite = :gravite AND v2.decision <> 'FAUX_POSITIF'))
                GROUP BY r.id, p.nom_complet
                ORDER BY r.debut DESC, r.id DESC
                LIMIT :limit OFFSET :offset""")
                .param("depot", depot).param("gravite", g)
                .param("limit", Math.clamp(limit, 1, 200)).param("offset", Math.max(0, offset))
                .query(this::summary).list();
    }

    @GetMapping("/reviews/{id}")
    public ResponseEntity<ReviewDetail> review(@PathVariable long id) {
        var summary = jdbc.sql(SUMMARY_SQL + " WHERE r.id = :id GROUP BY r.id, p.nom_complet").param("id", id)
                .query(this::summary).optional();
        if (summary.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<FindingView> findings = jdbc.sql("""
                SELECT f.id, f.outil, f.regle, f.fichier, f.ligne, f.gravite_outil, f.message,
                       v.decision, v.gravite, v.explication, v.correctif, v.confiance, v.modele
                FROM finding f LEFT JOIN verdict v ON v.finding_id = f.id
                WHERE f.review_id = ?
                ORDER BY CASE v.decision WHEN 'VRAI_PROBLEME' THEN 0 WHEN 'A_VERIFIER' THEN 1 WHEN 'FAUX_POSITIF' THEN 3
                         ELSE 2 END,
                         CASE v.gravite WHEN 'CRITIQUE' THEN 0 WHEN 'HAUTE' THEN 1 WHEN 'MOYENNE' THEN 2 ELSE 3 END, f.id""")
                .param(id)
                .query((rs, i) -> new FindingView(rs.getLong("id"), rs.getString("outil"), rs.getString("regle"),
                        rs.getString("fichier"), (Integer) rs.getObject("ligne"), rs.getString("gravite_outil"),
                        rs.getString("message"), rs.getString("decision") == null ? null
                                : new VerdictView(rs.getString("decision"), rs.getString("gravite"),
                                        rs.getString("explication"), rs.getString("correctif"),
                                        rs.getBigDecimal("confiance"), rs.getString("modele"))))
                .list();
        return ResponseEntity.ok(new ReviewDetail(summary.get(), findings));
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>(jdbc.sql("""
                SELECT count(*) AS revues,
                       coalesce(avg(cout_usd), 0) AS cout_moyen_usd,
                       avg(extract(epoch FROM fin - debut)) FILTER (WHERE fin IS NOT NULL) AS duree_moyenne_s,
                       count(*) FILTER (WHERE bloquante) AS revues_bloquantes,
                       coalesce(sum(cout_usd), 0) AS cout_total_usd
                FROM review""").query().singleRow());
        m.putAll(jdbc.sql("""
                SELECT count(f.id) AS alertes,
                       count(v.id) FILTER (WHERE v.decision = 'VRAI_PROBLEME') AS vrais_problemes,
                       count(v.id) FILTER (WHERE v.decision = 'A_VERIFIER') AS a_verifier,
                       count(v.id) FILTER (WHERE v.decision = 'FAUX_POSITIF') AS faux_positifs
                FROM finding f LEFT JOIN verdict v ON v.finding_id = f.id""").query().singleRow());
        Map<String, Object> parGravite = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT gravite, count(*) AS n FROM verdict WHERE decision <> 'FAUX_POSITIF' GROUP BY gravite""")
                .query((rs, i) -> parGravite.put(rs.getString("gravite"), rs.getLong("n"))).list();
        m.put("par_gravite", parGravite);
        m.put("evaluation", latestEvaluation());
        return m;
    }

    private JsonNode latestEvaluation() {
        try {
            return Files.isRegularFile(evalReport) ? mapper.readTree(evalReport.toFile()) : null;
        } catch (IOException e) {
            return null;
        }
    }
}
