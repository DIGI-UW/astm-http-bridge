package org.itech.ahb.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IpLiteralTest {

  @ParameterizedTest
  @CsvSource(
    {
      "192.000.002.025,192.0.2.25",
      "2001:DB8::1,2001:db8:0:0:0:0:0:1",
      "2001:0db8:0000:0000:0000:0000:0000:0001,2001:db8:0:0:0:0:0:1",
      "::ffff:192.0.2.25,192.0.2.25"
    }
  )
  void normalizesEquivalentLiteralSpellings(String input, String expected) {
    assertEquals(expected, IpLiteral.canonicalize(input));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
    strings = {
      "localhost",
      "dead.beef",
      "127.1",
      "256.1.2.3",
      "192.0.2.25:8080",
      "::gg",
      "1:2:3:4:5:6:7:8:9",
      "[::1]",
      "fe80::1%eth0",
      "2001:db8::1/64"
    }
  )
  void rejectsAnythingOtherThanUnscopedNumericAddresses(String value) {
    assertNull(IpLiteral.canonicalize(value));
  }
}
