package org.itech.ahb.file;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable identity for one accession delivery, independent of file path or process lifetime. */
public final class FileDeliveryIdentity {

  private FileDeliveryIdentity() {}

  public static String contentHash(byte[] content) {
    return HexFormat.of().formatHex(sha256().digest(content));
  }

  public static String forAccession(String connectionId, String contentHash, String accessionNumber) {
    MessageDigest digest = sha256();
    for (String component : new String[] { connectionId, contentHash, accessionNumber }) {
      if (component == null || component.isBlank()) {
        throw new IllegalArgumentException("FILE delivery requires connection, content hash, and accession");
      }
      byte[] bytes = component.getBytes(StandardCharsets.UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
    }
    return "file-v1:" + HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required for FILE delivery", exception);
    }
  }
}
