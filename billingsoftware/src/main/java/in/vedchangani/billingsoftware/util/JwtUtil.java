package in.vedchangani.billingsoftware.util;

import in.vedchangani.billingsoftware.service.impl.AppUserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtUtil {

    @Value("${jwt.secret.key}")
    private String SECRET_KEY;

    // Claim holding the account's token version at issue time (see UserEntity.tokenVersion).
    static final String TOKEN_VERSION_CLAIM = "tokenVersion";

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claiams = new HashMap<>();
        if (userDetails instanceof AppUserPrincipal principal) {
            claiams.put(TOKEN_VERSION_CLAIM, principal.getTokenVersion());
        }
        return createToken(claiams, userDetails.getUsername());
    }

    // Tokens issued before token versions existed carry no claim; they count as version 0, so
    // they keep working only until the account's version is first incremented.
    public int extractTokenVersion(String token) {
        Object version = extractAllClaims(token).get(TOKEN_VERSION_CLAIM);
        return version instanceof Number number ? number.intValue() : 0;
    }

    private String createToken(Map<String, Object> claiams, String subject) {
        return Jwts.builder()
                .setClaims(claiams)
                .setSubject(subject)
                .setIssuedAt(new Date(System.currentTimeMillis()))
                .setExpiration(new Date(System.currentTimeMillis() + 1000 * 60 * 60 * 10)) //10 hours expiration
                .signWith(SignatureAlgorithm.HS256, SECRET_KEY)
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .setSigningKey(SECRET_KEY)
                .parseClaimsJws(token)
                .getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token)
                && hasCurrentTokenVersion(token, userDetails));
    }

    // A token is only valid for the account's current session generation. Anything other than
    // the app's own principal (which always carries the version) is refused.
    private boolean hasCurrentTokenVersion(String token, UserDetails userDetails) {
        return userDetails instanceof AppUserPrincipal principal
                && extractTokenVersion(token) == principal.getTokenVersion();
    }
}
