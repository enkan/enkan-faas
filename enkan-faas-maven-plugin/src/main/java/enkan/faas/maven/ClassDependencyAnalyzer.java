package enkan.faas.maven;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the transitive class dependency closure from a root class using the
 * Java 25 Class File API.
 *
 * <p>Accepts a pre-built {@link ClassIndex} so the index is constructed once
 * and reused across multiple {@code analyze()} calls — one per
 * {@code @FaasFunction}-annotated handler.
 *
 * <p>JDK boot classes ({@code java/}, {@code javax/}, {@code sun/}, {@code com/sun/},
 * {@code jdk/}) are excluded from the closure: they live in the JDK and are never
 * present in project or dependency JARs. {@code jakarta/} is intentionally NOT excluded
 * because Jakarta EE APIs (e.g. {@code jakarta.inject}, {@code jakarta.ws.rs}) ship as
 * regular compile-scope JARs and must be included in the shaded JAR.
 * Classes not found in the index (e.g. {@code provided}-scope deps) are silently skipped.
 */
public class ClassDependencyAnalyzer {

    private static final List<String> EXCLUDED_PREFIXES = List.of(
            "java/", "javax/", "sun/", "com/sun/", "jdk/"
    );

    private final ClassIndex index;

    public ClassDependencyAnalyzer(ClassIndex index) {
        this.index = index;
    }

    /**
     * Returns the set of internal class names (e.g. {@code "enkan/faas/FunctionInvoker"})
     * transitively reachable from {@code rootInternalName}.
     */
    public Set<String> analyze(String rootInternalName) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        enqueue(rootInternalName, visited, queue);

        // Reuse a single scratch set across BFS iterations to avoid per-node allocation.
        Set<String> scratch = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            byte[] bytes = index.get(current);
            if (bytes == null) continue;

            scratch.clear();
            extractReferences(bytes, scratch);
            for (String ref : scratch) {
                enqueue(ref, visited, queue);
            }
        }
        return Collections.unmodifiableSet(visited);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    // ClassFile.of() is stateless (holds only immutable options) and safe to share
    // across threads. Cached here to avoid repeated allocation in the BFS hot path.
    private static final ClassFile CLASS_FILE = ClassFile.of();

    /** Extracts all referenced class names from {@code classBytes} into {@code out}. */
    private static void extractReferences(byte[] classBytes, Set<String> out) {
        ClassModel model;
        try {
            model = CLASS_FILE.parse(classBytes);
        } catch (IllegalArgumentException e) {
            return; // malformed class bytes (e.g. in a dep JAR) — skip
        }

        for (PoolEntry entry : model.constantPool()) {
            switch (entry) {
                case ClassEntry ce -> {
                    String name = normalise(ce.asInternalName());
                    if (name != null) out.add(name);
                }
                case MethodRefEntry mre -> {
                    String name = normalise(mre.owner().asInternalName());
                    if (name != null) out.add(name);
                }
                case InterfaceMethodRefEntry ime -> {
                    String name = normalise(ime.owner().asInternalName());
                    if (name != null) out.add(name);
                }
                case FieldRefEntry fre -> {
                    String name = normalise(fre.owner().asInternalName());
                    if (name != null) out.add(name);
                }
                default -> {}
            }
        }
    }

    /**
     * Strips array dimensions and object-type wrappers, then returns the
     * plain internal class name, or {@code null} if the name represents a
     * primitive, a JDK class, or is otherwise unusable.
     */
    static String normalise(String raw) {
        if (raw == null || raw.isEmpty()) return null;

        String s = raw;
        while (s.startsWith("[")) {
            s = s.substring(1);
        }
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.length() <= 1) return null;

        for (String prefix : EXCLUDED_PREFIXES) {
            if (s.startsWith(prefix)) return null;
        }
        return s;
    }

    private static void enqueue(String name, Set<String> visited, Deque<String> queue) {
        if (name != null && !name.isEmpty() && visited.add(name)) {
            queue.add(name);
        }
    }
}
