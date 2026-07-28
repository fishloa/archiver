package place.icomb.archiver.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/**
 * Decides whether a request's TCP peer is allowed to assert an identity via the {@code
 * X-Auth-Email} header.
 *
 * <p>Two kinds of entry are supported: CIDR ranges, and hostnames resolved through DNS. Hostnames
 * exist because container addresses are dynamic — the reverse proxy's address changes on every
 * redeploy, so a pinned IP would rot. Resolution results are cached briefly so this costs no DNS
 * lookup per request while still self-healing across redeploys.
 *
 * <p>Fails closed throughout: a malformed CIDR or an unresolvable hostname is dropped, never
 * widened into a permissive default.
 */
public class TrustedPeerResolver {

  private static final Logger log = LoggerFactory.getLogger(TrustedPeerResolver.class);

  private final List<IpAddressMatcher> matchers;
  private final List<String> hostnames;
  private final Duration cacheTtl;

  private volatile Set<String> cachedHostAddresses = Set.of();
  private volatile Instant cacheExpiresAt = Instant.EPOCH;

  public TrustedPeerResolver(List<String> cidrs, List<String> hostnames, Duration cacheTtl) {
    this.matchers = buildMatchers(cidrs);
    this.hostnames = List.copyOf(hostnames);
    this.cacheTtl = cacheTtl;
  }

  private static List<IpAddressMatcher> buildMatchers(List<String> cidrs) {
    var built = new ArrayList<IpAddressMatcher>();
    for (String cidr : cidrs) {
      if (cidr == null || cidr.isBlank()) {
        continue;
      }
      try {
        built.add(new IpAddressMatcher(cidr.trim()));
      } catch (IllegalArgumentException e) {
        log.warn("Ignoring malformed trusted CIDR '{}': {}", cidr, e.getMessage());
      }
    }
    return List.copyOf(built);
  }

  public boolean isTrusted(String remoteAddr) {
    if (remoteAddr == null || remoteAddr.isBlank()) {
      return false;
    }
    String peer = remoteAddr.trim();

    for (IpAddressMatcher matcher : matchers) {
      try {
        if (matcher.matches(peer)) {
          return true;
        }
      } catch (IllegalArgumentException e) {
        // Peer was not a parseable address for this matcher — treat as no match.
        log.debug("Peer '{}' not comparable against a configured CIDR: {}", peer, e.getMessage());
      }
    }

    return resolvedHostAddresses().contains(peer);
  }

  private Set<String> resolvedHostAddresses() {
    if (hostnames.isEmpty()) {
      return Set.of();
    }
    Instant now = Instant.now();
    if (now.isBefore(cacheExpiresAt)) {
      return cachedHostAddresses;
    }

    var resolved = new HashSet<String>();
    for (String hostname : hostnames) {
      if (hostname == null || hostname.isBlank()) {
        continue;
      }
      try {
        for (InetAddress address : InetAddress.getAllByName(hostname.trim())) {
          resolved.add(address.getHostAddress());
        }
      } catch (UnknownHostException e) {
        log.warn("Trusted proxy hostname '{}' did not resolve; ignoring it", hostname);
      }
    }

    cachedHostAddresses = Set.copyOf(resolved);
    cacheExpiresAt = now.plus(cacheTtl);
    return cachedHostAddresses;
  }
}
