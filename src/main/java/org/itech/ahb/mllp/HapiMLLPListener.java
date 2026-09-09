package org.itech.ahb.mllp;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.app.SimpleServer;
import ca.uhn.hl7v2.util.StandardSocketFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.itech.ahb.routing.MessageRouter;

/** HAPI transport owned by one saved connection, never a global Spring listener. */
public final class HapiMLLPListener {

  private final int port;
  private final String sourceBindingId;
  private final MessageRouter router;
  private final CompletableFuture<Void> bound = new CompletableFuture<>();
  private volatile ServerSocket socket;
  private volatile boolean stopping;
  private SimpleServer server;
  private DefaultHapiContext context;
  private ExecutorService executor;
  private HapiReceivingApplication application;
  private RateLimitingReceivingApplication rateLimiter;
  private boolean stopped;

  public HapiMLLPListener(int port, String sourceBindingId, MessageRouter router) {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid HL7 listen port");
    if (sourceBindingId == null || sourceBindingId.isBlank()) {
      throw new IllegalArgumentException("A saved-connection source binding is required");
    }
    this.port = port;
    this.sourceBindingId = sourceBindingId;
    this.router = java.util.Objects.requireNonNull(router);
  }

  public synchronized void start() {
    if (stopping) throw new IllegalStateException("Cannot restart a stopped HL7 listener instance");
    if (isRunning()) return;
    executor = Executors.newCachedThreadPool(Thread.ofPlatform().name("hl7-" + sourceBindingId + "-", 0).factory());
    context = new DefaultHapiContext(executor);
    context.setSocketFactory(
      new StandardSocketFactory() {
        @Override
        public ServerSocket createServerSocket() throws IOException {
          ServerSocket created = new ServerSocket() {
            @Override
            public Socket accept() throws IOException {
              try {
                return super.accept();
              } catch (IOException failure) {
                if (!isClosed() && isBound()) throw failure;
                // HAPI retries IOExceptions until its acceptor is stopped. Closing
                // admissions before draining must not create a hot error loop.
                try {
                  Thread.sleep(SimpleServer.SO_TIMEOUT);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
                throw new SocketTimeoutException("Saved HL7 listener is no longer accepting");
              }
            }

            @Override
            public void bind(SocketAddress endpoint, int backlog) throws IOException {
              try {
                super.bind(endpoint, backlog);
                bound.complete(null);
              } catch (IOException failure) {
                bound.completeExceptionally(failure);
                throw failure;
              }
            }
          };
          socket = created;
          // Startup can time out before HAPI reaches socket creation.
          if (stopping) created.close();
          return created;
        }
      }
    );
    try {
      application = new HapiReceivingApplication(router, sourceBindingId);
      rateLimiter = new RateLimitingReceivingApplication(application);
      server = context.newServer(port, false);
      server.registerApplication("*", "*", rateLimiter);
      server.start();
      // HAPI's service-start flag precedes the asynchronous acceptor bind.
      // Only this listener's actual bind establishes readiness.
      bound.get(5, TimeUnit.SECONDS);
      if (!server.isRunning()) throw new IllegalStateException("HL7 server exited during startup");
    } catch (Exception failure) {
      boolean interrupted = Thread.interrupted() || failure instanceof InterruptedException;
      try {
        stop();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      } finally {
        if (interrupted) Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("Cannot bind saved HL7 connection on port " + port, failure);
    }
  }

  public synchronized void stop() {
    if (stopped) return;
    stopping = true;
    if (application != null) application.stopAccepting();
    if (socket != null) {
      try {
        socket.close();
      } catch (IOException failure) {
        throw new IllegalStateException("Cannot close HL7 listener admissions", failure);
      }
    }
    try {
      // Keep routing authority and downstream resources alive through delivery.
      if (application != null) application.awaitDrained();
      if (server != null) server.stopAndWait();
      if (rateLimiter != null) rateLimiter.shutdown();
      if (context != null) context.close();
      if (executor != null) {
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
          executor.shutdownNow();
          if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("HL7 listener threads did not terminate");
          }
        }
      }
      stopped = true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted draining saved HL7 connection", interrupted);
    }
  }

  public boolean isRunning() {
    return (
      !stopping && socket != null && socket.isBound() && !socket.isClosed() && server != null && server.isRunning()
    );
  }

  public int getPort() {
    return port;
  }
}
