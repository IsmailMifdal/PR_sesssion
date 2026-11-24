package demo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ThemeUploader {
    private static final String THEMES = "/srv/themes/";

    @PostMapping("/themes")
    public void upload(@RequestParam("file") MultipartFile file) throws Exception {
        try (InputStream in = file.getInputStream(); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(THEMES + entry.getName());
                target.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(target)) {
                    zip.transferTo(out);
                }
            }
        }
    }
}
