package enkan.faas.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces one shaded JAR per {@code @FaasFunction}-annotated
 * {@link enkan.config.ApplicationFactory} class found in {@code target/classes/}.
 *
 * <p>For each annotated factory the plugin:
 * <ol>
 *   <li>Generates a vendor-specific handler class (bytecode) from the
 *       {@code adapter} attribute and writes it to {@code target/classes/}.</li>
 *   <li>Computes the transitive BFS dependency closure from the generated handler.</li>
 *   <li>Writes {@code target/{name}-shaded.jar} containing only the reachable classes.</li>
 * </ol>
 *
 * <p>Bound to the {@code package} lifecycle phase. Runs after {@code compile}
 * so {@code target/classes/} is fully populated.
 *
 * <p>Typical pom.xml usage:
 * <pre>{@code
 * <plugin>
 *   <groupId>net.unit8.enkan.faas</groupId>
 *   <artifactId>enkan-faas-maven-plugin</artifactId>
 *   <version>0.1.0-SNAPSHOT</version>
 *   <executions>
 *     <execution>
 *       <goals><goal>bundle</goal></goals>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 *
 * <p>Each {@link enkan.config.ApplicationFactory} must carry
 * {@code @FaasFunction(name = "...", adapter = SomeAdapter.class)}:
 * <pre>{@code
 * @FaasFunction(name = "todo-read", adapter = ApiGatewayV2Adapter.class)
 * public class TodoReadApplicationFactory implements ApplicationFactory<...> { ... }
 * }</pre>
 *
 * Output: {@code target/{name}-shaded.jar} for each annotated class.
 */
@Mojo(
    name = "bundle",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = true
)
public class BundleMojo extends AbstractMojo {

    // Class descriptor of @FaasFunction in the "L<internal-name>;" field-descriptor format.
    // ann.className().stringValue() returns this format (not the bare internal name), so the
    // "L...;" wrapping is intentional and correct.
    // Kept as a string literal (not ClassDesc.of(...).descriptorString()) to avoid a compile-time
    // dependency on enkan-component-faas, which targets Java 25 and would cause
    // maven-plugin-plugin's ASM-based scanner to fail with "Unsupported class file major version 69".
    private static final String FAAS_FUNCTION_DESC = "Lenkan/faas/FaasFunction;";

    // Lambda function names: letters, digits, hyphens, underscores; 1–64 characters.
    // https://docs.aws.amazon.com/lambda/latest/api/API_CreateFunction.html#lambda-CreateFunction-request-FunctionName
    private static final java.util.regex.Pattern FUNCTION_NAME_PATTERN =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    // ClassFile.of() is stateless (holds only immutable options) and safe to share
    // across threads — the mojo declares threadSafe = true.
    private static final ClassFile CLASS_FILE = ClassFile.of();

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        Path classesDir = outputDirectory.toPath();
        List<Path> depJars = resolvedDependencyJars();
        Path targetDir = Path.of(project.getBuild().getDirectory());

        // Build the class index first — scanHandlers reads bytes from it,
        // avoiding a separate disk pass over target/classes/.
        ClassIndex classIndex;
        try {
            classIndex = ClassIndex.build(classesDir, depJars);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to build class index", e);
        }

        List<AnnotatedHandler> handlers = scanHandlers(classIndex, classesDir);
        if (handlers.isEmpty()) {
            getLog().warn("No @FaasFunction-annotated ApplicationFactory classes found in "
                          + classesDir + " — nothing to bundle.");
            return;
        }

        // Generate handler classes and update the class index with the new bytecode.
        List<AnnotatedHandler> handlersWithGenerated = new ArrayList<>();
        for (AnnotatedHandler handler : handlers) {
            String generatedInternal;
            try {
                generatedInternal = HandlerGenerator.generate(
                        handler.name(), handler.internalName(),
                        handler.adapterInternalName(), classesDir);
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to generate handler for '" + handler.name() + "'", e);
            }

            if (generatedInternal != null) {
                getLog().info("Generated handler: " + generatedInternal.replace('/', '.'));
                // Add the generated class bytes to the index so BFS can traverse it.
                Path generatedPath = classesDir.resolve(generatedInternal + ".class");
                try {
                    byte[] generatedBytes = Files.readAllBytes(generatedPath);
                    classIndex = classIndex.withEntry(generatedInternal, generatedBytes);
                } catch (IOException e) {
                    throw new MojoExecutionException(
                            "Failed to read generated handler class", e);
                }
                handlersWithGenerated.add(new AnnotatedHandler(
                        handler.name(), generatedInternal, handler.adapterInternalName()));
            } else {
                getLog().warn("No handler generator for adapter '"
                              + handler.adapterInternalName() + "' — skipping '"
                              + handler.name() + "'.");
            }
        }

        if (handlersWithGenerated.isEmpty()) {
            getLog().warn("No handlers could be generated — nothing to bundle.");
            return;
        }

        ClassDependencyAnalyzer analyzer = new ClassDependencyAnalyzer(classIndex);

        // Compute reachable sets for all functions first so we can open each dep JAR
        // exactly once (O(M)) when reading dep entries, rather than once per function (O(N×M)).
        List<Set<String>> reachableSets = new ArrayList<>(handlersWithGenerated.size());
        Set<String> unionReachable = new HashSet<>();
        for (AnnotatedHandler handler : handlersWithGenerated) {
            Set<String> reachable = analyzer.analyze(handler.internalName());
            reachableSets.add(reachable);
            unionReachable.addAll(reachable);
        }

        Map<String, byte[]> depJarBytes;
        try {
            depJarBytes = JarBundler.readDepJarEntries(unionReachable, depJars);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read dependency JAR entries", e);
        }

        for (int i = 0; i < handlersWithGenerated.size(); i++) {
            AnnotatedHandler handler = handlersWithGenerated.get(i);
            Set<String> reachable = reachableSets.get(i);
            getLog().info("Bundling function: " + handler.name()
                          + " (handler: " + handler.internalName().replace('/', '.') + ")");
            getLog().info("  Reachable classes: " + reachable.size());
            try {
                Path output = targetDir.resolve(handler.name() + "-shaded.jar");
                JarBundler.bundle(reachable, classIndex, classesDir, depJarBytes, output, null);
                getLog().info("  Written: " + output);
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to bundle function '" + handler.name() + "'", e);
            }
        }
    }

    /**
     * Scans all project classes via the {@link ClassIndex} (no extra disk I/O) and
     * returns entries whose {@code RuntimeInvisibleAnnotations} contain {@code @FaasFunction}.
     *
     * <p>{@code @FaasFunction} uses {@link java.lang.annotation.RetentionPolicy#CLASS},
     * so it appears in {@code RuntimeInvisibleAnnotations} (not
     * {@code RuntimeVisibleAnnotations}).
     */
    private List<AnnotatedHandler> scanHandlers(ClassIndex classIndex, Path classesDir)
            throws MojoExecutionException {
        List<AnnotatedHandler> result = new ArrayList<>();
        if (!Files.isDirectory(classesDir)) return result;

        try (var walk = Files.walk(classesDir)) {
            walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String internalName = ClassIndex.toInternalName(classesDir, p);
                byte[] bytes = classIndex.get(internalName);
                if (bytes == null) return;
                try {
                    ClassModel model = CLASS_FILE.parse(bytes);
                    model.findAttribute(java.lang.classfile.Attributes.runtimeInvisibleAnnotations())
                         .ifPresent(attr -> {
                             for (var ann : attr.annotations()) {
                                 if (!FAAS_FUNCTION_DESC.equals(ann.className().stringValue())) {
                                     continue;
                                 }
                                 String name = null;
                                 String adapterDesc = null;
                                 for (var element : ann.elements()) {
                                     String elemName = element.name().stringValue();
                                     if ("name".equals(elemName)
                                             && element.value() instanceof AnnotationValue.OfString sv) {
                                         name = sv.stringValue();
                                     } else if ("adapter".equals(elemName)
                                             && element.value() instanceof AnnotationValue.OfClass cv) {
                                         // cv.classSymbol().descriptorString() returns "Lsome/Class;"
                                         String desc = cv.classSymbol().descriptorString();
                                         // Strip "L" prefix and ";" suffix to get internal name
                                         if (desc.startsWith("L") && desc.endsWith(";")) {
                                             adapterDesc = desc.substring(1, desc.length() - 1);
                                         }
                                     }
                                 }
                                 if (name == null) {
                                     getLog().warn("@FaasFunction on "
                                                   + internalName.replace('/', '.')
                                                   + " has no 'name' element — skipping.");
                                     return;
                                 }
                                 if (!FUNCTION_NAME_PATTERN.matcher(name).matches()) {
                                     getLog().warn("@FaasFunction name '" + name + "' on "
                                                   + internalName.replace('/', '.')
                                                   + " contains invalid characters or exceeds 64 chars."
                                                   + " Lambda function names must match [a-zA-Z0-9_-]{1,64}."
                                                   + " Skipping.");
                                     return;
                                 }
                                 if (adapterDesc == null) {
                                     getLog().warn("@FaasFunction on "
                                                   + internalName.replace('/', '.')
                                                   + " has no 'adapter' element — skipping.");
                                     return;
                                 }
                                 result.add(new AnnotatedHandler(name, internalName, adapterDesc));
                             }
                         });
                } catch (IllegalArgumentException e) {
                    getLog().debug("Could not parse class file " + p
                                   + " (possibly a non-standard class): " + e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to scan classes in " + classesDir, e);
        }
        return result;
    }

    private List<Path> resolvedDependencyJars() {
        List<Path> jars = new ArrayList<>();
        for (Artifact a : project.getArtifacts()) {
            if (Artifact.SCOPE_TEST.equals(a.getScope())) continue;
            if (Artifact.SCOPE_PROVIDED.equals(a.getScope())) continue;
            if (!"jar".equals(a.getType())) continue;
            if (a.getFile() != null) jars.add(a.getFile().toPath());
        }
        return jars;
    }

    private record AnnotatedHandler(String name, String internalName, String adapterInternalName) {}
}
