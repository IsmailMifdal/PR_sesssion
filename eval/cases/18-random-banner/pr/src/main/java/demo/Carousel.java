package demo;

import java.util.List;
import java.util.Random;

/** Bannière mise en avant sur la page d'accueil : aucune valeur secrète. */
public class Carousel {
    public String featured(List<String> banners) {
        return banners.get(new Random().nextInt(banners.size()));
    }
}
