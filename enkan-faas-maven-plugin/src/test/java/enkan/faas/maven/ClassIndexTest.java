package enkan.faas.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClassIndexTest {

    @ParameterizedTest(name = "stripClassSuffix({0}) == {1}")
    @CsvSource({
        "foo/Bar.class,         foo/Bar",
        "foo/Bar$Inner.class,   foo/Bar$Inner",
        "foo/Bar,               foo/Bar",
        "foo/Bar.xml,           foo/Bar.xml",
        ".class,                ",
    })
    void stripClassSuffix(String input, String expected) {
        assertThat(ClassIndex.stripClassSuffix(input))
                .isEqualTo(expected == null ? "" : expected);
    }

    @Test
    void toInternalName_convertsCorrectly() {
        Path root = Path.of("/a/b");
        Path classFile = Path.of("/a/b/foo/Bar.class");
        assertThat(ClassIndex.toInternalName(root, classFile)).isEqualTo("foo/Bar");
    }

    @Test
    void toEntryName_preservesSuffix() {
        Path root = Path.of("/a/b");
        Path file = Path.of("/a/b/foo/Bar.class");
        assertThat(ClassIndex.toEntryName(root, file)).isEqualTo("foo/Bar.class");
    }

    @Test
    void toEntryName_worksForResources() {
        Path root = Path.of("/a/b");
        Path file = Path.of("/a/b/META-INF/services/java.sql.Driver");
        assertThat(ClassIndex.toEntryName(root, file))
                .isEqualTo("META-INF/services/java.sql.Driver");
    }
}
