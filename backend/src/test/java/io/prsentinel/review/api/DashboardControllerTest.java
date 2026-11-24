package io.prsentinel.review.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.prsentinel.review.ReviewStore;
import io.prsentinel.scan.Finding;
import io.prsentinel.support.PostgresTestBase;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class DashboardControllerTest extends PostgresTestBase {

    static final String TOKEN = "Bearer dashboard-test-token-0123456789abcdef";

    @Autowired
    MockMvc mvc;
    @Autowired
    ReviewStore store;
    long critical;

    @BeforeEach
    void seed() {
        critical = store.startReview(99, "acme/widgets", 7, "a".repeat(40));
        var f = store.saveFindings(critical, List.of(
                new Finding("semgrep", "sqli", "ERROR", "Repo.java", 3, "<script>x</script>"),
                new Finding("gitleaks", "aws", "SECRET", "test/A.java", 5, "clé")), List.of("h1", "h2"));
        store.saveVerdict(f.get(0).id(), "VRAI_PROBLEME", "CRITIQUE", "Injection.", "fix();", 0.93, "gpt-4o");
        store.saveVerdict(f.get(1).id(), "FAUX_POSITIF", "BASSE", "Clé de test.", null, 0.9, "gpt-4o-mini");
        store.finish(critical, "TERMINEE", 2000, 200, new BigDecimal("0.0042"), true, null);

        long clean = store.startReview(99, "acme/other", 2, "b".repeat(40));
        store.finish(clean, "TERMINEE", 0, 0, BigDecimal.ZERO, false, null);
    }

    MockHttpServletRequestBuilder authed(String url) {
        return get(url).header("Authorization", TOKEN);
    }

    @Test
    void requiresBearerToken() throws Exception {
        mvc.perform(get("/api/reviews")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/reviews").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void disabledWithoutStrongToken() throws Exception {
        var filter = new DashboardAuthFilter("short");
        var res = new org.springframework.mock.web.MockHttpServletResponse();
        var req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/reviews");
        req.setServletPath("/api/reviews");
        filter.doFilter(req, res, (a, b) -> {});
        assertThat(res.getStatus()).isEqualTo(503);
    }

    @Test
    void listsReviewsWithCountsAndFilters() throws Exception {
        mvc.perform(authed("/api/reviews")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].depot").value("acme/widgets"))
                .andExpect(jsonPath("$[1].vraisProblemes").value(1))
                .andExpect(jsonPath("$[1].fauxPositifs").value(1))
                .andExpect(jsonPath("$[1].graviteMax").value("CRITIQUE"))
                .andExpect(jsonPath("$[1].bloquante").value(true));
        mvc.perform(authed("/api/reviews?gravite=CRITIQUE")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(authed("/api/reviews?gravite=BASSE")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(authed("/api/reviews?depot=acme/other")).andExpect(jsonPath("$[0].pr").value(2));
        mvc.perform(authed("/api/reviews?gravite=DROP")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void reviewDetailOrdersRealProblemsFirst() throws Exception {
        mvc.perform(authed("/api/reviews/" + critical)).andExpect(status().isOk())
                .andExpect(jsonPath("$.alertes[0].verdict.decision").value("VRAI_PROBLEME"))
                .andExpect(jsonPath("$.alertes[0].verdict.correctif").value("fix();"))
                .andExpect(jsonPath("$.alertes[1].verdict.decision").value("FAUX_POSITIF"));
        mvc.perform(authed("/api/reviews/999999")).andExpect(status().isNotFound());
    }

    @Test
    void metrics() throws Exception {
        mvc.perform(authed("/api/metrics")).andExpect(status().isOk())
                .andExpect(jsonPath("$.revues").value(2))
                .andExpect(jsonPath("$.vrais_problemes").value(1))
                .andExpect(jsonPath("$.faux_positifs").value(1))
                .andExpect(jsonPath("$.par_gravite.CRITIQUE").value(1));
    }

    @Test
    void onboardingForTheOperator() throws Exception {
        mvc.perform(authed("/api/onboarding")).andExpect(status().isOk())
                .andExpect(jsonPath("$.installUrl").doesNotExist()) // slug non configuré : pas de lien
                .andExpect(jsonPath("$.installations").value(1))
                .andExpect(jsonPath("$.depots").value(2))
                .andExpect(jsonPath("$.revues").value(2))
                .andExpect(jsonPath("$.premiereRevueId").value(critical));
    }

    @Test
    void corsPreflightForConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/reviews").header("Origin", "https://pr-sentinel.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://pr-sentinel.example"));
        mvc.perform(options("/api/reviews").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden());
    }
}
