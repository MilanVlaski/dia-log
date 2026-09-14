# 013: Allow duplicate top-level JSON keys (remove reserved-name skipping)

* **Status:** Accepted
* **Date:** 2026-09-14
* **Implementation Status:** Implemented

## Context

`JsonLogWriter` and its test-side mirrors (`JsonLogWriterStream`, `JsonLogWriterClassic`)
skipped MDC keys whose names collided with the writer's own top-level fields via an
`isReserved` switch (`ts`, `level`, `logger`, `thread`, `msg`, `errClass`, `errHash`,
`errMessage`). Two problems:

1. The reserved list lagged the actual field list — `stack` (exception stack field) and
   `prefix` (logger prefix, itself a KV pair added by `DiaLoggerBase` when a prefix is
   configured) were never reserved, so an MDC key named `stack` or `prefix` produced
   duplicate top-level JSON fields.
2. The fields are not always present: `stack`/`err*` only on exception events, `prefix`
   only when a prefix is configured. An always-reserved list would silently drop
   legitimate MDC values on events where the field does not exist.

ADR 012 already removed KV/MDC dedup on the same grounds: duplicate keys are valid JSON
(RFC 8259 does not forbid them), downstream ingestion handles them, and the serializer
should not silently drop data.

## Decision

Allow duplicate top-level JSON keys. Remove the `isReserved` check entirely from
`JsonLogWriter`, `JsonLogWriterStream`, and `JsonLogWriterClassic`: MDC keys are written
as-is, even when they collide with a writer field name.

### Why

1. **Consistent with ADR 012.** KV and MDC may already duplicate each other (both values
   are emitted). Extending the same tolerance to writer-field collisions leaves one
   simple output rule: every key in the event is written.
2. **No silent data loss.** The old check dropped MDC values without warning.
3. **Consumers resolve duplicates.** Ingestion systems accept duplicate object keys
   (last-wins or array collection); that resolution belongs at the consumer.
4. **The reserved list was unenforceable.** Keeping it in sync with the field list would
   require a per-field presence check on the hot path for a list that is inherently
   event-dependent.

## Consequences

* **Positive:** One output rule (every event key is written as-is); no reserved-name list
  to keep in sync; the hot-path MDC pass drops one branch per key.
* **Negative:** Rare duplicate top-level keys may appear when an MDC key collides with a
  writer field (e.g. `MDC.put("msg", …)` on any event, or `MDC.put("stack", …)` on an
  exception event). Operators whose ingestion cannot handle duplicates must filter at
  the consumer.

## References

- [ADR-012](./012-remove-key-dedup.md) — remove key dedup between KV pairs and MDC
- `doc/usage.md` § MDC — MDC keys written as-is
- `JsonLogWriterTest.mdcKeys_writtenAsIs_evenWhenFieldNames` /
  `mdcKeys_fieldNames_areWritten` — the inverted skip tests
