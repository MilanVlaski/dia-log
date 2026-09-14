# 08 — Packed Word VarHandle Stores

This guide explains how static strings (field prefixes, JSON literals) are precomputed into packed `long` words and written with direct `VarHandle` stores.

## The Problem

Writing static strings normally:

```java
// Old approach: per-string arraycopy or byte-by-byte write
out.write('"');
out.write('t');
out.write('s');
out.write('"');
out.write(':');
// ... repeated for each field
```

**Costs:**
- `write(int)` per byte (virtual dispatch)
- `write(byte[])` per string (arraycopy)
- Unoptimized byte-by-byte stores

## What Dia-Log Does

### T8: Full-Store/Partial-Advance Trick

Static strings are precomputed as little-endian `long` words:

```java
// Precomputed at class initialization:
private static final long KEY_TS_W0 = 0x2274730000000000L;  // "ts"
private static final int KEY_TS_LEN = 2;

// Write with one VarHandle store:
LE_LONG.set(buf, pos, KEY_TS_W0);
pos += 2;  // Advance by actual length, not 8
```

**Key insight:** The tail bytes (bytes 4–7 of the 8-byte word) are "garbage" that gets overwritten by the next store or never emitted (flush boundary is `pos`, not `buf.length`).

---

## The Techniques

### Packed Word Stores

Field prefixes are compiled as `KEY_X_W0`, `KEY_X_W1`, etc., where each word is one 8-byte little-endian `long`:

| Key | W0 (bytes) | Length |
|-----|------------|--------|
| `"ts":` | `0x3a74730000000000` | 4 |
| `"level":` | `0x3a7665726c000000` | 7 |
| `"null"` | `0x6c756c6c00000000` | 4 |
| `"true"` | `0x6575727400000000` | 4 |

### Write Pattern

```java
buf[pos++] = ',';  // Inline comma
LE_LONG.set(buf, pos, KEY_TS_W0);  // Full 8-byte store
pos += 8;  // Full word

LE_LONG.set(buf, pos, KEY_LEVEL_W1);  // Tail store
pos += 1;  // Advance by actual length (1 byte), not 8
```

### Why Full-Store/Partial-Advance?

**Old approach (length-dispatch):**
```java
if (len > 8)  LE_LONG.set(buf, pos + 8, w1);
if (len > 16) LE_LONG.set(buf, pos + 16, w2);
if (len > 24) LE_LONG.set(buf, pos + 24, w3);
```
- Per-length branch
- Linear byte-store cost for partial tails

**New approach (overwrite trick):**
```java
LE_LONG.set(buf, pos, w0);   // Always full 8-byte
pos += 8;

LE_LONG.set(buf, pos, w1);   // Always full 8-byte (tail)
pos += 1;                    // Advance by actual length
```
- One wide store regardless of tail length
- No per-length branch
- Flat ~1.1 ns per word

---

## Performance Comparison

| Tail Length | Old (byte-store) | New (overwrite) | Speedup |
|-------------|------------------|-----------------|---------|
| 1 byte | 1.08 ns | 1.12 ns | ~0% (overhead of wide store) |
| 2 bytes | 1.07 ns | 1.12 ns | ~0% |
| 3 bytes | 1.50 ns | 1.14 ns | 23% |
| 5 bytes | 1.81 ns | 1.11 ns | 39% |
| 7 bytes | 2.86 ns | 1.24 ns | 57% |

**Key finding:** The overwrite trick is flat (~1.1 ns) across all tail lengths, while byte-store approaches grow linearly.

---

## Verification

- **Correctness:** `WriteOpsPackedTest` verifies byte-identical output at every offset (0–7)
- **Byte-identity:** `JsonLogWriterDirectBufferTest` ensures packed-word path matches stream fallback
- **Benchmarks:** `PackedWordWriteBenchmark` isolates the tail-store cost

---

## Related Concepts

- [03 — Packed Word Stores](03-packed-word-stores.md) — Overview of static data packing
- T8 in `doc/perf-exploration/` — Detailed implementation record
- [06 — Benchmarking](06-benchmarking.md) — How performance claims are measured
