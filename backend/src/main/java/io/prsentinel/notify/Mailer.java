package io.prsentinel.notify;

import java.util.List;

/** Envoi d'e-mails en texte brut (aucun HTML : rien à injecter). Implémenté par SMTP, remplacé dans les tests. */
public interface Mailer {

    boolean enabled();

    void send(List<String> to, String subject, String text);
}
