package demo;

import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.net.URL;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class PreviewController {
    @GetMapping("/preview")
    public String preview(@RequestParam String url) throws java.io.IOException {
        try (InputStream in = new URL(url).openStream()) {
            return new String(in.readNBytes(100_000));
        }
    }
}
