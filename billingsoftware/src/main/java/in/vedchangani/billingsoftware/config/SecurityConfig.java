package in.vedchangani.billingsoftware.config;

import in.vedchangani.billingsoftware.exception.RestAccessDeniedHandler;
import in.vedchangani.billingsoftware.exception.RestAuthenticationEntryPoint;
import in.vedchangani.billingsoftware.filter.JwtRequestFilter;
import in.vedchangani.billingsoftware.service.impl.AppUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AppUserDetailsService appUserDetailsService;
    private final JwtRequestFilter jwtRequestFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception{
        http.cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/uploads/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/register").permitAll()

                        .requestMatchers(HttpMethod.GET, "/categories", "/items").hasAnyRole("USER", "CASHIER", "ADMIN")

                        .requestMatchers(HttpMethod.POST, "/orders").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/orders/my-orders").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/orders/*/cancel", "/orders/*/fail-payment").hasAnyRole("USER", "CASHIER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/payments/create-order", "/payments/verify").hasAnyRole("USER", "CASHIER", "ADMIN")

                        .requestMatchers("/pos/**").hasRole("CASHIER")

                        .requestMatchers(HttpMethod.GET, "/dashboard").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/orders/latest").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/orders/*").hasAnyRole("USER", "CASHIER")

                        .requestMatchers("/account/**").hasAnyRole("USER", "CASHIER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/activity/me").hasAnyRole("USER", "CASHIER", "ADMIN")

                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        .anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsFilter corsFilter() {
        return new CorsFilter(corsConfigurationSource());
    }

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    static List<String> validatedOrigins(List<String> configured) {
        List<String> origins = configured == null ? List.of() : configured.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException("app.cors.allowed-origins (APP_CORS_ALLOWED_ORIGINS) must list at least one origin");
        }
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalStateException("app.cors.allowed-origins must list explicit origins; '*' is not allowed with credentials");
        }
        return origins;
    }

    private UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(validatedOrigins(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(appUserDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(authProvider);
    }


}
