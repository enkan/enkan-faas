package enkan.faas.maven;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import static java.lang.classfile.ClassFile.*;

/**
 * Generates vendor-specific handler class bytecode from a {@code @FaasFunction}-annotated
 * {@link enkan.config.ApplicationFactory}.
 *
 * <p>The generated class is written to {@code target/classes/} so it is picked up
 * by the BFS dependency closure and included in the shaded JAR.
 *
 * <p>Currently supports AWS Lambda ({@code ApiGatewayV2Adapter}).
 * The adapter class name drives which handler interface is implemented and which
 * component class is instantiated.
 */
public class HandlerGenerator {

    // Known adapter → Component mapping
    private static final String AWS_ADAPTER_INTERNAL   = "enkan/faas/aws/ApiGatewayV2Adapter";
    private static final String AWS_COMPONENT_INTERNAL = "enkan/faas/aws/AwsLambdaComponent";
    private static final String AWS_EVENT_INTERNAL     = "com/amazonaws/services/lambda/runtime/events/APIGatewayV2HTTPEvent";
    private static final String AWS_RESPONSE_INTERNAL  = "com/amazonaws/services/lambda/runtime/events/APIGatewayV2HTTPResponse";
    private static final String AWS_HANDLER_INTERNAL   = "com/amazonaws/services/lambda/runtime/RequestHandler";
    private static final String AWS_CONTEXT_INTERNAL   = "com/amazonaws/services/lambda/runtime/Context";

    private static final String APP_COMPONENT_INTERNAL = "enkan/component/ApplicationComponent";
    private static final String ENKAN_SYSTEM_INTERNAL  = "enkan/system/EnkanSystem";

    /**
     * Generates a handler class for the given {@code @FaasFunction}-annotated factory.
     *
     * @param name              the function name (e.g. "todo-read")
     * @param factoryInternal   internal class name of the ApplicationFactory
     * @param adapterInternal   internal class name of the FaasAdapter
     * @param classesDir        {@code target/classes/} directory where the class is written
     * @return the internal name of the generated class, or {@code null} if not supported
     */
    public static String generate(String name, String factoryInternal,
                                  String adapterInternal, Path classesDir) throws IOException {
        if (!AWS_ADAPTER_INTERNAL.equals(adapterInternal)) {
            // Only AWS APIGatewayV2 is auto-generated for now.
            // GCP and Azure have platform-specific constraints requiring manual handlers.
            return null;
        }
        return generateAwsHandler(name, factoryInternal, classesDir);
    }

    private static String generateAwsHandler(String name, String factoryInternal, Path classesDir)
            throws IOException {

        // Generated class lives in the same package as the ApplicationFactory,
        // under a "generated" sub-package.
        int lastSlash = factoryInternal.lastIndexOf('/');
        String pkg = lastSlash >= 0 ? factoryInternal.substring(0, lastSlash) : "";
        String generatedInternal = (pkg.isEmpty() ? "" : pkg + "/") + "generated/"
                + capitalize(name) + "Handler";

        // e.g. "enkan/example/faas/read/generated/TodoReadHandler"

        ClassDesc generatedDesc         = ClassDesc.ofInternalName(generatedInternal);
        ClassDesc adapterDesc           = ClassDesc.ofInternalName(AWS_ADAPTER_INTERNAL);
        ClassDesc componentDesc         = ClassDesc.ofInternalName(AWS_COMPONENT_INTERNAL);
        ClassDesc appComponentDesc      = ClassDesc.ofInternalName(APP_COMPONENT_INTERNAL);
        ClassDesc enkanSystemDesc       = ClassDesc.ofInternalName(ENKAN_SYSTEM_INTERNAL);
        ClassDesc componentRelDesc      = ClassDesc.ofInternalName("enkan/component/ComponentRelationship");
        ClassDesc factoryDesc           = ClassDesc.ofInternalName(factoryInternal);
        ClassDesc eventDesc        = ClassDesc.ofInternalName(AWS_EVENT_INTERNAL);
        ClassDesc responseDesc     = ClassDesc.ofInternalName(AWS_RESPONSE_INTERNAL);
        ClassDesc handlerIfDesc    = ClassDesc.ofInternalName(AWS_HANDLER_INTERNAL);
        ClassDesc contextDesc      = ClassDesc.ofInternalName(AWS_CONTEXT_INTERNAL);
        ClassDesc objectDesc       = ConstantDescs.CD_Object;

        // handleRequest(APIGatewayV2HTTPEvent, Context) : APIGatewayV2HTTPResponse
        MethodTypeDesc handleRequestDesc = MethodTypeDesc.of(responseDesc, eventDesc, contextDesc);
        // handleRequest(Object, Object) : Object  — the erased bridge
        MethodTypeDesc handleRequestErasedDesc = MethodTypeDesc.of(objectDesc, objectDesc, objectDesc);

        byte[] bytes = ClassFile.of().build(generatedDesc, cb -> {
            cb.withVersion(JAVA_25_VERSION, 0);
            cb.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER);
            cb.withSuperclass(ConstantDescs.CD_Object);
            cb.withInterfaceSymbols(handlerIfDesc);

            // static final ApiGatewayV2Adapter ADAPTER;
            cb.withField("ADAPTER", adapterDesc, fb ->
                    fb.withFlags(ACC_PRIVATE | ACC_STATIC | ACC_FINAL));

            // static final EnkanSystem SYSTEM;
            cb.withField("SYSTEM", enkanSystemDesc, fb ->
                    fb.withFlags(ACC_PRIVATE | ACC_STATIC | ACC_FINAL));

            // static initializer
            cb.withMethod(ConstantDescs.CLASS_INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ACC_STATIC, mb -> mb.withCode(code -> {
                        // ADAPTER = new ApiGatewayV2Adapter();
                        code.new_(adapterDesc)
                            .dup()
                            .invokespecial(adapterDesc, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void))
                            .putstatic(generatedDesc, "ADAPTER", adapterDesc);

                        // SYSTEM = EnkanSystem.of(
                        //     "app",    new ApplicationComponent<>(FactoryClass.class.getName()),
                        //     "lambda", new AwsLambdaComponent(ADAPTER));
                        // SYSTEM.start();
                        // SYSTEM.registerCrac();

                        // Build the varargs Object[] for EnkanSystem.of(Object...)
                        // Slot 0: array ref
                        code.bipush(4) // 4 elements: "app", appComp, "lambda", lambdaComp
                            .anewarray(objectDesc);

                        // [0] = "app"
                        code.dup().bipush(0).ldc("app").aastore();

                        // [1] = new ApplicationComponent<>(FactoryClass.class.getName())
                        code.dup().bipush(1);
                        code.new_(appComponentDesc)
                            .dup();
                        // FactoryClass.class.getName()
                        code.ldc(factoryDesc)
                            .invokevirtual(ClassDesc.of("java.lang.Class"), "getName",
                                    MethodTypeDesc.of(ClassDesc.of("java.lang.String")));
                        code.invokespecial(appComponentDesc, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ClassDesc.of("java.lang.String")));
                        code.aastore();

                        // [2] = "lambda"
                        code.dup().bipush(2).ldc("lambda").aastore();

                        // [3] = new AwsLambdaComponent(ADAPTER)
                        code.dup().bipush(3);
                        code.new_(componentDesc)
                            .dup()
                            .getstatic(generatedDesc, "ADAPTER", adapterDesc)
                            .invokespecial(componentDesc, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void,
                                            ClassDesc.ofInternalName("enkan/faas/FaasAdapter")));
                        code.aastore();

                        // SYSTEM = EnkanSystem.of(array).relationships(...)
                        // First call EnkanSystem.of(array)
                        code.invokestatic(enkanSystemDesc, "of",
                                MethodTypeDesc.of(enkanSystemDesc,
                                        ClassDesc.of("java.lang.Object").arrayType()));

                        // Then .relationships(ComponentRelationship.component("lambda").using("app"))
                        // ComponentRelationship[] varargs — build a single-element array
                        code.bipush(1)
                            .anewarray(componentRelDesc);
                        code.dup().bipush(0);
                        // ComponentRelationship.component("lambda")
                        code.ldc("lambda")
                            .invokestatic(componentRelDesc, "component",
                                    MethodTypeDesc.of(
                                            ClassDesc.ofInternalName("enkan/component/ComponentRelationship$ComponentRelationshipBuilder"),
                                            ClassDesc.of("java.lang.String")));
                        // .using("app")
                        code.bipush(1)
                            .anewarray(ClassDesc.of("java.lang.String"))
                            .dup().bipush(0).ldc("app").aastore()
                            .invokevirtual(
                                    ClassDesc.ofInternalName("enkan/component/ComponentRelationship$ComponentRelationshipBuilder"),
                                    "using",
                                    MethodTypeDesc.of(componentRelDesc,
                                            ClassDesc.of("java.lang.String").arrayType()));
                        code.aastore();
                        // .relationships(array)
                        code.invokevirtual(enkanSystemDesc, "relationships",
                                MethodTypeDesc.of(enkanSystemDesc, componentRelDesc.arrayType()))
                            .putstatic(generatedDesc, "SYSTEM", enkanSystemDesc);

                        // SYSTEM.start()
                        code.getstatic(generatedDesc, "SYSTEM", enkanSystemDesc)
                            .invokevirtual(enkanSystemDesc, "start",
                                    MethodTypeDesc.of(ConstantDescs.CD_void));

                        // SYSTEM.registerCrac()
                        code.getstatic(generatedDesc, "SYSTEM", enkanSystemDesc)
                            .invokevirtual(enkanSystemDesc, "registerCrac",
                                    MethodTypeDesc.of(ConstantDescs.CD_void));

                        code.return_();
                    }));

            // default constructor
            cb.withMethod(ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void),
                    ACC_PUBLIC, mb -> mb.withCode(code -> {
                        code.aload(0)
                            .invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                                    MethodTypeDesc.of(ConstantDescs.CD_void))
                            .return_();
                    }));

            // public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context ctx)
            cb.withMethod("handleRequest", handleRequestDesc,
                    ACC_PUBLIC, mb -> mb.withCode(code -> {
                        code.getstatic(generatedDesc, "ADAPTER", adapterDesc)
                            .aload(1)  // event
                            .aload(2)  // ctx
                            .invokevirtual(adapterDesc, "handleRequest", handleRequestDesc)
                            .areturn();
                    }));

            // Bridge method: public Object handleRequest(Object, Object) — required by RequestHandler<E,R>
            cb.withMethod("handleRequest", handleRequestErasedDesc,
                    ACC_PUBLIC | ACC_BRIDGE | ACC_SYNTHETIC, mb -> mb.withCode(code -> {
                        code.aload(0)
                            .aload(1).checkcast(eventDesc)
                            .aload(2).checkcast(contextDesc)
                            .invokevirtual(generatedDesc, "handleRequest", handleRequestDesc)
                            .areturn();
                    }));
        });

        // Write to target/classes/
        Path outPath = classesDir.resolve(generatedInternal + ".class");
        Files.createDirectories(outPath.getParent());
        Files.write(outPath, bytes);

        return generatedInternal;
    }

    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        // Convert kebab-case "todo-read" → "TodoRead"
        StringBuilder sb = new StringBuilder();
        boolean next = true;
        for (char c : name.toCharArray()) {
            if (c == '-') {
                next = true;
            } else if (next) {
                sb.append(Character.toUpperCase(c));
                next = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
