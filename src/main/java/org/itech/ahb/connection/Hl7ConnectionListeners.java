package org.itech.ahb.connection;

/** Lifecycle boundary for HL7 listeners owned by saved Bridge connections. */
public interface Hl7ConnectionListeners {
  void start(String connectionId, String sourceBindingId, int port);
  void stop(String connectionId);
}
