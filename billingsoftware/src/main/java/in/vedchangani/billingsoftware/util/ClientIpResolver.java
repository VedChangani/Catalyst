package in.vedchangani.billingsoftware.util;

import in.vedchangani.billingsoftware.config.PasswordResetProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;

/**
 * The client address used as a rate-limit key.
 *
 * By default it is the TCP peer address; X-Forwarded-For is ignored, because any client can send
 * that header and would otherwise choose its own "IP" (and so dodge the limit or frame another
 * address). Only when app.password-reset.trust-forwarded-for is enabled - i.e. the app is deployed
 * behind the bundled nginx, which sets {@code X-Forwarded-For: $proxy_add_x_forwarded_for} - is the
 * header used, and then only its LAST entry: the proxy appends the address it actually saw, so
 * earlier (client-supplied) entries are never trusted. Enable it only when the backend is not
 * reachable except through that proxy.
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final PasswordResetProperties properties;

    public String resolve(HttpServletRequest request) {
        if (properties.isTrustForwardedFor()) {
            String last = null;
            for (String line : Collections.list(request.getHeaders(FORWARDED_FOR))) {
                for (String part : line.split(",")) {
                    if (StringUtils.hasText(part)) {
                        last = part.trim();
                    }
                }
            }
            if (last != null) {
                return last;
            }
        }
        return request.getRemoteAddr();
    }
}
