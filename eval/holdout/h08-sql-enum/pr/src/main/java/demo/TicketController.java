package demo;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class TicketController {
    enum Status { OPEN, PENDING, CLOSED }

    private final JdbcTemplate jdbc;

    public TicketController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/tickets")
    public List<String> byStatus(@RequestParam String status) {
        Status s = Status.valueOf(status.toUpperCase());
        return jdbc.queryForList("SELECT title FROM ticket WHERE status = '" + s.name() + "'", String.class);
    }
}
