# 10 — No-Grow Buffer Contracts

This guide explains the fixed-capacity buffer design and the negated-position contract for limit-aware writers.

## The Problem

The naive grow-on-demand buffer:

```java
// Old approach: grow on overflow
if (pos + len > buf.length) {
    buf = grow(pos + len);  // Reallocate + copy
    System.arraycopy(oldBuf, 0, buf, 0, pos);
}
```

**Costs:**
- Reallocation on every oversized event (rare but unbounded)
- `System.arraycopy` after every grow
- No way to handle events larger than buffer capacity

## What Dia-Log Does

### Fixed Capacity, No Reallocation

```java
// Allocate once at startup (default 16 MiB)
ReusableByteArrayOutputStream rbo = new ReusableByteArrayOutputStream(16 * 1024 * 1024);
```

**Benefits:**
- No reallocation ever
- No copy after grow
- Bounded worst-case behavior

### The Negated-Position Contract

Limit-aware writers return **negated position** on overflow:

```java
// Success: returns new position
// Overflow: returns -preCallPosition

int newPos = writeEscapedJsonStringNoGrow(buf, pos, limit, value);
if (newPos < 0) {
    pos = -newPos;  // Recover pre-call position
    pos = writeTooLargeField(buf, pos);  // Replace with "V2BIG"
}
```

---

## The Techniques

### T12: Negated-Position Buffer Contract

#### The Contract

| Return Value | Meaning |
|--------------|---------|
| `pos + n <= limit` | Success, advance by `n` bytes |
| `-preCallPos` | Overflow, caller reverts to `preCallPos` |

**Why negated?** The caller accepts the result in the same cursor local:

```java
// Single assignment, no extra boolean
pos = writeEscapedJsonStringNoGrow(buf, pos, limit, value);
if (pos < 0) {
    pos = -pos;  // Negate to recover pre-call position
}
```

#### Partial Writes Are Harmless

When overflow occurs:
1. The writer may store bytes past the returned position
2. The buffer garbage is never seen (caller reverts `pos`)
3. The garbage is overwritten by the next store or never emitted

**Example:**
```java
// Write a 50-byte string, but limit is 48
pos = writeEscapedJsonStringNoGrow(buf, 100, 150, longString);
// Returns -100
// Buffer[100..147] may contain partial string
// Caller: pos = 100; writeTooLargeField(buf, 100);
// Result: {..other fields..,"value":"V2BIG"}
```

### SWAR Block Margin Guard

Instead of checking capacity per byte, check per SWAR block:

```java
// 16-byte SWAR block can expand to 96 bytes (max 6x for dirty)
// 8-byte word can expand to 16 bytes (max 2x for dirty)
final int LIMIT_MARGIN = 1024;  // Conservative, covers all cases

for (int i = 0; pos + i + 16 <= limit - LIMIT_MARGIN; ) {
    // Process 16-byte block
    // No per-byte capacity check needed
}
// Per-byte fallback for remainder
for (; pos < limit; pos++) {
    if (pos + 1 > limit - LIMIT_MARGIN) {
        return -preCallPos;
    }
    // Write byte
}
```

**Benefit:** One compare per SWAR block instead of per byte.

---

## Event Assembly Finalizers

When an event is too large to fit, the appender finalizes gracefully:

```java
int pos = writeJsonEventDirect(buf, pos, limit);  // Returns final position

if (pos == -1) {
    // Buffer too small for ts prefix
    buf = "{\n}";
} else if (pos < 0) {
    // Value overflow
    pos = -pos;  // Recover pre-call position
    writeTooLargeField(buf, pos);  // "V2BIG" placeholder
} else {
    // Success: append newline, flush
    buf[pos++] = '\n';
    System.arraycopy(buf, 0, result, 0, pos);
}
```

---

## Performance Comparison

| Metric | Grow-on-Demand | No-Grow | Improvement |
|--------|----------------|---------|-------------|
| Reallocation | Occasional | Never | Eliminated |
| Capacity checks | Per byte/store | Per SWAR block | ~50× fewer |
| Worst-case | Unbounded | Fixed | Bounded |
| Oversized events | Keep growing | Graceful fallback | Predictable |

---

## Verification

- **Correctness:** `NoGrowWritersTest` verifies negated-position contract
- **Buffer tests:** `ReusableByteArrayOutputStreamTest` ensures no growth
- **Byte-identity:** `JsonLogWriterDirectBufferTest` ensures normal events unchanged
- **Edge cases:** Tiny buffers (8 bytes) force all capacity checks

---

## Related Concepts

- [02 — Cursor Locality](02-cursor-locality.md) — Writer-owns-buffer pattern
- T12 in `doc/perf-exploration/` — Detailed implementation record
- [06 — Benchmarking](06-benchmarking.md) — How performance claims are measured
