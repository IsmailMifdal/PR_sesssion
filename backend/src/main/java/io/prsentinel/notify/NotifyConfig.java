package io.prsentinel.notify;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Notifications : Slack (clé de chiffrement requise, {@code SENTINEL_ENCRYPTION_KEY}) et e-mail (SMTP,
 * {@code SPRING_MAIL_HOST} et {@code SENTINEL_MAIL_FROM}). Chaque canal est désactivé si sa configuration manque.
 */
@Configuration(proxyBeanMethods = false)
public class NotifyConfig {

    /** Paramètres partagés ; {@code box} est {@code null} sans clé de chiffrement valide. */
    public record NotifySettings(SecretBox box, String frontUrl, String gitUrl) {

        public boolean slackEnabled() {
            return box != null;
        }
    }

    @Bean
    NotifySettings notifySettings(@Value("${sentinel.notify.encryption-key:}") String key,
            @Value("${sentinel.auth.front-url:}") String frontUrl, @Value("${sentinel.github.git-url}") String gitUrl) {
        return new NotifySettings(SecretBox.fromBase64(key), frontUrl, gitUrl);
    }

    @Bean
    SlackClient slackClient(@Value("${sentinel.notify.slack-prefix:https://hooks.slack.com/services/}") String prefix) {
        return new SlackClient(prefix, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    @Bean
    Mailer mailer(ObjectProvider<JavaMailSender> sender, @Value("${sentinel.notify.mail-from:}") String from) {
        JavaMailSender smtp = sender.getIfAvailable();
        return new Mailer() {
            @Override
            public boolean enabled() {
                return smtp != null && !from.isBlank();
            }

            @Override
            public void send(List<String> to, String subject, String text) {
                if (!enabled()) {
                    throw new IllegalStateException("e-mail non configuré");
                }
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(from);
                msg.setBcc(to.toArray(new String[0])); // les destinataires ne voient pas les adresses des autres
                msg.setSubject(subject);
                msg.setText(text);
                smtp.send(msg);
            }
        };
    }

    @Bean
    ReviewNotifier reviewNotifier(NotificationStore store, NotifySettings s, SlackClient slack) {
        return new ReviewNotifier(store, s.box(), slack, s.gitUrl(), s.frontUrl());
    }

    @Bean
    WeeklyReport weeklyReport(NotificationStore store, Mailer mailer, NotifySettings s) {
        return new WeeklyReport(store, mailer, s.frontUrl(), Clock.systemUTC());
    }
}
