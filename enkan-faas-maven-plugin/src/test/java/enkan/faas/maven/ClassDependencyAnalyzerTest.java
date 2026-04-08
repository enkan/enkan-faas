package enkan.faas.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClassDependencyAnalyzerTest {

    @ParameterizedTest(name = "normalise({0}) == {1}")
    @CsvSource({
        // plain internal name → returned as-is
        "enkan/faas/FunctionInvoker,        enkan/faas/FunctionInvoker",
        // object array descriptor → unwrapped to internal name
        "[Lenkan/faas/FunctionInvoker;,     enkan/faas/FunctionInvoker",
        // 2D array → still unwrapped correctly
        "[[Lenkan/faas/Foo;,                enkan/faas/Foo",
        // JDK prefix → null (represented as empty string sentinel in CSV; checked separately)
        // User-land class with similar prefix must NOT be excluded
        "jdkx/SomeClass,                    jdkx/SomeClass",
    })
    void normalise_returnsExpected(String raw, String expected) {
        assertThat(ClassDependencyAnalyzer.normalise(raw.strip()))
                .isEqualTo(expected.strip());
    }

    @ParameterizedTest(name = "normalise({0}) == null (JDK boot prefix)")
    @ValueSource(strings = {
        "java/lang/String",
        "javax/sql/DataSource",
        "sun/misc/Unsafe",
        "com/sun/proxy/$Proxy0",
        "jdk/internal/reflect/Reflection",
    })
    void normalise_returnsNullForJdkClasses(String raw) {
        assertThat(ClassDependencyAnalyzer.normalise(raw)).isNull();
    }

    @Test
    void normalise_returnsNonNullForJakartaClasses() {
        // jakarta.* ships as regular compile-scope JARs (e.g. jakarta.inject-api),
        // so the BFS must NOT exclude it by prefix — missing classes are silently
        // skipped at BFS traversal time when absent from the class index.
        assertThat(ClassDependencyAnalyzer.normalise("jakarta/inject/Inject"))
                .isEqualTo("jakarta/inject/Inject");
    }

    @ParameterizedTest(name = "normalise({0}) == null (primitive/short)")
    @ValueSource(strings = {"I", "B", "Z", "J", "D", "F", "C", "S", "V"})
    void normalise_returnsNullForPrimitiveDescriptors(String raw) {
        assertThat(ClassDependencyAnalyzer.normalise(raw)).isNull();
    }

    @ParameterizedTest(name = "normalise(primitive array {0}) == null")
    @ValueSource(strings = {"[I", "[B", "[Z", "[[I"})
    void normalise_returnsNullForPrimitiveArrays(String raw) {
        assertThat(ClassDependencyAnalyzer.normalise(raw)).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void normalise_returnsNullForNullOrEmpty(String raw) {
        assertThat(ClassDependencyAnalyzer.normalise(raw)).isNull();
    }
}
