package demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.formLogin(form -> form.loginPage("/login"))
            .authorizeHttpRequests(a -> a.anyRequest().authenticated());
        http.csrf().disable();
        return http.build();
    }
}
