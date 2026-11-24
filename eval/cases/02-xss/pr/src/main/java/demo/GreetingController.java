package demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class GreetingController {
    @GetMapping(value = "/hello", produces = "text/html")
    public void hello(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        String name = request.getParameter("name");
        response.getWriter().write("<h1>Bonjour " + name + "</h1>");
    }
}
