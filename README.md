# OpenELIS Analyzer Bridge

Middleware that runs analyzer connections, parses analyzer traffic from pinned
profiles, and sends one normalized result contract to OpenELIS.

This repository was previously named **ASTM-HTTP Bridge**. The internal rename to `openelis-analyzer-bridge` is complete across Maven, Docker, and scripts. The Docker Hub image `itechuw/astm-http-bridge` is still published as a legacy alias via CI.

## Ownership Model

Bridge and OpenELIS responsibilities are explicitly separated:

- Bridge owns portable profiles, durable analyzer connections and runtime
  configuration, listeners, parsing, probes, control recognition, FILE
  watching, and normalized delivery.
- OpenELIS owns the lab-facing setup workflow, references to Bridge
  connections, lab units, local catalog bindings, verification and audit,
  activation intent, operational QC, held results, and review.
- A profile defines communication behavior for one analyzer type and supplies
  defaults for creating a new Bridge connection of that type.

## Architecture

**Current OGC-1054 delivery boundary:** the working connection paths are priority
ASTM and FILE, including profile-driven HTTP CSV/TSV input. HL7 has normalized
contract fixtures and parser coverage, but durable HL7/MLLP connection activation
is not implemented. Enabling the shared MLLP listener does not create an authorized
connection; its traffic is rejected. Do not deploy this revision as a replacement
for a working HL7/MLLP installation until that runtime gap is addressed. The
protocol diagram and MLLP settings below describe components, not completed
connection support.

```
Analyzer(s)                                    OpenELIS
───────────                                    ────────
ASTM/TCP     ─┐
HL7/MLLP     ─┤
RS232/Serial ─┼─> [OpenELIS Analyzer Bridge] ──FHIR──> /analyzer/fhir
Files        ─┤   │ Pinned profile parsing  │          normalized result contract
HTTP /input  ─┘   │ Saved connection lookup │
                   │ Metrics + health checks │
                   └─────────────────────────┘
                     │
                     ├─ /actuator/health      (per-transport status)
                     ├─ /actuator/prometheus   (Prometheus metrics)
                     └─ /actuator/metrics      (Micrometer metrics)

OpenELIS ──HTTP POST──> [Bridge] ──TCP──> Analyzer (ASTM host query / outbound)
```

### Protocol vs Transport

| Concept | Options | Description |
|---------|---------|-------------|
| **Protocol** | ASTM, HL7, CSV | Message format/syntax |
| **Transport** | TCP, MLLP, Serial, File, HTTP | How the message arrives |

## Quick Start

### Using Docker (Recommended)

```bash
git clone https://github.com/DIGI-UW/openelis-analyzer-bridge.git
cd openelis-analyzer-bridge

docker compose up -d --build
docker logs --follow openelis-analyzer-bridge
```

### Building from Source

```bash
cd astm-http-lib
mvn clean install

cd ..
mvn clean package

java -jar target/openelis-analyzer-bridge-*.jar --spring.config.location=configuration.yml
```

## Docker Deployment

### Port Mapping

| External | Internal | Service |
|----------|----------|---------|
| 8442 | 8443 | HTTPS API endpoint |
| 12000 | 12001 | ASTM LIS1-A listener |
| 12010 | 12011 | ASTM E1381-95 listener |
| Saved port | Saved port | Active HL7 server connection (publish each configured port) |

### Volume Mounts

| Host Path | Container Path | Purpose |
|-----------|---------------|---------|
| `./configuration.yml` | `/app/configuration.yml` | Runtime configuration |
| `/path/to/import` | `/mnt/analyzer-import` | File watcher input (optional) |
| `/path/to/archive` | `/mnt/analyzer-archive` | Processed files (optional) |

### Serial Devices

Uncomment in `docker-compose.yml` if using serial transport:

```yaml
devices:
  - /dev/ttyUSB0:/dev/ttyUSB0
```

## Configuration

Runtime configuration is read from `configuration.yml` (mounted into container at `/app/configuration.yml`).

### Configuration Properties Reference

| Property | Description | Default |
|----------|-------------|---------|
| **OpenELIS Forwarding** | | |
| `org.itech.ahb.forward-http-server.uri` | OpenELIS analyzer endpoint base URI | Required |
| `org.itech.ahb.forward-http-server.username` | Basic auth username | Optional |
| `org.itech.ahb.forward-http-server.password` | Basic auth password | Optional |
| `org.itech.ahb.forward-http-server.insecure-tls` | Disable TLS verification for forwarding and health checks | false |
| `org.itech.ahb.forward-http-server.connect-timeout-seconds` | HTTP connect timeout | 30 |
| `org.itech.ahb.forward-http-server.read-timeout-seconds` | HTTP read timeout | 30 |
| `org.itech.ahb.forward-http-server.max-attempts` | Outbound retry attempts | 3 |
| `org.itech.ahb.forward-http-server.backoff-ms` | Initial outbound retry backoff in ms | 1000 |
| **ASTM TCP** | | |
| **MLLP (HL7)** | | |
| `org.itech.ahb.mllp.enabled` | Permit saved HL7 server connections to start listeners | false |
| **Serial** | | |
| **File Watcher** | | |
| `bridge.file.enabled` | Enable FILE connection runtime | true |
| `bridge.file.stateStorePath` | Durable file-processing state database | JVM temporary directory |
| `bridge.file.pollIntervalMs` | Poll interval | 5000 |
| `bridge.file.fileStabilityTimeoutMs` | Stable-file wait | 3000 |
| `bridge.file.maxRetryAttempts` | Processing attempts | 3 |
| `bridge.file.retryDelayMs` | Initial retry backoff | 1000 |
| **Profile Catalog** | | |
| `bridge.profile-catalog.directory` | Durable site-profile revision store | `/data/openelis-analyzer-bridge/profile-catalog` |
| `bridge.profile-catalog.shipped-pattern` | Packaged profile resource pattern | `classpath*:/analyzer-profiles/**/*.json` |
| **Connection Catalog** | | |
| `bridge.connection-catalog.directory` | Durable analyzer connection store | `/data/openelis-analyzer-bridge/connections` |
| **Connectivity** | | |
| `bridge.connectivity.advertised-host` | Reserved; currently unused by connection activation and receiver probes | Optional; setting it has no runtime effect |
| **Security (M7.1)** | | |
| `bridge.security.enabled` | Enable HTTP Basic auth on `/input` and management APIs | true |
| `bridge.security.username` | HTTP Basic username | bridge |
| `bridge.security.password` | HTTP Basic password: plaintext or `{bcrypt}...` (use env var in prod) | changeme |
| **Server** | | |
| `server.port` | HTTP server port | 8443 |

### Saved HL7 listeners

Enable `org.itech.ahb.mllp.enabled` (or `MLLP_ENABLED` with the production profile)
to permit activation of saved HL7 connections with `transport=TCP/IP` and
`connectionRole=SERVER`. Each connection owns its saved `port`; publish those
ports in the container configuration. Enabling the runtime alone opens no port.
The former global `org.itech.ahb.mllp.port` / `MLLP_PORT` setting no longer creates
a listener. TCP client-mode inbound activation is not supported and is rejected.

Activation succeeds only after that connection's socket binds. The listener's
saved connection binding identifies incoming results; neither the peer IP nor
MSH sender fields can select another analyzer. Use network access controls to
restrict who can reach each analyzer port; the binding is not peer authentication.
Deactivation closes admissions and waits up to 30 seconds for active delivery
before removing routing authority. Failed drains report failure and retain
ownership. On restart, the durable connection catalog restores active listeners
from their last successfully activated values and pinned profiles, even when a
newer saved edit has not been activated. These values remain internal to Bridge;
OpenELIS receives the active reference, not a second configuration copy.
Health reports each owned listener,
not a global socket.

### FILE shutdown and recovery

Stopping the FILE service closes admissions for uploads and watcher work before
stopping its polling and processing executors. Already-started operations retain
ownership through their final state write. Shutdown then waits up to 30 seconds
for remaining claims, including uploads running on request threads. A timeout or
interruption reports incomplete shutdown; it does not release those claims or
report successful cancellation. Do not treat this failure as a completed drain.

Pending retry timers are cancelled without clearing their persisted state or
deadlines. On process restart, restored connections rediscover source files and
resume according to that state. A stopped watcher instance cannot be restarted
or accept new registrations; recovery creates a new service instance. Preserve
both the source directories and the configured state database across restarts.

### Analyzer Identification

Create a durable Bridge connection from a published profile revision. Activating
that connection materializes its source binding and profile-owned behavior into
the runtime registry. There is no separate static analyzer map.

#### Resolution Policy

Analyzer identification uses three distinct concepts:

- **Source binding**: where a message came from (IP/port, serial port, file directory, HTTP source).
- **Protocol hint**: what the payload claims (e.g., HL7 sender app/facility, ASTM sender token).
- **Bridge connection ID**: the durable identity emitted in every normalized
  result bundle and used by OpenELIS for exact lookup.

Policy rules:

- The saved connection bound to the source is authoritative for routing.
- Protocol hints are validation evidence and diagnostics only.
- Protocol hints alone must not select routing targets.
- An unregistered source is rejected and dead-lettered before delivery.
- A contradictory hint is recorded but cannot override the source-bound
  connection.

## Monitoring & Observability

### Health Checks

```bash
# Overall health
curl http://localhost:8442/actuator/health

# Individual transport health
curl http://localhost:8442/actuator/health/httpforward   # OpenELIS connectivity
curl http://localhost:8442/actuator/health/mllp          # MLLP listener status
curl http://localhost:8442/actuator/health/serial         # Serial port status
curl http://localhost:8442/actuator/health/filewatcher    # File watcher status
```

Health indicators are individually enabled/disabled via configuration:

```yaml
management:
  health:
    mllp:
      enabled: true
    serial:
      enabled: true
    filewatcher:
      enabled: true
    httpforward:
      enabled: true
```

### Prometheus Metrics

Prometheus-format metrics are exposed at `/actuator/prometheus`.

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `bridge_messages_received_total` | Counter | protocol, transport | Messages received from analyzers |
| `bridge_messages_routed_total` | Counter | protocol, transport, result | Messages forwarded to OpenELIS |
| `bridge_messages_routing_duration_seconds` | Timer | protocol, transport | End-to-end routing latency |

**Example PromQL queries:**

```promql
# Message throughput per minute
rate(bridge_messages_received_total[1m])

# Routing success rate
rate(bridge_messages_routed_total{result="success"}[5m])
  / rate(bridge_messages_routed_total[5m])

# P95 latency by protocol
histogram_quantile(0.95, rate(bridge_messages_routing_duration_seconds_bucket[5m]))
```

### Kubernetes Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8443
  initialDelaySeconds: 120
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8443
  initialDelaySeconds: 30
  periodSeconds: 10
```

## Security

The `/input` HTTP endpoint and the `/api/profiles` and `/api/connections`
management APIs are protected with HTTP Basic authentication. Non-HTTP
transports (ASTM/TCP, MLLP, Serial, File) are unaffected.

Active connections have distinct runtime registrations even when they share an
analyzer host. A host-only inbound lookup is accepted only when it identifies one
active connection; shared hosts require a connection-specific source binding.

HTTP input ignores `X-Forwarded-For`, `X-Real-IP`, and `X-Forwarded-Port` by default.
Behind a reverse proxy, set `bridge.http.trusted-proxies` to a comma-separated
string of trusted proxy IP addresses (for example, `"192.0.2.2,192.0.2.3"`). Only these
socket peers may supply forwarded identity. The address chain is read from right
to left and stops at the first untrusted peer, so a client-supplied prefix cannot
choose a different analyzer. Configure trusted proxies to append the actual peer
address and overwrite forwarded port and real-IP headers. Do not enable generic
servlet/container forwarded-header rewriting: keep
`server.forward-headers-strategy=none` so Bridge can inspect the real socket peer.

### Configuration

```yaml
bridge:
  security:
    enabled: true                               # false to disable (not recommended)
    username: bridge
    password: ${BRIDGE_AUTH_PASSWORD:changeme}   # Set via environment variable
```

### Usage

```bash
# Authenticated request to /input
curl -u bridge:changeme -X POST http://localhost:8442/input \
  -H "Content-Type: application/hl7-v2" \
  -d "MSH|^~\&|ANALYZER|LAB|..."

# Unauthenticated returns 401
curl -X POST http://localhost:8442/input -d "test"
# → 401 Unauthorized
```

### Production Setup

**Required:** Set the password via environment variable. The default `changeme` causes startup failure when `spring.profiles.active` is not `dev` or `test`.

```bash
export BRIDGE_AUTH_PASSWORD=your-secure-password
docker compose up -d
```

Or in Docker Compose:

```yaml
environment:
  BRIDGE_AUTH_PASSWORD: your-secure-password
```

Pre-encoded passwords are supported using Spring’s delegating form: set `bridge.security.password={bcrypt}$2a$10$...` (or another `{id}...` scheme) and the bridge stores that value as-is. Plaintext values are BCrypt-encoded once at startup—do not double-encode.

### Disabling Security

For development only:

```yaml
bridge:
  security:
    enabled: false
```

## Result Delivery

### Analyzer -> OpenELIS (results submission)

- Bridge accepts traffic only for an active saved connection.
- The pinned profile determines parsing, result selection, control recognition,
  and optional LOINC hints.
- Bridge posts `application/fhir+json` to `/analyzer/fhir` for ASTM, HL7,
  serial, HTTP, and FILE traffic.
- The normalized bundle carries the exact Bridge connection and profile
  revision, raw analyzer code and value, transport, and control-recognition
  evidence. OpenELIS does not infer identity from source headers or analyzer
  names.

### OpenELIS -> Analyzer (query/config)

```bash
curl -X POST "http://bridge:8443/?forwardAddress=192.168.1.10&forwardPort=5000" \
  -H "Content-Type: text/plain" \
  -d "H|\^&|||"
```

## Testing

### Unit Tests

```bash
mvn test
```

### Integration Tests

```bash
mvn verify
```

### Protocol Integration

These scripts require saved, active test connections; enabling a transport alone
does not register an analyzer. For the HL7 script, first activate an HL7 server
connection, then set `BRIDGE_CONNECTION_ID` and its `BRIDGE_MLLP_PORT`. Set
`BRIDGE_PASSWORD` (and optionally `BRIDGE_USERNAME`) when API authentication is
enabled. Its forwarding destination must be the isolated test WireMock service.
The script verifies both the protocol acknowledgement and saved connection identity.

For self-contained HL7 lifecycle and disk-backed restart checks without preparing
a deployment, run `mvn test -Dtest=Hl7SavedConnectionTest,HapiConnectionLifecycleTest`.

```bash
# Assembled Bridge transport and normalized-contract tests
mvn -Dtest=UnifiedRoutingTest,HttpForwardingRouterTest test

# Virtual serial integration, when socat ports are available
./scripts/e2e-tests/test-serial.sh
```

Cross-process analyzer behavior belongs in
[DIGI-UW/analyzer-mock-server](https://github.com/DIGI-UW/analyzer-mock-server),
which sends real protocol traffic to a running Bridge. Visible OpenELIS user
stories are tested separately through the browser.

## Project Structure

```
openelis-analyzer-bridge/
├── src/main/java/org/itech/ahb/
│   ├── controller/          # HTTP endpoints (/input, query forwarding)
│   ├── config/              # Configuration classes
│   ├── file/                # File watcher transport
│   ├── health/              # Health indicators (HTTP, MLLP, Serial, File)
│   ├── metrics/             # Prometheus metrics service
│   ├── mllp/                # MLLP transport (HL7 v2.x)
│   ├── model/               # Protocol/Transport enums
│   ├── normalizer/          # Message normalization + routing
│   ├── routing/             # HTTP forwarding router
│   ├── serial/              # Serial port transport
│   └── util/                # Utilities
├── astm-http-lib/           # ASTM protocol library
├── configuration.yml        # Runtime configuration
├── docker-compose.yml       # Production deployment
└── scripts/e2e-tests/       # Optional virtual-serial runner
```

## Contracts

- `contracts/analyzer/v1/normalized-fhir-bundle.schema.json`: normalized result
  contract consumed by OpenELIS
- `contracts/analyzer/v1/fixtures/`: canonical ASTM, HL7, and FILE examples
- `src/main/resources/analyzer-profiles/`: shipped analyzer type profiles

### Checkpoint fixtures and the final repository pair

The analyzer-mock revision in `.github/workflows/test.yml` is the exact fixture
version used to validate this Bridge checkpoint. Earlier checkpoints may pin an
earlier compatible fixture revision; that is not a claim about the final stack's
deployment dependencies. Do not move every checkpoint to the newest mock revision
without checking that its profile and result contracts are supported there.

For final-stack validation, use the mock revision pinned by the top Bridge
checkpoint and verify that the OpenELIS follow-up pins that same mock revision
and the exact final Bridge commit. Report checkpoint test evidence separately
from checks on that final repository pair.

## License / Contributing

TBD (add project license and contribution guidelines).
