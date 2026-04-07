package enkan.faas.maven;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a shaded JAR containing only the entries whose class names appear in
 * {@code reachable}, plus non-class resources from the project's
 * {@code target/classes/} and {@code META-INF/} entries from dependency JARs.
 *
 * <p>Entry-writing policy:
 * <ul>
 *   <li>{@code META-INF/MANIFEST.MF} — written first, synthesised by this class.</li>
 *   <li>Project {@code target/classes/}: every {@code .class} in the reachable
 *       set and every non-{@code .class} file (resources, native-image config,
 *       etc.) are included unconditionally.</li>
 *   <li>Dependency JARs: {@code .class} entries in the reachable set and
 *       {@code META-INF/} non-{@code .class} entries (service-loader files,
 *       native-image metadata). Other dep resources are skipped.</li>
 *   <li>First-write wins for duplicate entry names.</li>
 * </ul>
 *
 * <p><b>Security:</b> JAR entry names are validated against path traversal
 * (Zip Slip) and entry sizes are capped at {@value #MAX_ENTRY_BYTES} bytes
 * to prevent ZIP bomb extraction.
 */
public class JarBundler {

    /** Maximum uncompressed size for a single JAR entry (256 MB). */
    static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;

    /**
     * Writes a shaded JAR to {@code outputJar}.
     *
     * @param reachable    internal class names to include (e.g. {@code "enkan/faas/FunctionInvoker"})
     * @param classIndex   pre-built index supplying project class bytes (avoids re-reading disk)
     * @param classesDir   {@code target/classes} of the project (used for non-class resources)
     * @param depJarBytes  pre-read dep JAR entries: entry name → bytes (only entries of interest)
     * @param outputJar    destination JAR path (parent dirs are created if absent)
     * @param mainClass    optional dot-separated Main-Class for the manifest; may be {@code null}
     * @throws IOException if writing fails — the caller should treat this as a build error
     */
    public static void bundle(Set<String> reachable,
                              ClassIndex classIndex,
                              Path classesDir,
                              Map<String, byte[]> depJarBytes,
                              Path outputJar,
                              String mainClass) throws IOException {

        Files.createDirectories(outputJar.getParent());

        Set<String> written = new HashSet<>();

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(outputJar)))) {

            writeManifest(zos, mainClass);
            written.add("META-INF/MANIFEST.MF");

            // Project classes and resources.
            // .class bytes come from the pre-built ClassIndex (already in memory);
            // non-class resource files are read from disk as they were not indexed.
            if (Files.isDirectory(classesDir)) {
                try (var walk = Files.walk(classesDir)) {
                    for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                        String entry = ClassIndex.toEntryName(classesDir, p);
                        if (entry.endsWith(".class")) {
                            String internalName = ClassIndex.stripClassSuffix(entry);
                            if (!reachable.contains(internalName)) continue;
                            if (written.add(entry)) {
                                byte[] bytes = classIndex.get(internalName);
                                if (bytes == null) {
                                    // Should not happen — index was built from this classesDir.
                                    // Fall back to disk rather than silently omitting the class.
                                    bytes = Files.readAllBytes(p);
                                }
                                writeBytes(zos, entry, bytes);
                            }
                        } else {
                            if (written.add(entry)) {
                                writeBytes(zos, entry, Files.readAllBytes(p));
                            }
                        }
                    }
                }
            }

            // Dependency entries (pre-filtered and pre-read by the caller).
            // .class entries are filtered to this function's reachable set;
            // META-INF/ non-class entries (SPI, native-image config) are included unconditionally.
            for (Map.Entry<String, byte[]> e : depJarBytes.entrySet()) {
                String name = e.getKey();
                if (name.endsWith(".class")
                        && !reachable.contains(ClassIndex.stripClassSuffix(name))) continue;
                if (written.add(name)) {
                    writeBytes(zos, name, e.getValue());
                }
            }
        }
    }

    /**
     * Reads the subset of dep JAR entries that are candidates for any shaded JAR:
     * {@code .class} files that appear in {@code reachable} and {@code META-INF/}
     * non-class entries (service-loader files, native-image metadata).
     *
     * <p>Opening JARs once here — rather than once per function in
     * {@link #bundle} — keeps the overall complexity O(M) instead of O(N×M)
     * where N is the number of functions and M is the number of dependency JARs.
     *
     * @param reachable set of internal class names across ALL functions (union)
     * @param depJars   compile+runtime dependency JARs
     * @return entry name → bytes map (first-seen wins across JARs)
     */
    public static Map<String, byte[]> readDepJarEntries(Set<String> reachable,
                                                        List<Path> depJars) throws IOException {
        Map<String, byte[]> result = new java.util.LinkedHashMap<>();
        for (Path jar : depJars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                for (JarEntry e : (Iterable<JarEntry>) jf.stream()::iterator) {
                    String name = e.getName();
                    if (name.endsWith("/")) continue;
                    if (name.equals("META-INF/MANIFEST.MF")) continue;
                    if (!ClassIndex.isSafeEntryName(name)) continue; // Zip Slip guard

                    if (name.endsWith(".class")) {
                        if (!reachable.contains(ClassIndex.stripClassSuffix(name))) continue;
                    } else if (!name.startsWith("META-INF/")) {
                        continue;
                    }

                    if (result.containsKey(name)) continue; // first-JAR wins

                    long size = e.getSize();
                    if (size > MAX_ENTRY_BYTES) continue; // known-large: reject immediately

                    try (InputStream in = jf.getInputStream(e)) {
                        // For entries with unknown size (getSize() == -1, e.g. ZIP64 or STORED
                        // without a size field), readAllBytes() is uncapped. Read up to the limit
                        // + 1 byte so we can detect oversized entries without loading them fully.
                        byte[] data = size < 0
                                ? in.readNBytes((int) MAX_ENTRY_BYTES + 1)
                                : in.readAllBytes();
                        if (data.length > MAX_ENTRY_BYTES) continue; // unknown-size ZIP bomb: reject
                        result.put(name, data);
                    } catch (IOException ex) {
                        // unreadable entry — skip
                    }
                }
            }
        }
        return result;
    }

    private static void writeManifest(ZipOutputStream zos, String mainClass) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null && !mainClass.isBlank()) {
            mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        mf.write(zos);
        zos.closeEntry();
    }

    private static void writeBytes(ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }
}
