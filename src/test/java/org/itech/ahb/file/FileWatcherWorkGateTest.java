package org.itech.ahb.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWatcherWorkGateTest {

  @TempDir
  Path directory;

  private FileWatcher watcher;

  @BeforeEach
  void setUp() throws IOException {
    watcher = new FileWatcher(new FileConfig(), null, null);
    watcher.addWatchDirectory(directory, "*.csv", "owner");
  }

  @AfterEach
  void tearDown() {
    watcher.stop();
  }

  @Test
  void nestedPausesBlockNewFilesUntilEveryCleanupScopeCloses() throws Exception {
    Path file = directory.resolve("result.csv");
    try (var outer = watcher.pauseDirectory(directory)) {
      try (var inner = watcher.pauseDirectory(directory)) {
        assertNull(watcher.tryClaimFile(file, "owner"));
      }
      assertNull(watcher.tryClaimFile(file, "owner"));
    }
    try (var claim = watcher.tryClaimFile(file, "owner")) {
      assertNotNull(claim);
    }
  }

  @Test
  void interruptedDrainReleasesItsPauseWithoutReleasingTheActiveWorker() throws Exception {
    Path busy = directory.resolve("busy.csv");
    try (var worker = watcher.tryClaimFile(busy, "owner")) {
      assertNotNull(worker);
      try {
        Thread.currentThread().interrupt();
        assertThrows(IOException.class, () -> watcher.pauseDirectory(directory));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertNull(watcher.tryClaimFile(busy, "owner"));
      try (var other = watcher.tryClaimFile(directory.resolve("other.csv"), "owner")) {
        assertNotNull(other, "failed drain must not leave the directory permanently paused");
      }
    }
  }

  @Test
  void physicalDirectoryAliasesShareTheSameClaimAndPause() throws Exception {
    Path physical = Files.createDirectory(directory.resolve("physical"));
    Path alias = Files.createSymbolicLink(directory.resolve("alias"), physical);
    watcher.addWatchDirectory(physical, "*.csv", "owner");
    watcher.addWatchDirectory(alias, "*.csv", "owner");
    try (var worker = watcher.tryClaimFile(physical.resolve("result.csv"), "owner")) {
      assertNotNull(worker);
      assertNull(watcher.tryClaimFile(alias.resolve("result.csv"), "owner"));
    }
    try (var pause = watcher.pauseDirectory(alias)) {
      assertNull(watcher.tryClaimFile(physical.resolve("new.csv"), "owner"));
    }
    try (var worker = watcher.tryClaimFile(physical.resolve("new.csv"), "owner")) {
      assertNotNull(worker);
    }
  }
}
