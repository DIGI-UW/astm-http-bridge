package org.itech.ahb.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.itech.ahb.config.properties.HTTPForwardServerConfigurationProperties;
import org.itech.ahb.connection.AnalyzerConnectionCatalog;
import org.itech.ahb.connection.AnalyzerConnectionCatalog.FileDirectoryClaim;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry;
import org.itech.ahb.connection.AnalyzerRuntimeRegistry.AnalyzerEntry;
import org.itech.ahb.fhir.FileNameSelfDeclarationScanner;
import org.itech.ahb.file.FileConfig;
import org.itech.ahb.file.FileMessageHandler;
import org.itech.ahb.file.FileWatcher;
import org.itech.ahb.file.SqliteFileStateStore;
import org.itech.ahb.profile.ControlResultRecognition;
import org.itech.ahb.profile.TabularResultValueSelection;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

class BridgeFileResetTransportTest {

  @TempDir
  Path directory;

  @ParameterizedTest(name = "reset drains real HTTP delivery, uploaded={0}")
  @ValueSource(booleans = { true, false })
  void resetDrainsDeliveryBeforeCleanupAndKeepsTheConnectionUsable(boolean uploaded) throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched"));
    Path file = watched.resolve("result.csv");
    byte[] csv = "Sample,Test,Result\nPATIENT-1,T1,2\n".getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv));
    CountDownLatch received = new CountDownLatch(1);
    CountDownLatch acknowledge = new CountDownLatch(1);
    CountDownLatch resetting = new CountDownLatch(1);
    AtomicInteger deliveries = new AtomicInteger();
    AtomicReference<Throwable> serverError = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/analyzer/fhir", exchange -> {
      try {
        var bundle = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
        assertEquals("Bundle", bundle.path("resourceType").asText());
        deliveries.incrementAndGet();
        received.countDown();
        assertTrue(acknowledge.await(10, TimeUnit.SECONDS), "receiver acknowledgment was not released");
        exchange.sendResponseHeaders(200, -1);
      } catch (Throwable failure) {
        serverError.compareAndSet(null, failure);
      } finally {
        exchange.close();
      }
    });
    server.start();
    SqliteFileStateStore store = new SqliteFileStateStore(directory.resolve("state.db"));
    AnalyzerRuntimeRegistry registry = new AnalyzerRuntimeRegistry();
    AnalyzerEntry entry = new AnalyzerEntry();
    entry.setId("owner");
    entry.setBridgeConnectionId("connection-1");
    entry.setProfileId("site.reset-test");
    entry.setProfileRevision(1);
    entry.setExpectedProtocol("FILE");
    entry.setFileDirectory(watched.toString());
    entry.setFilePattern("*.csv");
    entry.setMappedTestCodes(Set.of("T1"));
    entry.setColumnMappings(Map.of("Sample", "sampleId", "Test", "testCode", "Result", "result"));
    entry.setDelimiter(",");
    entry.setControlResultRecognition(ControlResultRecognition.none());
    entry.setRecognitionFingerprint("sha256:" + "1".repeat(64));
    entry.setTabularResultValueSelection(TabularResultValueSelection.resultOnly());
    registry.register(watched + "#connection-1", entry);
    HTTPForwardServerConfigurationProperties http = new HTTPForwardServerConfigurationProperties();
    http.setUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/analyzer"));
    http.setReadTimeoutSeconds(10);
    FileMessageHandler handler = new FileMessageHandler(registry, http);
    FileConfig config = new FileConfig();
    config.setEnabled(true);
    config.setPollIntervalMs(50);
    config.setFileStabilityTimeoutMs(50);
    FileWatcher watcher = new FileWatcher(config, handler, store);
    AnalyzerConnectionCatalog catalog = mock(AnalyzerConnectionCatalog.class);
    when(catalog.fileDirectoryClaims()).thenReturn(List.of(new FileDirectoryClaim("owner", watched, "*.csv")));
    BridgeAdminController admin = new BridgeAdminController(registry, store, catalog, watcher);
    FileUploadController uploads = new FileUploadController(
      registry,
      handler,
      mock(FileNameSelfDeclarationScanner.class),
      watcher
    );
    var executor = Executors.newFixedThreadPool(3);
    try {
      watcher.start();
      watcher.addWatchDirectory(watched, "*.csv", "owner");
      var initial = executor.submit(() -> {
        if (uploaded) {
          MockHttpServletResponse response = new MockHttpServletResponse();
          uploads.uploadFile("owner", "T1", multipart("result.csv", csv), response);
          assertTrue(response.getContentAsString().contains("banner success"));
        } else {
          Files.write(file, csv);
        }
        return null;
      });
      assertTrue(received.await(5, TimeUnit.SECONDS), "no real HTTP delivery reached the receiver");
      var reset = executor.submit(() -> {
        resetting.countDown();
        return admin.reset("owner");
      });
      assertTrue(resetting.await(5, TimeUnit.SECONDS));
      assertThrows(
        TimeoutException.class,
        () -> reset.get(200, TimeUnit.MILLISECONDS),
        "reset must not finish while the receiver still holds the delivery"
      );
      assertTrue(Files.exists(file));
      assertTrue(store.get("owner", hash).isPresent());
      var refused = executor.submit(() -> {
        MockHttpServletResponse response = new MockHttpServletResponse();
        uploads.uploadFile("owner", "T1", multipart("other.csv", csv), response);
        return response;
      });
      assertEquals(
        409,
        refused.get(2, TimeUnit.SECONDS).getStatus(),
        "new uploads must be rejected without waiting on a registry lock held by reset"
      );
      assertFalse(Files.exists(watched.resolve("other.csv")));
      assertEquals(1, deliveries.get());

      acknowledge.countDown();
      initial.get(5, TimeUnit.SECONDS);
      var result = reset.get(5, TimeUnit.SECONDS);
      assertEquals(200, result.getStatusCode().value());
      assertEquals(1, result.getBody().get("filesRemoved"));
      assertEquals(1, result.getBody().get("stateRowsRemoved"));
      assertFalse(Files.exists(file));
      assertTrue(store.get("owner", hash).isEmpty());

      MockHttpServletResponse after = new MockHttpServletResponse();
      uploads.uploadFile("owner", "T1", multipart("after-reset.csv", csv), after);
      assertTrue(after.getContentAsString().contains("banner success"));
      assertEquals(2, deliveries.get(), "reset must resume the saved connection");
      assertNull(serverError.get());
    } finally {
      acknowledge.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      watcher.stop();
      store.close();
      server.stop(0);
    }
  }

  private MockMultipartFile multipart(String filename, byte[] content) {
    return new MockMultipartFile("file", filename, "text/csv", content);
  }
}
