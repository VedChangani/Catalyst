package in.vedchangani.billingsoftware.config;

import in.vedchangani.billingsoftware.exception.RestAccessDeniedHandler;
import in.vedchangani.billingsoftware.exception.RestAuthenticationEntryPoint;
import in.vedchangani.billingsoftware.filter.JwtRequestFilter;
import in.vedchangani.billingsoftware.service.impl.AppUserDetailsService;
import lombok.RequiredArgsConstructor;
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
                        .requestMatchers("/login", "/encode", "/uploads/**").permitAll()

                        // ---- USER + CASHIER + ADMIN: product/category browsing (read-only) ----
                        .requestMatchers(HttpMethod.GET, "/categories", "/items").hasAnyRole("USER", "CASHIER", "ADMIN")

                        // ---- USER-only ONLINE ordering (ADMIN/CASHIER enter sales via /pos/orders) ----
                        // (order ownership filtering for "my-orders" is enforced in the service layer;
                        // see OrderServiceImpl.getMyOrders())
                        .requestMatchers(HttpMethod.POST, "/orders").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/orders/my-orders").hasAnyRole("USER", "ADMIN")
                        // Payment lifecycle: CASHIER is admitted here only so it can settle/release
                        // the POS orders it entered itself; the service layer rejects everything
                        // that is not the ONLINE order's customer or the POS order's creator.
                        .requestMatchers(HttpMethod.POST, "/orders/*/cancel", "/orders/*/fail-payment").hasAnyRole("USER", "CASHIER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/payments/create-order", "/payments/verify").hasAnyRole("USER", "CASHIER", "ADMIN")

                        // ---- CASHIER + ADMIN: POS workflow (order entry + customer lookup) ----
                        .requestMatchers("/pos/**").hasAnyRole("CASHIER", "ADMIN")

                        // ---- ADMIN-only: dashboard & administrative order operations ----
                        .requestMatchers(HttpMethod.GET, "/dashboard").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/orders/latest").hasRole("ADMIN")
                        // Customer order details; must stay after the literal /orders/latest and
                        // /orders/my-orders rules above. Ownership is enforced in the service layer.
                        .requestMatchers(HttpMethod.GET, "/orders/*").hasRole("USER")
                        .requestMatchers(HttpMethod.DELETE, "/orders/**").hasRole("ADMIN")

                        // ---- ADMIN-only: item/category/user management ----
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

    private UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173"));
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
