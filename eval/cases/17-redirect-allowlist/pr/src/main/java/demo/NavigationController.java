package demo;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;

public class NavigationController {
    private static final Set<String> ALLOWED = Set.of("/dashboard", "/settings", "/billing");

    public void go(String target, HttpServletResponse response) throws java.io.IOException {
        String destination = ALLOWED.contains(target) ? target : "/dashboard";
        response.sendRedirect(destination);
    }

    public void back(String target, HttpServletResponse response) throws java.io.IOException {
        if (!ALLOWED.contains(target)) {
            target = "/dashboard";
        }
        response.sendRedirect(target);
    }
}
