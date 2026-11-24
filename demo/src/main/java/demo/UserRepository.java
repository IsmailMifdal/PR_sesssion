package demo;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Noms des utilisateurs ayant cette adresse e-mail. */
    public List<String> findByEmail(String email) {
        String sql = "SELECT name FROM users WHERE email = '" + email + "'";
        return jdbc.queryForList(sql, String.class);
    }
}
