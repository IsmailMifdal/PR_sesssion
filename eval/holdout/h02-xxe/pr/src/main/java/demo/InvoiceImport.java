package demo;

import jakarta.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.springframework.web.bind.annotation.*;

@RestController
public class InvoiceImport {
    @PostMapping("/invoices/import")
    public String importXml(HttpServletRequest request) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(request.getInputStream());
        return doc.getDocumentElement().getAttribute("number");
    }
}
