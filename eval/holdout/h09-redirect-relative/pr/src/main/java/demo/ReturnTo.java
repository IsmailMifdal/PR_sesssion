package demo;

import jakarta.servlet.http.HttpServletResponse;

public class ReturnTo {
    /** Chemin interne uniquement : commence par un seul « / », lettres, chiffres, tirets et « / ». */
    private static final String INTERNAL_PATH = "^/(?![/\\\\])[a-z0-9/-]*$";

    public void afterLogin(String returnTo, HttpServletResponse response) throws java.io.IOException {
        if (returnTo == null || !returnTo.matches(INTERNAL_PATH) || returnTo.contains("//")) {
            returnTo = "/";
        }
        response.sendRedirect(returnTo);
    }
}
