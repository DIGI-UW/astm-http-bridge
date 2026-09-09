package org.itech.ahb.health;

import lombok.extern.slf4j.Slf4j;
import org.itech.ahb.connection.ManagedHl7ConnectionListeners;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("mllp")
@ConditionalOnEnabledHealthIndicator("mllp")
@Slf4j
public class MLLPHealthIndicator implements HealthIndicator {

    private final ManagedHl7ConnectionListeners listeners;

    public MLLPHealthIndicator(ManagedHl7ConnectionListeners listeners) {
        this.listeners = listeners;
    }

    @Override
    public Health health() {
        if (!listeners.isEnabled()) {
            return Health.unknown()
                .withDetail("reason", "Saved HL7 listener runtime disabled")
                .build();
        }

        var state = listeners.runningConnections();
        return (state.values().stream().allMatch(Boolean::booleanValue) ? Health.up() : Health.down())
            .withDetail("connections", state)
            .withDetail("status", state.isEmpty() ? "no active saved HL7 connections" : "saved connection listeners")
            .build();
    }
}
