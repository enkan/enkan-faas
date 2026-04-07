package enkan.faas.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * An in-memory map of internal class name → bytecode for all classes on the
 * project classpath (compiled output + dependency JARs).
 *
 * <p>Build the index once and reuse it across multiple {@link ClassDependencyAnalyzer}
 * invocations — one per {@code @FaasFunction}-annotated handler — so that JAR
 * bytes are not re-read from disk for every function.
 *
 * <p>Project classes (from {@code classesDir}) take priority over dependency JAR
 * entries of the same name: {@link Map#putIfAbsent} semantics apply to deps.
 */
public class ClassIndex {

    private final Map<String, byte[]> index;

    private ClassIndex(Map<String, byte[]> index) {
        this.index = Collections.unmodifiableMap(index);
    }

    /**
     * Builds the index from compiled project classes and all resolved dependency JARs.
     *
     * @param classesDir     {@code target/classes} directory
     * @param dependencyJars compile+runtime dependency JARs (test and provided excluded by caller)
     */
    public static ClassIndex build(Path classesDir, List<Path> dependencyJars) throws IOException {
        Map<String, byte[]> map = new HashMap<>(4096);

        if (Files.isDirectory(classesDir)) {
            try (var walk = Files.walk(classesDir)) {
                walk.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String name = toInternalName(classesDir, p);
                        try {
                            map.put(name, Files.readAllBytes(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException("Failed to read class file: " + p, e);
                        }
                    });
            } catch (UncheckedIOException e) {
                throw e.getCause(); // unwrap so callers see IOException
            }
        }

        for (Path jar : dependencyJars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                for (var e : (Iterable<? extends java.util.jar.JarEntry>) jf.stream()::iterator) {
                    String entryName = e.getName();
                    if (!entryName.endsWith(".class")) continue;
                    if (!isSafeEntryName(entryName)) continue; // Zip Slip guard
                    String name = stripClassSuffix(entryName);
                    if (map.containsKey(name)) continue; // project classes take priority
                    try (InputStream in = jf.getInputStream(e)) {
                        map.put(name, in.readAllBytes());
                    } catch (IOException ex) {
                        // unreadable entry — skip; BFS will treat this class as missing
                    }
                }
            }
        }

        return new ClassIndex(map);
    }

    /** Returns the bytecode for {@code internalName}, or {@code null} if not indexed. */
    public byte[] get(String internalName) {
        return index.get(internalName);
    }

    /**
     * Returns a new {@link ClassIndex} that includes the given entry in addition to
     * all existing entries. Used to register generated handler classes so that BFS
     * can traverse them without rebuilding the entire index from disk.
     */
    public ClassIndex withEntry(String internalName, byte[] bytes) {
        Map<String, byte[]> map = new HashMap<>(index);
        map.put(internalName, bytes);
        return new ClassIndex(Collections.unmodifiableMap(map));
    }

    /**
     * Converts a path under {@code root} to a slash-separated ZIP entry name,
     * preserving the original extension.
     *
     * <p>Example: {@code root=/a/b}, {@code path=/a/b/foo/Bar.class} → {@code "foo/Bar.class"}.
     */
    static String toEntryName(Path root, Path file) {
        return root.relativize(file).toString().replace(File.separatorChar, '/');
    }

    /**
     * Converts an absolute {@code .class} path under {@code root} to an internal
     * class name (slash-separated, no {@code .class} suffix).
     *
     * <p>Example: {@code root=/a/b}, {@code path=/a/b/foo/Bar.class} → {@code "foo/Bar"}.
     */
    static String toInternalName(Path root, Path classFile) {
        return stripClassSuffix(toEntryName(root, classFile));
    }

    /** Strips a trailing {@code .class} suffix from a slash-separated name. */
    static String stripClassSuffix(String name) {
        return name.endsWith(".class") ? name.substring(0, name.length() - 6) : name;
    }

    /**
     * Returns {@code false} if the entry name contains path traversal sequences
     * that could escape the JAR root (Zip Slip attack).
     *
     * <p>A safe name is non-empty, does not start with {@code /}, and contains
     * no {@code ..} path component.
     */
    static boolean isSafeEntryName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.startsWith("/")) return false;
        for (String component : name.split("/")) {
            if ("..".equals(component)) return false;
        }
        return true;
    }
}
