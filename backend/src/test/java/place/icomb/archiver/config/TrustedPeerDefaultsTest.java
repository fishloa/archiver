package place.icomb.archiver.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * The trusted ranges the application ships with, read from application.yml itself.
 *
 * <p>The frontend asks the backend who a user is and forwards the proxy's email header; the backend
 * believes that header only from a trusted address. The stack's containers are recreated on every
 * deploy and the Docker network behind them has moved subnet before (10.0.9.x, then 10.0.18.x),
 * which silently made every signed-in user look like a stranger. The default must be a range that
 * covers the host's container networks, never a single subnet that happens to be current.
 */
class TrustedPeerDefaultsTest {

  private TrustedPeerResolver shipped() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    Properties p = yaml.getObject();
    String raw = p.getProperty("archiver.auth.trusted-cidrs");
    // ${AUTH_TRUSTED_CIDRS:default} -> default
    String defaults = raw.substring(raw.indexOf(':') + 1, raw.lastIndexOf('}'));
    List<String> cidrs = Arrays.stream(defaults.split(",")).map(String::trim).toList();
    return new TrustedPeerResolver(cidrs, List.of(), Duration.ofSeconds(30));
  }

  @Test
  void theContainerNetworksOnTheHostAreTrustedWhicheverSubnetTheyAreOn() {
    TrustedPeerResolver resolver = shipped();
    assertThat(resolver.isTrusted("10.0.9.4")).as("the old subnet").isTrue();
    assertThat(resolver.isTrusted("10.0.18.6")).as("the current subnet").isTrue();
    assertThat(resolver.isTrusted("10.0.30.2")).as("a future one").isTrue();
  }

  @Test
  void loopbackAndTheLanRangeStayTrusted() {
    TrustedPeerResolver resolver = shipped();
    assertThat(resolver.isTrusted("127.0.0.1")).isTrue();
    assertThat(resolver.isTrusted("192.168.17.20")).isTrue();
  }

  @Test
  void addressesOutsideThoseRangesAreNotTrusted() {
    TrustedPeerResolver resolver = shipped();
    assertThat(resolver.isTrusted("10.1.0.5")).isFalse();
    assertThat(resolver.isTrusted("8.8.8.8")).isFalse();
    assertThat(resolver.isTrusted("192.168.30.4")).isFalse();
    assertThat(resolver.isTrusted(null)).isFalse();
  }
}
