package io.prsentinel.auth;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Utilisateurs, codes de connexion à usage unique et sessions du tableau de bord. */
@Component
public class SessionStore {

    public static final String SESSION_PREFIX = "pss_";
    static final Duration CODE_TTL = Duration.ofMinutes(2);

    private final JdbcClient jdbc;

    public SessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record GitHubUser(long githubId, String login, String nom, String avatarUrl) {}

    /** Crée ou met à jour l'utilisateur et remplace la liste de ses dépôts visibles. */
    @Transactional
    public long saveLogin(GitHubUser user, List<String> depots) {
        long id = jdbc.sql("""
                INSERT INTO app_user (github_id, login, nom, avatar_url) VALUES (?, ?, ?, ?)
                ON CONFLICT (github_id) DO UPDATE SET login = excluded.login, nom = excluded.nom,
                    avatar_url = excluded.avatar_url, derniere_connexion = now()
                RETURNING id""")
                .params(user.githubId(), user.login(), user.nom(), safeAvatar(user.avatarUrl()))
                .query(Long.class).single();
        jdbc.sql("DELETE FROM user_repo WHERE user_id = ?").param(id).update();
        for (String depot : depots.stream().distinct().toList()) {
            jdbc.sql("INSERT INTO user_repo (user_id, depot) VALUES (?, ?)").params(id, depot).update();
        }
        return id;
    }

    /** Code transmis au front par redirection, échangé une seule fois contre une session. */
    public String createLoginCode(long userId) {
        jdbc.sql("DELETE FROM login_code WHERE expire_le < now() - interval '1 hour'").update();
        String code = Tokens.random("plc_");
        jdbc.sql("INSERT INTO login_code (empreinte, user_id, expire_le) VALUES (?, ?, ?)")
                .params(Tokens.hash(code), userId, at(Instant.now().plus(CODE_TTL))).update();
        return code;
    }

    /** Consomme le code (atomique : une seule requête peut le valider). */
    public Optional<Long> redeemLoginCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                UPDATE login_code SET utilise = true
                WHERE empreinte = ? AND NOT utilise AND expire_le > now()
                RETURNING user_id""").param(Tokens.hash(code)).query(Long.class).optional();
    }

    public record NewSession(String token, OffsetDateTime expiresAt) {}

    public NewSession createSession(long userId, Duration ttl) {
        jdbc.sql("DELETE FROM user_session WHERE expire_le < now()").update();
        String token = Tokens.random(SESSION_PREFIX);
        OffsetDateTime expires = at(Instant.now().plus(ttl));
        jdbc.sql("INSERT INTO user_session (empreinte, user_id, expire_le) VALUES (?, ?, ?)")
                .params(Tokens.hash(token), userId, expires).update();
        return new NewSession(token, expires);
    }

    public Optional<Viewer> findSession(String token) {
        if (token == null || !token.startsWith(SESSION_PREFIX)) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT u.id, u.login, u.nom, u.avatar_url,
                       coalesce(array_agg(r.depot ORDER BY r.depot) FILTER (WHERE r.depot IS NOT NULL), '{}') AS depots
                FROM user_session s JOIN app_user u ON u.id = s.user_id
                LEFT JOIN user_repo r ON r.user_id = u.id
                WHERE s.empreinte = ? AND s.expire_le > now()
                GROUP BY u.id""")
                .param(Tokens.hash(token))
                .query((rs, i) -> new Viewer(false, rs.getLong("id"), rs.getString("login"), rs.getString("nom"),
                        rs.getString("avatar_url"), List.of((String[]) rs.getArray("depots").getArray())))
                .optional();
    }

    public void deleteSession(String token) {
        if (token != null) {
            jdbc.sql("DELETE FROM user_session WHERE empreinte = ?").param(Tokens.hash(token)).update();
        }
    }

    /** Seules les images servies par GitHub sont gardées (le front les affiche ; CSP restreinte à ce domaine). */
    static String safeAvatar(String url) {
        return url != null && url.startsWith("https://avatars.githubusercontent.com/") ? url : null;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
