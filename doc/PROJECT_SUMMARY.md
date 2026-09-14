# Dia-Log Project Summary

**Version:** 1.0.1  
**Date:** 2026-09-26  
**Status:** Production Ready

---

## At a Glance

| Aspect | Rating | Description |
|--------|--------|-------------|
| **Performance** | ⭐⭐⭐⭐⭐ | Zero-allocation hot path, 2.6–3.0× faster with throwables |
| **Code Quality** | ⭐⭐⭐⭐½ | Clean architecture, minimal dependencies |
| **Test Coverage** | ⭐⭐⭐⭐⭐ | 88% line / 79% branch (exceeds 80%/70% floor) |
| **Documentation** | ⭐⭐⭐⭐⭐ | 37 technique records, 6 consolidated guides |
| **Maturity** | ⭐⭐⭐⭐⭐ | 13 ADRs, comprehensive changelog, ready for release |

---

## What Dia-Log Is

A diagnostic logging library for SLF4J 2.x and Java 25+ that provides:

1. **Structured JSON logging** — flat top-level fields for machine parsing
2. **Contextual key/value pairs** — statement-scoped, auto-cleanup
3. **Deterministic stack-trace hashing** — for error deduplication
4. **Zero-allocation hot path** — no garbage per log line

---

## Key Technologies

- **Wyhash64** — Deterministic 64-bit hashing, zero-allocation
- **Ryu** — Fast float/double to string conversion
- **VarHandle** — Direct byte[] access for strings and number writing
- **Packed-word stores** — Precomputed field prefixes in `long` values
- **Reusable buffers** — 1 MiB grow-once event buffer

---

## Performance Highlights

| Metric | Value |
|--------|-------|
| No-throwable event | 0.330 us/op, ~330 B/op |
| With throwable | 2.159 us/op, ~592 B/op |
| vs Jackson | ~8% faster (no throwable), ~3% faster (with throwable) |
| vs Default Logback | ~3.1× faster (traced events) |
| Allocation (hot path) | 0 B/op (zero-allocation) |

---

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| `core` | `dia-log-core` | Logger, Wyhash64, stack sanitization |
| `logback` | `dia-log-logback` | JsonAppender, JsonAppenderRolling |
| `example` | `dia-log-example` | Runnable demo |

---

## Quick Start

```java
// Structured logging
log.atInfo().kv("userId", id).kv("action", "login").log("User logged in");

// Conditional stack trace
log.atDebug().stackWhenTraceEnabled().log("Debug: {state}");
```

```xml
<!-- logback.xml -->
<appender name="JSON" class="hr.hrg.dialog.logback.JsonAppender">
</appender>
<root level="INFO"><appender-ref ref="JSON"/></root>
```

---

## Requirements

- **Java:** 25+ (enforced by Maven Enforcer)
- **SLF4J:** 2.0.18
- **Logback:** 1.5.38 (for logback module)
- **Optional:** `--add-opens java.base/java.lang=ALL-UNNAMED` for zero-allocation fast paths

---

## Key Features

### ✅ Implemented

- Structured JSON output (flat fields)
- Statement-scoped key/value pairs
- MDC propagation
- Conditional stack trace (`stackWhenTraceEnabled()`)
- Deterministic error hashing
- Async event forwarding (`EventSnapshotHandler`)
- XZ compression for rotated logs
- No-grow buffer with overflow handling

### ⏳ Planned

- Automatic MDC cleanup (ADR 003)
- Async appender with bounded queue
- Integration tests for logback.xml pipeline

---

## Documentation

| Document | Location |
|----------|----------|
| Usage Guide | `doc/usage.md` |
| Migration Guide | `doc/migration.md` |
| Performance Guide | `doc/perf/*.md` |
| Technique Records | `doc/perf-exploration/t*.md` |
| Architecture Decisions | `doc/adr/*.md` |
| Benchmark Results | `doc/perf-exploration/*.txt`, `*.csv` |

---

## Build Commands

```bash
# Build and test
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
mvn -o -pl core,logback test "-Dsurefire.failIfNoSpecifiedTests=false"

# Package (for local testing)
mvn -o package -DskipTests "-Dgpg.skip=true" "-Djacoco.skip=true" -pl project-automation

# Run tools
java -jar project-automation/target/dia-log-project-automation-1.0.0-cli.jar derivative
```

---

## License

MIT License

---

## Contact

- **Author:** Davor Hrg
- **Repository:** https://github.com/hrgdavor/dia-log
