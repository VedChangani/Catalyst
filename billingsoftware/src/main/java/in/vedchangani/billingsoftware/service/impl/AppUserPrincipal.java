package in.vedchangani.billingsoftware.service.impl;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

// The authenticated principal: Spring's User plus the account's current token version, so the JWT
// filter can compare it with the version inside a presented token without a second lookup.
public class AppUserPrincipal extends User {

    private final int tokenVersion;

    public AppUserPrincipal(String username, String password, boolean enabled,
                            Collection<? extends GrantedAuthority> authorities, int tokenVersion) {
        super(username, password, enabled, true, true, true, authorities);
        this.tokenVersion = tokenVersion;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }
}
