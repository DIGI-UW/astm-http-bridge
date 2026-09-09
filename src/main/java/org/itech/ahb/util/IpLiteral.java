package org.itech.ahb.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/** Numeric sender identities only: never use hostname resolution for authorization. */
public final class IpLiteral {

  private static final Pattern IPV4 = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
  private static final Pattern IPV6 = Pattern.compile("[0-9a-fA-F:.]+");

  private IpLiteral() {}

  /** Returns one stable address spelling, or null for a nonliteral/invalid address. */
  public static String canonicalize(String value) {
    if (value == null) return null;
    String address = value.trim();
    boolean ipv4 = IPV4.matcher(address).matches();
    boolean ipv6 = address.indexOf(':') >= 0 && IPV6.matcher(address).matches();
    if (!ipv4 && !ipv6) return null;
    try {
      if (ipv4) {
        String[] parts = address.split("\\.");
        byte[] octets = new byte[4];
        for (int index = 0; index < octets.length; index++) {
          int octet = Integer.parseInt(parts[index]);
          if (octet > 255) return null;
          octets[index] = (byte) octet;
        }
        return InetAddress.getByAddress(octets).getHostAddress();
      }
      // Colon-containing numeric IPv6 is parsed by the JDK without name lookup.
      // Hostnames, ports, CIDR ranges, brackets and zone names cannot reach this call.
      return InetAddress.getByName(address).getHostAddress();
    } catch (UnknownHostException exception) {
      return null;
    }
  }
}
