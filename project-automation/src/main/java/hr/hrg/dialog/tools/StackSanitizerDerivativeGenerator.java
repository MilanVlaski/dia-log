package hr.hrg.dialog.tools;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marker-driven boilerplate generator for the dia-log project.
 * <p>
 * Reads the canonical {@code JavaStackSanitizer} source (in {@code dia-log-core}) and derives the
 * three sibling classes from it by processing the marker comments embedded in that source:
 * <ul>
 *   <li>{@code JavaStackTraceWriter}      — core, no-filter (drops {@code Predicate} and fallback)</li>
 *   <li>{@code JavaStackSanitizerLogback} — logback, filter-enabled ({@code IThrowableProxy} input)</li>
 *   <li>{@code JavaStackWriterLogback}    — logback, no-filter</li>
 * </ul>
 * <p>
 * Marker convention (see the {@code JavaStackSanitizer} class javadoc): a marker comment line
 * annotates the following line:
 * <pre>{@code
 *   // @core            next line: kept in JavaStackTraceWriter, commented out in the logback variants
 *   // @sanitizer       next line: kept in JavaStackSanitizerLogback, commented out in the writers
 *   // @writer          next line: kept in JavaStackWriterLogback, commented out elsewhere
 *   // @core,writer     next line: kept in JavaStackTraceWriter and JavaStackWriterLogback
 *   // @restore:x,y     next (already-commented) line: uncommented in variants x,y
 * }</pre>
 * Lines without a preceding marker are shared and kept in every variant. Code not needed in a
 * variant is commented out in the generated derivative (never deleted). The generator also applies
 * the input/accessor substitutions for logback and removes the {@code Predicate} parameter for the
 * no-filter variants. Every expected substitution is verified and fails loudly
 * (no silent no-ops) if the canonical source has drifted from the generator's
 * expectations.
 * <p>
 * Usage from the repository root:
 * <pre>{@code
 *   mvn -pl project-automation compile exec:java \
 *       -Dexec.mainClass=hr.hrg.dialog.tools.StackSanitizerDerivativeGenerator
 * }</pre>
 */
public final class StackSanitizerDerivativeGenerator {

    private static final Path DEFAULT_REPO_ROOT = Paths.get("").toAbsolutePath();

    private static final String CORE = "core";
    private static final String SANITIZER = "sanitizer";
    private static final String WRITER = "writer";

    private final JavaParser javaParser = new JavaParser();
    private final Path repoRoot;

    public StackSanitizerDerivativeGenerator(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public StackSanitizerDerivativeGenerator() {
        this(DEFAULT_REPO_ROOT);
    }

    public static void main(String[] args) throws IOException {
        Path root = args.length >= 1 ? Paths.get(args[0]).toAbsolutePath() : DEFAULT_REPO_ROOT;
        new StackSanitizerDerivativeGenerator(root).generateAll();
    }

    /** Reads the source of truth and regenerates all three derivatives in the repository. */
    public void generateAll() throws IOException {
        writeAll(repoRoot);
    }

    /**
     * Regenerates all three derivatives from the canonical source, writing them under
     * {@code outputRoot} (the repository root by default). Target paths are repo-relative,
     * so the same relative layout is produced under any output root.
     */
    public void writeAll(Path outputRoot) throws IOException {
        Path source = sourceSanitizerPath();
        String sourceText = Files.readString(source, StandardCharsets.UTF_8);
        String methods = extractMethods(sourceText);

        write(outputRoot.resolve(CORE_WRITER_REL), assemble(CORE, derive(CORE, methods)));
        write(outputRoot.resolve(LB_SANITIZER_REL), assemble(SANITIZER, derive(SANITIZER, methods)));
        write(outputRoot.resolve(LB_WRITER_REL), assemble(WRITER, derive(WRITER, methods)));

        System.out.println("Generated 3 derivative classes from " + source);
    }

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    private static final String SOURCE_REL =
            "core/src/main/java/hr/hrg/dialog/core/JavaStackSanitizer.java";
    private static final String CORE_WRITER_REL =
            "core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java";
    private static final String LB_SANITIZER_REL =
            "logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java";
    private static final String LB_WRITER_REL =
            "logback/src/main/java/hr/hrg/dialog/logback/JavaStackWriterLogback.java";

    private Path sourceSanitizerPath() {
        return repoRoot.resolve(SOURCE_REL);
    }

    // ------------------------------------------------------------------
    // Source extraction
    // ------------------------------------------------------------------

    /** Extracts the method declarations from the class body (from the first method to the end). */
    private static String extractMethods(String sourceText) {
        // Find the class body opening brace.
        int classOpen = sourceText.indexOf("public class JavaStackSanitizer {");
        if (classOpen < 0) {
            throw new IllegalStateException("Cannot find class declaration in source");
        }
        int bodyStart = sourceText.indexOf('{', classOpen);
        if (bodyStart < 0) {
            throw new IllegalStateException("Cannot find class body in source");
        }
        // Find the first method declaration (after the field declarations).
        int fingerprintIdx = sourceText.indexOf("public static long fingerprint", bodyStart);
        if (fingerprintIdx < 0) {
            throw new IllegalStateException("Cannot find first method in source");
        }
        // The extracted section runs from the first method to the class's final closing brace.
        String fromFirstMethod = sourceText.substring(fingerprintIdx);
        int finalBrace = fromFirstMethod.lastIndexOf('}');
        return fromFirstMethod.substring(0, finalBrace);
    }

    // ------------------------------------------------------------------
    // Marker processing
    // ------------------------------------------------------------------

    private static final class Marker {
        final boolean restore;
        final boolean blockBegin;
        final boolean blockEnd;
        final List<String> variants;

        Marker(boolean restore, boolean blockBegin, boolean blockEnd, List<String> variants) {
            this.restore = restore;
            this.blockBegin = blockBegin;
            this.blockEnd = blockEnd;
            this.variants = variants;
        }
    }

    /** Parses a marker comment line, or returns null if the line is not a marker. */
    private static Marker parseMarker(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("// @")) {
            return null;
        }
        String spec = trimmed.substring(4).trim(); // strip "// @"
        boolean restore = false;
        boolean blockBegin = false;
        boolean blockEnd = false;
        if (spec.startsWith("restore:")) {
            restore = true;
            spec = spec.substring("restore:".length());
        }
        if (spec.endsWith(":begin")) {
            blockBegin = true;
            spec = spec.substring(0, spec.length() - ":begin".length());
        } else if (spec.endsWith(":end")) {
            blockEnd = true;
            spec = spec.substring(0, spec.length() - ":end".length());
        }
        List<String> variants = new ArrayList<>();
        for (String v : spec.split(",")) {
            String s = v.trim();
            if (!s.isEmpty()) {
                variants.add(s);
            }
        }
        if (variants.isEmpty()) {
            return null;
        }
        return new Marker(restore, blockBegin, blockEnd, variants);
    }

    /**
     * Derives the methods section for the given variant by processing marker lines and applying
     * the signature/accessor substitutions.
     */
    private static String derive(String variant, String methods) {
        List<String> lines = new ArrayList<>(List.of(methods.split("\n", -1)));
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Marker marker = parseMarker(line);
            if (marker != null) {
                // Block marker: comment/uncomment the whole block between :begin and :end.
                if (marker.blockBegin) {
                    boolean keep = marker.variants.contains(variant);
                    // Collect the block until the matching :end marker.
                    List<String> block = new ArrayList<>();
                    int j = i + 1;
                    boolean closed = false;
                    while (j < lines.size()) {
                        String l = lines.get(j);
                        Marker m2 = parseMarker(l);
                        if (m2 != null && m2.blockEnd && m2.variants.equals(marker.variants)) {
                            closed = true;
                            j++;
                            break;
                        }
                        block.add(l);
                        j++;
                    }
                    if (!closed) {
                        throw new IllegalStateException("Unterminated block marker: " + line);
                    }
                    for (String bl : block) {
                        out.add(keep ? bl : comment(bl));
                    }
                    i = j;
                    continue;
                }
                if (marker.blockEnd) {
                    // Should be consumed by its :begin; if seen alone, ignore.
                    i++;
                    continue;
                }

                // The marker annotates the next non-blank line.
                int j = i + 1;
                while (j < lines.size() && lines.get(j).isBlank()) {
                    out.add(lines.get(j)); // preserve blank lines
                    j++;
                }
                if (j >= lines.size()) {
                    break;
                }
                String target = lines.get(j);
                boolean keep = marker.variants.contains(variant);
                if (isMethodStart(target)) {
                    // Whole-method removal: if the variant is not in the marker, drop the method.
                    if (!keep) {
                        int end = findMethodEnd(lines, j);
                        i = end + 1;
                        continue;
                    }
                    // Keep: emit the method (marker removed), then continue normally.
                    out.add(target);
                    i = j + 1;
                    continue;
                }
                // Single-line target.
                if (marker.restore) {
                    out.add(keep ? uncomment(target) : target);
                } else {
                    out.add(keep ? target : comment(target));
                }
                i = j + 1;
                continue;
            }
            out.add(line);
            i++;
        }
        String derived = String.join("\n", out);
        return applySubstitutions(variant, derived);
    }

    private static boolean isMethodStart(String line) {
        String t = line.trim();
        return t.startsWith("public static ") || t.startsWith("private static ");
    }

    /** Finds the index of the line that closes the method starting at {@code start} (brace-aware). */
    private static int findMethodEnd(List<String> lines, int start) {
        int depth = 0;
        for (int i = start; i < lines.size(); i++) {
            String code = stripComments(lines.get(i));
            for (char c : code.toCharArray()) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return lines.size() - 1;
    }

    /** Removes {@code //} line comments and {@code /* *}{@code /} block comments from a line for brace counting. */
    private static String stripComments(String line) {
        String s = line;
        int lineComment = s.indexOf("//");
        if (lineComment >= 0) {
            s = s.substring(0, lineComment);
        }
        // Remove any /* ... */ block comment fragments (approximate: drop from /* to the end).
        int blockComment = s.indexOf("/*");
        if (blockComment >= 0) {
            s = s.substring(0, blockComment);
        }
        return s;
    }

    private static String comment(String line) {
        // Comment out while preserving indentation.
        String indent = leadingWhitespace(line);
        return indent + "// " + line.trim();
    }

    private static String uncomment(String line) {
        String indent = leadingWhitespace(line);
        String rest = line.trim();
        if (rest.startsWith("//")) {
            rest = rest.substring(2).trim();
        }
        return indent + rest;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    // ------------------------------------------------------------------
    // Substitutions
    // ------------------------------------------------------------------

    private static String applySubstitutions(String variant, String methods) {
        String text = methods;
        if (isLogback(variant)) {
            // Adapt input type and throwable accessor for logback proxy shape.
            text = requireLiteral(text, "StackTraceElement[]", "StackTraceElementProxy[]", variant);
            text = requireLiteral(text, "Throwable rootCause", "IThrowableProxy rootCause", variant);
            // Fallback blocks index into the proxy array; get the underlying StackTraceElement.
            text = requireLiteral(text, "StackTraceElement el = trace[i];",
                    "StackTraceElement el = trace[i].getStackTraceElement();", variant);
            if (SANITIZER.equals(variant)) {
                // The logback sanitizer does not declare the byte constants; qualify them.
                text = requireRegexReplace(text, "\\bNEWLINE_JSON_BYTES\\b", "JavaStackSanitizer.NEWLINE_JSON_BYTES", variant);
                text = requireRegexReplace(text, "\\bNEWLINE_BYTES\\b", "JavaStackSanitizer.NEWLINE_BYTES", variant);
                text = requireRegexReplace(text, "\\bDOT_BYTES\\b", "JavaStackSanitizer.DOT_BYTES", variant);
                text = requireRegexReplace(text, "\\bLAMBDA_METHOD_BYTES\\b", "JavaStackSanitizer.LAMBDA_METHOD_BYTES", variant);
                text = requireRegexReplace(text, "\\bLAMBDA_SUFFIX_FOR_CLASS\\b", "JavaStackSanitizer.LAMBDA_SUFFIX_FOR_CLASS", variant);
                text = requireRegexReplace(text, "\\bLAMBDA_PREFIX_FOR_METHOD\\b", "JavaStackSanitizer.LAMBDA_PREFIX_FOR_METHOD", variant);
                text = requireRegexReplace(text, "\\bstringWriteStrategy\\b", "JavaStackSanitizer.stringWriteStrategy", variant);
            } else { // WRITER
                // The logback writer declares the byte constants but not stringWriteStrategy.
                text = requireRegexReplace(text, "\\bstringWriteStrategy\\b", "JavaStackTraceWriter.stringWriteStrategy", variant);
            }
        }
        if (isNoFilter(variant)) {
            // Drop the Predicate filter parameter from every method except fingerprint.
            // Fails loudly if nothing was removed: the canonical source has drifted.
            String before = text;
            text = removeFilterParamsExceptFingerprint(text);
            if (text.equals(before)) {
                throw new IllegalStateException("StackSanitizerDerivativeGenerator: no "
                        + "Predicate<String> filter parameters were removed in variant "
                        + variant + " (canonical source has drifted from the generator's expectations)");
            }
            // The fingerprint method's addFromTrace call must also drop the filter argument.
            text = requireLiteral(text,
                    "addFromTrace(rootCause.getStackTrace(), filter, stream)",
                    "addFromTrace(rootCause.getStackTrace(), stream)", variant);
            // The core writer keeps a local addFromTraceElement helper; make it private.
            if (CORE.equals(variant)) {
                text = requireLiteral(text,
                        "public static void addFromTraceElement",
                        "private static void addFromTraceElement", variant);
            }
            // Delegation calls inside no-filter method bodies must drop the filter argument.
            text = requireLiteral(text,
                    "addFromTraceToOutputStreamWithNewline(trace, filter,out, NEWLINE_BYTES)",
                    "addFromTraceToOutputStreamWithNewline(trace, out, NEWLINE_BYTES)", variant);
            text = requireLiteral(text,
                    "addFromTraceToOutputStreamWithNewline(trace, filter,out, NEWLINE_JSON_BYTES)",
                    "addFromTraceToOutputStreamWithNewline(trace, out, NEWLINE_JSON_BYTES)", variant);
            text = requireLiteral(text,
                    "addFromTraceToOutputStreamWithNewlineAndFingerprint(trace, filter, out, NEWLINE_JSON_BYTES, throwableClassName, stream)",
                    "addFromTraceToOutputStreamWithNewlineAndFingerprint(trace, out, NEWLINE_JSON_BYTES, throwableClassName, stream)", variant);
        }
        return text;
    }

    /**
     * Applies a required literal substitution, failing loudly if the expected
     * literal is missing: a silent no-op would let the canonical source drift
     * from the generator's expectations.
     */
    private static String requireLiteral(String text, String from, String to, String variant) {
        if (!text.contains(from)) {
            throw new IllegalStateException("StackSanitizerDerivativeGenerator: expected literal not found in variant "
                    + variant + " (canonical source has drifted from the generator's expectations): " + from);
        }
        return text.replace(from, to);
    }

    /** Applies a required regex substitution, failing loudly if the pattern is absent. */
    private static String requireRegexReplace(String text, String regex, String to, String variant) {
        int matches = 0;
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) {
            matches++;
        }
        if (matches == 0) {
            throw new IllegalStateException("StackSanitizerDerivativeGenerator: expected pattern not found in variant "
                    + variant + " (canonical source has drifted from the generator's expectations): " + regex);
        }
        return text.replaceAll(regex, to);
    }

    private static boolean isLogback(String variant) {
        return SANITIZER.equals(variant) || WRITER.equals(variant);
    }

    private static boolean isNoFilter(String variant) {
        return CORE.equals(variant) || WRITER.equals(variant);
    }

    /** Removes {@code Predicate<String> filter} from method signatures, keeping it on {@code fingerprint}. */
    private static String removeFilterParamsExceptFingerprint(String text) {
        // Walk method-by-method: find "public static <type> <name>(".
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < text.length()) {
            int m = text.indexOf("public static ", idx);
            if (m < 0) {
                sb.append(text, idx, text.length());
                break;
            }
            sb.append(text, idx, m);
            int open = text.indexOf('(', m);
            int close = text.indexOf(')', open);
            String header = text.substring(m, open);
            String name = methodName(header);
            String params = text.substring(open + 1, close);
            String newParams;
            if (name.equals("fingerprint")) {
                newParams = params;
            } else {
                // Remove a "," + optional whitespace + "Predicate<String> filter" parameter.
                newParams = params.replaceAll("(?s),\\s*Predicate<String>\\s+filter", "").trim();
                // Handle the case where the filter param is the first parameter.
                newParams = newParams.replaceFirst("(?s)^Predicate<String>\\s+filter,\\s*", "").trim();
            }
            sb.append(header).append('(').append(newParams).append(')');
            idx = close + 1;
        }
        return sb.toString();
    }

    private static String methodName(String header) {
        String t = header.trim();
        // "public static long " or "public static void " etc. -> last token
        String[] parts = t.split("\\s+");
        return parts[parts.length - 1];
    }

    // ------------------------------------------------------------------
    // Assembly
    // ------------------------------------------------------------------

    private static String assemble(String variant, String methods) {
        Header h = header(variant);
        return h.packageLine + "\n\n"
                + String.join("\n", h.imports) + "\n\n"
                + h.javadoc + "\n"
                + "public class " + h.className + " {\n\n"
                + h.fields + "\n"
                + methods + "\n"
                + "}\n";
    }

    private record Header(String packageLine, List<String> imports, String javadoc, String className, String fields) {}

    private static Header header(String variant) {
        return switch (variant) {
            case CORE -> new Header(
                    "package hr.hrg.dialog.core;",
                    List.of(
                            "import java.io.IOException;",
                            "import java.io.OutputStream;",
                            "import java.nio.charset.StandardCharsets;",
                            "import java.util.function.Predicate;"),
                    """
                    /**
                     * No-filter derivative of {@link JavaStackSanitizer} for {@link StackTraceElement[]} input.
                     * <p>
                     * @generated by {@code StackSanitizerDerivativeGenerator} from {@code JavaStackSanitizer}.
                     * This class keeps the same normalization rules as {@link JavaStackSanitizer} but removes
                     * filtering and fallback logic from the public API.
                     */""",
                    "JavaStackTraceWriter",
                    """
                            public static final byte[] DOT_BYTES = {'.'};
                            public static final byte[] NEWLINE_BYTES = {'\\n'};
                            public static final byte[] NEWLINE_JSON_BYTES = {'\\\\','n'};
                            public static final byte[] LAMBDA_METHOD_BYTES = "lambda".getBytes(StandardCharsets.UTF_8);
                            public static final StringByteExtractor.ByteWriter stringWriteStrategy = StringByteExtractor.getStrategy();
                            public static final String LAMBDA_SUFFIX_FOR_CLASS = "$$Lambda$";
                            public static final String LAMBDA_PREFIX_FOR_METHOD = "lambda$";

                            private static final byte DOT_BYTE = '.';
                            private static final byte NEWLINE_BYTE = '\\n';""");

            case SANITIZER -> new Header(
                    "package hr.hrg.dialog.logback;",
                    List.of(
                            "import ch.qos.logback.classic.spi.IThrowableProxy;",
                            "import ch.qos.logback.classic.spi.StackTraceElementProxy;",
                            "import hr.hrg.dialog.core.JavaStackSanitizer;",
                            "import hr.hrg.dialog.core.Wyhash64;",
                            "",
                            "import java.io.IOException;",
                            "import java.io.OutputStream;",
                            "import java.util.function.Predicate;"),
                    """
                    /**
                     * Logback-proxy derivative of {@link JavaStackSanitizer}.
                     * <p>
                     * @generated by {@code StackSanitizerDerivativeGenerator} from {@code JavaStackSanitizer}.
                     * This class keeps sanitizer semantics (filter + fallback + normalization) while adapting
                     * input from {@link IThrowableProxy} and {@link StackTraceElementProxy}.
                     */""",
                    "JavaStackSanitizerLogback",
                    """
                            private static final byte DOT_BYTE = '.';
                            private static final byte NEWLINE_BYTE = '\\n';""");

            case WRITER -> new Header(
                    "package hr.hrg.dialog.logback;",
                    List.of(
                            "import ch.qos.logback.classic.spi.IThrowableProxy;",
                            "import ch.qos.logback.classic.spi.StackTraceElementProxy;",
                            "import hr.hrg.dialog.core.JavaStackSanitizer;",
                            "import hr.hrg.dialog.core.JavaStackTraceWriter;",
                            "import hr.hrg.dialog.core.Wyhash64;",
                            "",
                            "import java.io.IOException;",
                            "import java.io.OutputStream;",
                            "import java.nio.charset.StandardCharsets;",
                            "import java.util.function.Predicate;"),
                    """
                    /**
                     * Logback-proxy no-filter derivative aligned with {@link JavaStackTraceWriter} semantics.
                     * <p>
                     * @generated by {@code StackSanitizerDerivativeGenerator} from {@code JavaStackSanitizer}.
                     * This is the logback-side filter-free counterpart to {@link hr.hrg.dialog.core.JavaStackTraceWriter}.
                     */""",
                    "JavaStackWriterLogback",
                    """
                            public static final byte[] DOT_BYTES = JavaStackTraceWriter.DOT_BYTES;
                            public static final byte[] NEWLINE_BYTES = JavaStackTraceWriter.NEWLINE_BYTES;
                            public static final byte[] NEWLINE_JSON_BYTES = JavaStackTraceWriter.NEWLINE_JSON_BYTES;
                            public static final byte[] LAMBDA_METHOD_BYTES = "lambda".getBytes(StandardCharsets.UTF_8);
                            public static final String LAMBDA_SUFFIX_FOR_CLASS = JavaStackTraceWriter.LAMBDA_SUFFIX_FOR_CLASS;
                            public static final String LAMBDA_PREFIX_FOR_METHOD = JavaStackTraceWriter.LAMBDA_PREFIX_FOR_METHOD;

                            private static final byte DOT_BYTE = '.';
                            private static final byte NEWLINE_BYTE = '\\n';""");

            default -> throw new IllegalArgumentException("Unknown variant " + variant);
        };
    }

    private void write(Path target, String code) throws IOException {
        // Validate the generated source re-parses cleanly before writing.
        ParseResult<CompilationUnit> result = javaParser.parse(code);
        if (!result.isSuccessful()) {
            // Dump the failing output for debugging.
            Files.writeString(repoRoot.resolve("target/generated-debug.java"), code, StandardCharsets.UTF_8);
            throw new IllegalStateException("Generated source failed to parse for " + target
                    + " -> " + result.getProblems());
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, code, StandardCharsets.UTF_8);
        System.out.println("  wrote " + target);
    }
}
