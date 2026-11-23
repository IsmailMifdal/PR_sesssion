package demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class LoginController {
    @GetMapping("/after-login")
    public void afterLogin(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        String next = request.getParameter("next");
        response.sendRedirect(next);
    }
}
