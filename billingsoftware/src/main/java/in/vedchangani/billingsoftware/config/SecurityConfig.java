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
                        // ---- Public endpoints ----
                        .requestMatchers("/login", "/uploads/**").permitAll()
                        // Customer self-registration (always ROLE_USER - see UserServiceImpl.registerCustomer)
                        .requestMatchers(HttpMethod.POST, "/register").permitAll()
                        // Customer forgot-password (emailed one-time code); anonymous by nature. Only enabled
                        // ROLE_USER accounts can ever complete it - see PasswordResetServiceImpl.
                        .requestMatchers(HttpMethod.POST, "/forgot-password", "/reset-password").permitAll()

                        // ---- USER + CASHIER + ADMIN: product/category browsing (read-only) ----
                        .requestMatchers(HttpMethod.GET, "/categories", "/items").hasAnyRole("USER", "CASHIER", "ADMIN")

                        // ---- USER-only ONLINE ordering (the CASHIER enters store sales via /pos/orders) ----
                        // (order ownership filtering for "my-orders" is enforced in the service layer;
                        // see OrderServiceImpl.getMyOrders())
                        .requestMatchers(HttpMethod.POST, "/orders").hasRole("USER")
                        // the customer's own purchase history; ADMIN uses /admin/orders, CASHIER /pos/sales
                        .requestMatchers(HttpMethod.GET, "/orders/my-orders").hasRole("USER")
                        // Payment lifecycle. The service layer only lets the ONLINE order's customer (user)
                        // or the POS order's creator (createdBy) act. CASHIER is admitted so it can settle/
                        // release the POS sales it entered; ADMIN only so it can still settle a historical
                        // POS order it entered before POS creation became CASHIER-only.
                        .requestMatchers(HttpMethod.POST, "/orders/*/cancel", "/orders/*/fail-payment").hasAnyRole("USER", "CASHIER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/payments/create-order", "/payments/verify").hasAnyRole("USER", "CASHIER", "ADMIN")

                        // ---- CASHIER only: POS workflow (order entry, customer lookup, own sales). ADMIN
                        // no longer creates POS sales; it reviews them (historical and new) in /admin/orders. ----
                        .requestMatchers("/pos/**").hasRole("CASHIER")

                        // ---- ADMIN-only: dashboard & administrative order operations ----
                        .requestMatchers(HttpMethod.GET, "/dashboard").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/orders/latest").hasRole("ADMIN")
                        // Customer order details; must stay after the literal /orders/latest and
                        // /orders/my-orders rules above. Ownership is enforced in the service layer.
                        // (USER: orders it owns as customer; CASHIER: POS orders it entered - see
                        // OrderServiceImpl.getMyOrder.)
                        .requestMatchers(HttpMethod.GET, "/orders/*").hasAnyRole("USER", "CASHIER")

                        // ---- Any signed-in role: the caller's own account (profile + password) ----
                        .requestMatchers("/account/**").hasAnyRole("USER", "CASHIER", "ADMIN")
                        // ---- Any signed-in role: the caller's own activity log (admin's system-wide
                        // log is /admin/activity, covered by the ADMIN rule below) ----
                        .requestMatchers(HttpMethod.GET, "/activity/me").hasAnyRole("USER", "CASHIER", "ADMIN")

                        // ---- ADMIN-only: All Orders, analytics, items, categories, cashiers, system activity ----
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

    // Explicit frontend origin(s) from app.cors.allowed-origins (APP_CORS_ALLOWED_ORIGINS,
    // comma-separated). Credentials are allowed, so a wildcard is refused at startup.
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
