package demo;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ObjectInputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class ImportController {
    @PostMapping("/import")
    public Object importData(HttpServletRequest request) throws Exception {
        ObjectInputStream in = new ObjectInputStream(request.getInputStream());
        return in.readObject();
    }
}
