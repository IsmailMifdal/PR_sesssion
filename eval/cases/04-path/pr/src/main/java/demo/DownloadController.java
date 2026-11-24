package demo;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class DownloadController {
    @GetMapping("/download")
    public byte[] download(@RequestParam String file) throws Exception {
        java.io.File f = new java.io.File("/srv/uploads/" + file);
        return java.nio.file.Files.readAllBytes(f.toPath());
    }
}
