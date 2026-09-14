package hr.hrg.dialog.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Freshness guard for the generated stack-trace derivatives: the three checked-in
 * derivative files must be byte-identical to what the generator produces from the
 * canonical {@code JavaStackSanitizer}. Same "is up to date" pattern as
 * {@code StrPackerTest.jsonLogWriterSource_isUpToDate}.
 * <p>
 * If this test fails, edit {@code core/.../JavaStackSanitizer.java} (the canonical
 * source only), run the generator (see AGENTS.md) and commit the regenerated
 * derivatives — do not edit the derivative files directly.
 */
class StackSanitizerDerivativeTest {

    private static final String[] DERIVATIVE_PATHS = {
            "core/src/main/java/hr/hrg/dialog/core/JavaStackTraceWriter.java",
            "logback/src/main/java/hr/hrg/dialog/logback/JavaStackSanitizerLogback.java",
            "logback/src/main/java/hr/hrg/dialog/logback/JavaStackWriterLogback.java",
    };

    @Test
    void committedDerivatives_areUpToDate(@TempDir Path out) throws Exception {
        Path repoRoot = repoRoot();
        new StackSanitizerDerivativeGenerator(repoRoot).writeAll(out);
        for (String rel : DERIVATIVE_PATHS) {
            Path expected = repoRoot.resolve(rel);
            Path generated = out.resolve(rel);
            assertEquals(Files.readString(expected, StandardCharsets.UTF_8),
                    Files.readString(generated, StandardCharsets.UTF_8),
                    rel + " is not up to date with the canonical JavaStackSanitizer — "
                            + "run the StackSanitizerDerivativeGenerator and commit the output");
        }
    }

    /** Walks up from the working directory to the repository root (the parent of {@code project-automation}). */
    private static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("project-automation"))
                    && Files.isDirectory(dir.resolve("core"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root from "
                + System.getProperty("user.dir"));
    }
}
