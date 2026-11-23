package io.prsentinel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prsentinel.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SchemaTest extends PostgresTestBase {

    @Test
    void flywayCreatesPlanDataModel() {
        var tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name""").query(String.class).list();
        assertThat(tables).containsExactly("finding", "installation", "repository", "review", "scan_job", "verdict");
    }

    @Test
    void verdictConstraintsRejectInvalidValues() {
        long installation = jdbc.sql("INSERT INTO installation (github_installation_id) VALUES (1) RETURNING id")
                .query(Long.class).single();
        long repo = jdbc.sql("INSERT INTO repository (installation_id, nom_complet) VALUES (?, 'a/b') RETURNING id")
                .param(installation).query(Long.class).single();
        long review = jdbc.sql("INSERT INTO review (repository_id, pr_numero, commit_sha) VALUES (?, 1, ?) RETURNING id")
                .params(repo, "a".repeat(40)).query(Long.class).single();
        long finding = jdbc.sql("""
                INSERT INTO finding (review_id, outil, regle, fichier, gravite_outil)
                VALUES (?, 'semgrep', 'r', 'f', 'HIGH') RETURNING id""").param(review).query(Long.class).single();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO verdict (finding_id, decision, gravite, explication, confiance, modele)
                VALUES (?, 'PEUT_ETRE', 'HAUTE', 'x', 0.5, 'm')""").param(finding).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO verdict (finding_id, decision, gravite, explication, confiance, modele)
                VALUES (?, 'VRAI_PROBLEME', 'HAUTE', 'x', 1.5, 'm')""").param(finding).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
