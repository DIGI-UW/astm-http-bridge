package org.itech.ahb.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.mllp.MLLPConfig;
import org.itech.ahb.normalizer.AnalyzerIdentifier;
import org.itech.ahb.normalizer.MessageNormalizer;
import org.itech.ahb.profile.AnalyzerProfileCatalog;
import org.itech.ahb.profile.ProfileFingerprintService;
import org.itech.ahb.routing.HttpForwardingRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

/** Real saved catalog -> owned MLLP socket -> normalizer -> HTTP delivery. */
class Hl7SavedConnectionTest {

  @TempDir
  Path directory;

  private final ObjectMapper mapper = new ObjectMapper();
  private final LinkedBlockingQueue<Delivery> deliveries = new LinkedBlockingQueue<>();
  private HttpServer receiver;
  private ObjectNode profile;
  private ManagedHl7ConnectionListeners listeners;
  private AnalyzerRuntimeRegistry registry;
  private AnalyzerConnectionCatalog catalog;

  @BeforeEach
  void setUp() throws Exception {
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext("/", exchange -> {
      deliveries.add(
        new Delivery(
          exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_SOURCE_ID),
          exchange.getRequestHeaders().getFirst(HttpForwardingRouter.HEADER_ANALYZER_ID),
          new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        )
      );
      exchange.sendResponseHeaders(200, 2);
      exchange.getResponseBody().write("OK".getBytes(StandardCharsets.UTF_8));
      exchange.close();
    });
    receiver.start();
    profile = (ObjectNode) mapper.readTree(
      Files.readString(Path.of("contracts/analyzer/v1/fixtures/analyzer-profile-astm.json"))
    );
    profile.withObject("profileMeta").put("id", "saved-hl7-fixture").put("displayName", "Saved HL7 fixture");
    profile.putObject("protocol").put("name", "HL7").put("version", "2.5.1");
    profile.withObject("configDefaults").remove("extractionOverrides");
    ObjectNode recognition = profile.putObject("controlResultRecognition");
    recognition.put("mode", "RULES");
    recognition
      .putObject("rules")
      .putObject("control-label")
      .put("ruleType", "FIELD_EQUALS")
      .put("targetField", "OBX.3.2")
      .put("operand", "CONTROL");
    ProfileFingerprintService fingerprints = new ProfileFingerprintService();
    profile.withObject("catalog").put("recognitionFingerprint", fingerprints.recognitionFingerprint(recognition));
    profile.withObject("catalog").put("revisionFingerprint", fingerprints.revisionFingerprint(profile));
    boot(true);
  }

  @AfterEach
  void close() {
    try {
      if (listeners != null) listeners.stopAll();
    } finally {
      if (receiver != null) receiver.stop(0);
    }
  }

  @Test
  void samePeerConnectionsKeepTheirIdentityAcrossDiskBackedRestartAndIndependentDeactivation() throws Exception {
    int firstPort = freePort();
    String first = create("oe-first", firstPort);
    activate(first);
    int secondPort = freePort();
    String second = create("oe-second", secondPort);
    ObjectNode activation = command(second, "ACTIVATE");
    assertThat(catalog.applyRuntimeCommand(activation).path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(catalog.applyRuntimeCommand(activation).path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(listeners.runningConnections()).hasSize(2);
    assertDelivery(firstPort, first, "oe-first");
    assertDelivery(secondPort, second, "oe-second");
    listeners.stopAll();
    assertClosed(firstPort);
    assertClosed(secondPort);
    boot(true); // Fresh catalogs, registry and listeners; only the files survive.
    assertThat(catalog.require(first).path("actualRuntimeState").asText()).isEqualTo("ACTIVE");
    assertThat(listeners.runningConnections()).containsEntry(first, true).containsEntry(second, true);
    assertDelivery(firstPort, first, "oe-first");
    assertDelivery(secondPort, second, "oe-second");
    catalog.applyRuntimeCommand(command(first, "DEACTIVATE"));
    assertClosed(firstPort);
    assertThat(registry.findAnalyzerId("connection:" + first)).isEmpty();
    assertDelivery(secondPort, second, "oe-second");
    assertThat(catalog.applyRuntimeCommand(command(first, "DEACTIVATE")).path("actualRuntimeState").asText()).isEqualTo(
      "INACTIVE"
    );
  }

  @Test
  void occupiedPortFailsActivationWithoutAuthorizingAConnectionAndCanBeRetried() throws Exception {
    String id;
    int port;
    try (ServerSocket occupied = new ServerSocket(0)) {
      port = occupied.getLocalPort();
      id = create("oe-occupied", port);
      String connectionId = id;
      assertThatThrownBy(() -> activate(connectionId)).isInstanceOf(AnalyzerConnectionException.class);
      assertThat(catalog.require(id).path("actualRuntimeState").asText()).isEqualTo("INACTIVE");
      assertThat(registry.findAnalyzerId("connection:" + id)).isEmpty();
      assertThat(listeners.runningConnections()).isEmpty();
      assertThat(occupied.isClosed()).isFalse();
    }
    activate(id);
    assertDelivery(port, id, "oe-occupied");
  }

  @Test
  void failedReplacementAndRestartKeepTheLastActivatedPort() throws Exception {
    int originalPort = freePort();
    String id = create("oe-replacement", originalPort);
    activate(id);
    try (ServerSocket occupied = new ServerSocket(0)) {
      ObjectNode saved = catalog.require(id);
      ObjectNode update = mapper
        .createObjectNode()
        .put("schemaVersion", "1.0")
        .put("requestId", UUID.randomUUID().toString())
        .put("connectionId", id)
        .put("expectedConfigRevision", 1)
        .put("displayName", "oe-replacement");
      update.set("profileRef", saved.path("profileRef").deepCopy());
      update.putObject("values").put("port", occupied.getLocalPort());
      catalog.update(update);
      ObjectNode replacement = command(id, "ACTIVATE").put("expectedConfigRevision", 2);
      assertThatThrownBy(() -> catalog.applyRuntimeCommand(replacement)).isInstanceOf(
        AnalyzerConnectionException.class
      );
      assertDelivery(originalPort, id, "oe-replacement");
      listeners.stopAll();
      boot(true);
      assertThat(catalog.require(id).path("configRevision").asInt()).isEqualTo(2);
      assertThat(catalog.require(id).path("activeRuntimeRef").path("configRevision").asInt()).isEqualTo(1);
      assertDelivery(originalPort, id, "oe-replacement");
      catalog.applyRuntimeCommand(command(id, "DEACTIVATE").put("expectedConfigRevision", 2));
      assertClosed(originalPort);
    }
  }

  @Test
  void disabledRuntimeDoesNotAuthorizeOrOpenSavedConnections() throws Exception {
    listeners.stopAll();
    boot(false);
    int port = freePort();
    String id = create("oe-disabled", port);
    assertThatThrownBy(() -> activate(id))
      .isInstanceOf(AnalyzerConnectionException.class)
      .hasMessageContaining("disabled");
    assertThat(registry.findAnalyzerId("connection:" + id)).isEmpty();
    assertClosed(port);
  }

  private void boot(boolean enabled) throws Exception {
    AnalyzerProfileCatalog profiles = new AnalyzerProfileCatalog(
      directory.resolve("profiles"),
      List.of(new ByteArrayResource(mapper.writeValueAsBytes(profile))),
      mapper,
      Clock.systemUTC()
    );
    registry = new AnalyzerRuntimeRegistry();
    HTTPForwardServerConfigurationProperties forwarding = new HTTPForwardServerConfigurationProperties();
    forwarding.setUri(URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/analyzer"));
    MessageNormalizer normalizer = new MessageNormalizer(
      new HttpForwardingRouter(forwarding, null, null, registry),
      new AnalyzerIdentifier(registry),
      registry,
      null,
      null,
      null
    );
    MLLPConfig config = new MLLPConfig();
    config.setEnabled(enabled);
    listeners = new ManagedHl7ConnectionListeners(config, normalizer);
    catalog = new AnalyzerConnectionCatalog(
      directory.resolve("connections"),
      profiles,
      mapper,
      Clock.systemUTC(),
      UUID::randomUUID,
      new BridgeAnalyzerConnectionRuntime(registry, null, null, null, listeners)
    );
  }

  private String create(String analyzerId, int port) {
    ObjectNode request = mapper.createObjectNode();
    request
      .put("schemaVersion", "1.0")
      .put("requestId", UUID.randomUUID().toString())
      .put("clientAnalyzerId", analyzerId)
      .put("displayName", analyzerId);
    request
      .putObject("profileRef")
      .put("profileId", "saved-hl7-fixture")
      .put("revision", 1)
      .put("fingerprint", profile.path("catalog").path("revisionFingerprint").asText());
    request.putObject("values").put("port", port);
    return catalog.create(request).path("connectionId").asText();
  }

  private void activate(String id) {
    assertThat(catalog.applyRuntimeCommand(command(id, "ACTIVATE")).path("actualRuntimeState").asText()).isEqualTo(
      "ACTIVE"
    );
  }

  private ObjectNode command(String id, String action) {
    return mapper
      .createObjectNode()
      .put("schemaVersion", "1.0")
      .put("commandId", UUID.randomUUID().toString())
      .put("connectionId", id)
      .put("action", action)
      .put("expectedConfigRevision", 1);
  }

  private void assertDelivery(int port, String id, String analyzerId) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      // Both sockets use the same peer IP and a spoofed sender; neither determines identity.
      String message =
        "MSH|^~\\&|SPOOF|OTHER|OE|LAB|20260909120000||ORU^R01|MSG-1|P|2.5.1\r" +
        "PID|1||PATIENT\rOBR|1||ACCESSION|PANEL\rOBX|1|NM|T1^CONTROL||2|unit\r";
      socket.getOutputStream().write(("\u000b" + message + "\u001c\r").getBytes(StandardCharsets.UTF_8));
      StringBuilder ack = new StringBuilder();
      int next;
      while ((next = socket.getInputStream().read()) != -1 && next != 0x1c) ack.append((char) next);
      assertThat(ack.toString()).contains("MSA|AA|");
    }
    Delivery delivery = deliveries.poll(5, TimeUnit.SECONDS);
    assertThat(delivery).isNotNull();
    assertThat(delivery.source()).isEqualTo("connection:" + id);
    assertThat(delivery.analyzer()).isEqualTo(analyzerId);
    assertThat(delivery.body()).contains("ACCESSION", "CONTROL");
  }

  private static void assertClosed(int port) {
    assertThatThrownBy(() -> {
      try (Socket socket = new Socket("127.0.0.1", port)) {}
    }).isInstanceOf(IOException.class);
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private record Delivery(String source, String analyzer, String body) {}
}
