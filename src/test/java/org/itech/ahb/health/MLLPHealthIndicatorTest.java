package org.itech.ahb.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.itech.ahb.connection.ManagedHl7ConnectionListeners;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class MLLPHealthIndicatorTest {

  @Test
  void disabledRuntimeDoesNotClaimAnActiveGlobalListener() {
    ManagedHl7ConnectionListeners listeners = mock(ManagedHl7ConnectionListeners.class);
    assertThat(new MLLPHealthIndicator(listeners).health().getStatus()).isEqualTo(Status.UNKNOWN);
  }

  @Test
  void enabledRuntimeNeedsNoGlobalPortButReportsFailedOwnedListeners() {
    ManagedHl7ConnectionListeners listeners = mock(ManagedHl7ConnectionListeners.class);
    when(listeners.isEnabled()).thenReturn(true);
    when(listeners.runningConnections()).thenReturn(Map.of());
    MLLPHealthIndicator indicator = new MLLPHealthIndicator(listeners);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    when(listeners.runningConnections()).thenReturn(Map.of("one", true, "two", false));
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(indicator.health().getDetails().get("connections")).isEqualTo(Map.of("one", true, "two", false));
    when(listeners.runningConnections()).thenReturn(Map.of("one", true));
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }
}
