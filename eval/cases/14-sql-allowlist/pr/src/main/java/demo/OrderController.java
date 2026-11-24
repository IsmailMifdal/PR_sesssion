package demo;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OrderController {
    /** Colonnes triables : liste blanche fermée. */
    private static final Set<String> SORTABLE = Set.of("created_at", "total", "ref");
    private final JdbcTemplate jdbc;

    public OrderController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/orders")
    public List<String> orders(@RequestParam String sort) {
        if (!SORTABLE.contains(sort)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tri inconnu");
        }
        return jdbc.queryForList("SELECT ref FROM orders ORDER BY " + sort, String.class);
    }
}
