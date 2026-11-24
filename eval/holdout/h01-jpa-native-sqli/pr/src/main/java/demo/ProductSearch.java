package demo;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProductSearch {
    private final EntityManager em;

    public ProductSearch(EntityManager em) {
        this.em = em;
    }

    @GetMapping("/products")
    public List<?> search(@RequestParam String name) {
        return em.createNativeQuery("SELECT * FROM product WHERE name LIKE '%" + name + "%'").getResultList();
    }
}
