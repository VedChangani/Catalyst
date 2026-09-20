package in.vedchangani.billingsoftware.controller;

import in.vedchangani.billingsoftware.TestMobiles;
import in.vedchangani.billingsoftware.entity.UserEntity;
import in.vedchangani.billingsoftware.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The whole role/URL rule set in one place. The other security tests each cover a feature; this one
 * walks EVERY API route as anonymous, USER, CASHIER and ADMIN through the real SecurityConfig filter
 * chain, so a change to a URL rule for any single route (or a new route that falls into the wrong
 * rule) fails here regardless of which feature owns it. It is the "direct URL/API access is
 * protected, not only the navigation" guarantee.
 *
 * Only the role gate is asserted. A permitted call is made with an empty body / unknown id so it
 * fails validation or lookup (never a 401/403) and changes no data. Ownership rules inside the
 * service layer (e.g. a customer reading someone else's order) have their own tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessControlMatrixTest {

    private static final String U = "USER";
    private static final String C = "CASHIER";
    private static final String A = "ADMIN";

    private record Route(HttpMethod method, String path, Set<String> allowed) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    // Source of truth: the rules in SecurityConfig, one row per route the application exposes.
    private static final List<Route> ROUTES = List.of(
            // catalog browsing: any signed-in role
            route(HttpMethod.GET, "/items", U, C, A),
            route(HttpMethod.GET, "/categories", U, C, A),
            // online ordering: customers only
            route(HttpMethod.POST, "/orders", U),
            route(HttpMethod.GET, "/orders/my-orders", U),
            route(HttpMethod.GET, "/orders/some-order", U, C),
            route(HttpMethod.GET, "/orders/latest", A),
            // payment lifecycle: any signed-in role (ownership is enforced per order in the service)
            route(HttpMethod.POST, "/orders/some-order/cancel", U, C, A),
            route(HttpMethod.POST, "/orders/some-order/fail-payment", U, C, A),
            route(HttpMethod.POST, "/payments/create-order", U, C, A),
            route(HttpMethod.POST, "/payments/verify", U, C, A),
            // POS: cashiers only - not even an admin
            route(HttpMethod.POST, "/pos/orders", C),
            route(HttpMethod.GET, "/pos/sales", C),
            route(HttpMethod.GET, "/pos/customers", C),
            route(HttpMethod.GET, "/pos/no-such-route", C),
            // own account and own activity: any signed-in role
            route(HttpMethod.GET, "/account/me", U, C, A),
            route(HttpMethod.PATCH, "/account/me", U, C, A),
            route(HttpMethod.PATCH, "/account/me/password", U, C, A),
            route(HttpMethod.GET, "/activity/me", U, C, A),
            // administration: admins only
            route(HttpMethod.GET, "/dashboard", A),
            route(HttpMethod.GET, "/admin/orders", A),
            route(HttpMethod.GET, "/admin/analytics", A),
            route(HttpMethod.GET, "/admin/activity", A),
            route(HttpMethod.GET, "/admin/cashiers", A),
            route(HttpMethod.POST, "/admin/cashiers", A),
            route(HttpMethod.PATCH, "/admin/cashiers/some-cashier/status", A),
            route(HttpMethod.POST, "/admin/cashiers/some-cashier/reset-password", A),
            route(HttpMethod.POST, "/admin/items", A),
            route(HttpMethod.PUT, "/admin/items/some-item", A),
            route(HttpMethod.PATCH, "/admin/items/some-item/stock", A),
            route(HttpMethod.DELETE, "/admin/items/some-item", A),
            route(HttpMethod.POST, "/admin/categories", A),
            route(HttpMethod.DELETE, "/admin/categories/some-category", A),
            // a route that does not exist is still behind the /admin/** rule, so it cannot be probed
            route(HttpMethod.GET, "/admin/no-such-route", A));

    private static Route route(HttpMethod method, String path, String... roles) {
        return new Route(method, path, Set.of(roles));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;

    private UserEntity customer;
    private UserEntity cashier;
    private UserEntity admin;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        customer = account("Matrix Customer", "matrix-customer-" + s + "@example.com", "ROLE_USER");
        cashier = account("Matrix Cashier", "matrix-cashier-" + s + "@example.com", "ROLE_CASHIER");
        admin = account("Matrix Admin", "matrix-admin-" + s + "@example.com", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll(List.of(customer, cashier, admin));
    }

    private UserEntity account(String name, String email, String role) {
        return userRepository.save(UserEntity.builder()
                .userId("uid-" + UUID.randomUUID()).email(email).name(name).role(role)
                .mobile(TestMobiles.next()).password("not-used").build());
    }

    private int status(Route route, UserEntity actor) throws Exception {
        MockHttpServletRequestBuilder builder = request(route.method(), route.path());
        if (route.method() != HttpMethod.GET && route.method() != HttpMethod.DELETE) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        if (actor != null) {
            builder.with(user(actor.getEmail()).roles(actor.getRole().replace("ROLE_", "")));
        }
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    @Test
    void everyRoute_enforcesItsRoles_forAnonymousUserCashierAndAdmin() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Route route : ROUTES) {
            int anonymous = status(route, null);
            if (anonymous != 401) {
                violations.add(route + " as anonymous answered " + anonymous + " (expected 401)");
            }
            for (UserEntity actor : List.of(customer, cashier, admin)) {
                String role = actor.getRole().replace("ROLE_", "");
                int result = status(route, actor);
                if (route.allowed().contains(role)) {
                    if (result == 401 || result == 403) {
                        violations.add(route + " as " + role + " answered " + result + " (should be allowed)");
                    }
                } else if (result != 403) {
                    violations.add(route + " as " + role + " answered " + result + " (expected 403)");
                }
            }
        }

        assertTrue(violations.isEmpty(), "role rules differ from the expected matrix:\n" + String.join("\n", violations));
    }
}
