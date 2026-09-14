# 07 — Bufferless VarHandle Number Writing

This guide explains how integers, longs, floats, and doubles are written directly into the output buffer without intermediate scratch buffers or `System.arraycopy` operations.

## The Problem

The naive approach to writing numbers:

```java
// Old approach: build in scratch, then copy
byte[] scratch = new byte[20];
int len = JsonNumberWriter.buildLong(scratch, value);
System.arraycopy(scratch, 20 - len, buf, pos, len);
```

**Costs:**
- Scratch buffer allocation (even if caller-owned)
- Right-to-left digit building loop
- `System.arraycopy` for every number

## What Dia-Log Does

```java
// New approach: write directly at offset
int newPos = JsonNumberWriter.writeLong(buf, pos, value);
```

**Benefits:**
- No scratch buffer
- Left-to-right digit emission
- One `VarHandle` store per 4-digit group

---

## The Techniques

### T9: Direct Buffer Number Writing

Digits are computed and written **in-place** at the caller-supplied `pos`:

1. **Int/Long:** Left-to-right digit extraction using `Math.multiplyHigh`
2. **Float/Double:** Delegates to Ryu's bufferless writer
3. **VarHandle stores:** Each 4-digit group is one `LE_INT.set(buf, pos, word)`

**Key implementation:**
```java
public static int writeLong(byte[] buf, int pos, long value) {
    if (value == Long.MIN_VALUE) {
        LE_LONG.set(buf, pos, MIN_VALUE_W0);
        LE_LONG.set(buf, pos + 8, MIN_VALUE_W1);
        return pos + 19;
    }
    
    // Split at 10^9, division-free
    long hi = value < 0 ? -value : value;
    long lo = hi;
    long split = 1_000_000_000L;
    long magic = 0xC74520EE;
    long[] quots = multiplyHigh(hi, magic);
    // ... digit extraction and direct stores
}
```

---

## Performance Comparison

| Benchmark | Old (build+copy) | New (bufferless) | Improvement |
|-----------|------------------|------------------|-------------|
| `writeLong` (full) | 36.4 ns/op | 24.6 ns/op | 1.5× |
| `writeInt` (full) | 22.0 ns/op | 18.1 ns/op | 1.2× |
| `eventNewDirect` | 0.095 µs/op | 0.064 µs/op | 1.49× |

**Allocation:** 0 B/op on the direct path (both old and new use caller-owned buffers).

---

## Verification

- **Correctness:** `ForyPerfComparisonTest` compares byte-identical output against the old build+copy path
- **Unit tests:** `JsonNumberWriterTest` covers all boundary cases
- **Benchmarks:** `IntWriteBenchmark` / `LongWriteBenchmark` measure performance

---

## Related Concepts

- [04 — Number Writing](04-number-writing.md) — Overview of number serialization techniques
- T9 in `doc/perf-exploration/` — Detailed implementation record
- `JeaiiiFastWriter` — Alternative division-free int/long writer (T10)
