package place.icomb.archiver.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The one definition of what makes an MCP access token valid, used by the {@code /api/mcp/**}
 * resource server and by {@link McpBearerFilter} on the rest of the API, so the two can never
 * disagree about who holds a good token.
 *
 * <p>The audience is checked against a fixed value (the configured issuer plus the MCP resource
 * path), the same value the token generator stamps on every token it issues. It is deliberately not
 * derived from the incoming request's host: that agrees with the issuer in production but not under
 * test, which hits {@code localhost} on a random port.
 */
public final class McpJwtDecoders {

  private McpJwtDecoders() {}

  public static NimbusJwtDecoder build(String issuer, JWKSource<SecurityContext> jwkSource) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
    String expectedAudience = issuer + "/api/mcp/sse";
    OAuth2TokenValidator<Jwt> audienceValidator =
        jwt ->
            jwt.getAudience().contains(expectedAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "The aud claim is not valid", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
    return decoder;
  }
}
