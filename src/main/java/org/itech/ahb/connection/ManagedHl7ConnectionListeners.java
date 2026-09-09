package org.itech.ahb.connection;

import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.itech.ahb.mllp.HapiMLLPListener;
import org.itech.ahb.mllp.MLLPConfig;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.routing.MessageRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Owns the listener lifetime and port for each saved, active HL7 connection. */
@Component
@EnableConfigurationProperties(MLLPConfig.class)
public final class ManagedHl7ConnectionListeners implements Hl7ConnectionListeners {

  private final MLLPConfig config;
  private final MessageRouter router;
  private final Map<String, RunningListener> listeners = new HashMap<>();
  private boolean stopping;

  @Autowired
  public ManagedHl7ConnectionListeners(MLLPConfig config, MessageNormalizer normalizer) {
    this(config, (MessageRouter) normalizer);
  }

  public ManagedHl7ConnectionListeners(MLLPConfig config, MessageRouter router) {
    this.config = config;
    this.router = router;
  }

  @Override
  public synchronized void start(String connectionId, String sourceBindingId, int port) {
    if (!config.isEnabled() || stopping) throw new AnalyzerConnectionException(
      "Saved HL7 listener runtime is disabled or stopping"
    );
    RunningListener current = listeners.get(connectionId);
    if (
      current != null &&
      current.sourceBindingId().equals(sourceBindingId) &&
      current.listener().getPort() == port &&
      current.listener().isRunning()
    ) return;
    stop(connectionId);
    HapiMLLPListener listener = new HapiMLLPListener(port, sourceBindingId, router);
    try {
      listener.start();
    } catch (RuntimeException failure) {
      throw new AnalyzerConnectionException(
        "Cannot activate HL7 listener for Bridge connection " + connectionId,
        failure
      );
    }
    listeners.put(connectionId, new RunningListener(sourceBindingId, listener));
  }

  @Override
  public synchronized void stop(String connectionId) {
    RunningListener current = listeners.get(connectionId);
    if (current == null) return;
    current.listener().stop();
    // Failed drains retain ownership so a second listener cannot replace live work.
    listeners.remove(connectionId);
  }

  public synchronized Map<String, Boolean> runningConnections() {
    Map<String, Boolean> state = new HashMap<>();
    listeners.forEach((id, current) -> state.put(id, current.listener().isRunning()));
    return Map.copyOf(state);
  }

  public boolean isEnabled() {
    return config.isEnabled();
  }

  @PreDestroy
  public synchronized void stopAll() {
    stopping = true;
    RuntimeException failure = null;
    for (String id : List.copyOf(listeners.keySet())) {
      try {
        stop(id);
      } catch (RuntimeException exception) {
        if (failure == null) failure = exception;
        else failure.addSuppressed(exception);
      }
    }
    if (failure != null) throw failure;
  }

  private record RunningListener(String sourceBindingId, HapiMLLPListener listener) {}
}
