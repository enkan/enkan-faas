package enkan.faas.maven;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JarBundlerTest {

    @ParameterizedTest(name = "isSafeEntryName({0}) == true")
    @ValueSource(strings = {
        "foo/Bar.class",
        "META-INF/services/java.sql.Driver",
        "META-INF/MANIFEST.MF",
        "enkan/faas/FunctionInvoker.class",
    })
    void safeNames(String name) {
        assertThat(ClassIndex.isSafeEntryName(name)).isTrue();
    }

    @ParameterizedTest(name = "isSafeEntryName({0}) == false (path traversal / invalid)")
    @ValueSource(strings = {
        "../etc/passwd",
        "foo/../../etc/passwd",
        "/absolute/path.class",
        "foo/../bar.class",
    })
    void unsafeNames(String name) {
        assertThat(ClassIndex.isSafeEntryName(name)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nullOrEmptyIsUnsafe(String name) {
        assertThat(ClassIndex.isSafeEntryName(name)).isFalse();
    }
}
