package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.profile.ControlRecognitionRule;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMessageHandlerReplayTest {

  @TempDir
  Path directory;

  private final ObjectMapper json = new ObjectMapper();
  private final List<JsonNode> deliveries = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger rejectRequest = new AtomicInteger(-1);
  private final AtomicBoolean loseAcknowledgment = new AtomicBoolean();
  private HttpServer server;
  private AnalyzerRuntimeRegistry registry;
  private HTTPForwardServerConfigurationProperties config;
  private AnalyzerEntry entry;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/analyzer/fhir", exchange -> {
      deliveries.add(json.readTree(exchange.getRequestBody().readAllBytes()));
      if (!loseAcknowledgment.get()) {
        exchange.sendResponseHeaders(deliveries.size() == rejectRequest.get() ? 503 : 200, -1);
      }
      exchange.close();
    });
    server.start();
    config = new HTTPForwardServerConfigurationProperties();
    config.setUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/analyzer"));
    config.setReadTimeoutSeconds(2);
    registry = new AnalyzerRuntimeRegistry();
    entry = new AnalyzerEntry();
    entry.setId("oe-1");
    entry.setName("Test connection");
    entry.setBridgeConnectionId("connection-1");
    entry.setProfileId("site.replay-test");
    entry.setProfileRevision(1);
    entry.setExpectedProtocol("FILE");
    entry.setColumnMappings(Map.of("Sample", "sampleId", "Test", "testCode", "Result", "result"));
    entry.setDelimiter(",");
    entry.setTabularResultValueSelection(TabularResultValueSelection.resultOnly());
    entry.setControlResultRecognition(
      ControlResultRecognition.rules(
        List.of(new ControlRecognitionRule("control-prefix", "SPECIMEN_ID_PREFIX", null, "QC-", null, null))
      )
    );
    entry.setRecognitionFingerprint("sha256:" + "1".repeat(64));
    registry.register(directory + "#connection-1", entry);
  }

  @AfterEach
  void stopServer() {
    if (server != null) server.stop(0);
  }

  @Test
  void partialDeliveryAndRestartReusePatientAndControlIdentitiesEvenAfterRename() throws Exception {
    Path file = csv("results.csv", "PATIENT-1,T1,2\nQC-1,T1,3\n");
    rejectRequest.set(2);
    assertThrows(FileMessageHandler.FileProcessingException.class, () -> handler().processFile(file, "oe-1"));
    assertEquals(2, deliveries.size());
    Path renamed = Files.move(file, directory.resolve("renamed.csv"));

    handler().processFile(renamed, "oe-1");

    assertEquals(4, deliveries.size());
    assertEquals(id(0), id(2));
    assertEquals(id(1), id(3));
    assertNotEquals(id(0), id(1));
    assertEquals(
      java.util.Set.of("PATIENT", "CONTROL"),
      deliveries.subList(0, 2).stream().map(this::classification).collect(java.util.stream.Collectors.toSet())
    );
  }

  @Test
  void lostAcknowledgmentAndNewHandlerReuseTheAcceptedDeliveryIdentity() throws Exception {
    Path file = csv("results.csv", "PATIENT-1,T1,2\n");
    loseAcknowledgment.set(true);
    assertThrows(IOException.class, () -> handler().processFile(file, "oe-1"));
    assertFalse(deliveries.isEmpty());
    int beforeRetry = deliveries.size();
    loseAcknowledgment.set(false);

    handler().processFile(file, "oe-1");

    assertTrue(deliveries.size() > beforeRetry);
    assertEquals(
      1,
      deliveries.stream().map(bundle -> bundle.path("identifier").path("value").asText()).distinct().count()
    );
  }

  @Test
  void changedContentAndDifferentConnectionHaveDifferentDeliveryIdentities() throws Exception {
    Path file = csv("results.csv", "PATIENT-1,T1,2\n");
    handler().processFile(file, "oe-1");
    Files.writeString(file, "Sample,Test,Result\nPATIENT-1,T1,3\n");
    handler().processFile(file, "oe-1");
    entry.setBridgeConnectionId("connection-2");
    handler().processFile(file, "oe-1");

    assertNotEquals(id(0), id(1));
    assertNotEquals(id(1), id(2));
  }

  private Path csv(String name, String rows) throws IOException {
    return Files.writeString(directory.resolve(name), "Sample,Test,Result\n" + rows, StandardCharsets.UTF_8);
  }

  private FileMessageHandler handler() {
    return new FileMessageHandler(registry, config);
  }

  private String id(int index) {
    return deliveries.get(index).path("identifier").path("value").asText();
  }

  private String classification(JsonNode bundle) {
    for (JsonNode entry : bundle.path("entry")) {
      JsonNode resource = entry.path("resource");
      if (!"Observation".equals(resource.path("resourceType").asText())) continue;
      for (JsonNode extension : resource.path("extension")) {
        if (
          "https://openelis-global.org/fhir/StructureDefinition/analyzer-result-classification".equals(
              extension.path("url").asText()
            )
        ) return extension.path("valueCode").asText();
      }
    }
    throw new AssertionError("Delivery has no normalized result classification");
  }
}
