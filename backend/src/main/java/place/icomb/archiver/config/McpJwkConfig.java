package place.icomb.archiver.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.Base64URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loads the single RSA keypair that signs every JWT this Authorization Server issues. Same "private
 * key as a base64 stack secret" pattern already used for {@code APPLE_KEY_P8_B64} -- generate once
 * with {@code openssl genrsa 2048 | base64 | tr -d '\n'}, store the result as {@code
 * MCP_OAUTH_SIGNING_KEY_B64} in the deploy environment. No rotation for this version; a single key
 * is sufficient for a single-operator archive.
 *
 * <p>Modern OpenSSL (3.x) emits {@code genrsa} output as a PKCS#8 PEM ({@code BEGIN PRIVATE KEY}),
 * which {@link PKCS8EncodedKeySpec} reads natively -- no third-party PEM parser is needed. The
 * decoded private key is an {@link RSAPrivateCrtKey}, which carries the modulus and public exponent
 * alongside the private exponent, so the matching public key material for the JWK can be read
 * straight off it via {@link Base64URL#encode(java.math.BigInteger)} instead of reconstructing a
 * separate {@code RSAPublicKey} through another {@code KeyFactory} round-trip.
 */
@Configuration
public class McpJwkConfig {

  private final String signingKeyB64;

  public McpJwkConfig(@Value("${archiver.mcp.oauth.signing-key-b64:}") String signingKeyB64) {
    if (signingKeyB64 == null || signingKeyB64.isBlank()) {
      throw new IllegalStateException(
          "archiver.mcp.oauth.signing-key-b64 is not set. Generate one with: "
              + "openssl genrsa 2048 | base64 | tr -d '\\n'");
    }
    this.signingKeyB64 = signingKeyB64;
  }

  @Bean
  public JWKSource<SecurityContext> jwkSource() {
    try {
      String pem = new String(Base64.getDecoder().decode(signingKeyB64), StandardCharsets.US_ASCII);
      String base64Der =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replaceAll("\\s", "");
      byte[] derBytes = Base64.getDecoder().decode(base64Der);

      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      RSAPrivateCrtKey privateKey =
          (RSAPrivateCrtKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(derBytes));

      RSAKey rsaKey =
          new RSAKey.Builder(
                  Base64URL.encode(privateKey.getModulus()),
                  Base64URL.encode(privateKey.getPublicExponent()))
              .privateKey(privateKey)
              .keyUse(KeyUse.SIGNATURE)
              .algorithm(JWSAlgorithm.RS256)
              .keyID(UUID.randomUUID().toString())
              .build();

      return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load MCP OAuth signing key", e);
    }
  }
}
