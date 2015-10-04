package com.digitalsanctum.lambda.generator;

import com.digitalsanctum.lambda.Executor;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeSpec;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import javax.lang.model.element.Modifier;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

public class Generator {

    public static void main(String[] args) throws Exception {

        if (args == null || args.length < 3) {
            System.err.println("Usage: java -jar lambda-generator-1.0-SNAPSHOT.jar <lambda_jar_path> <handler> <resource_path> <resource_http_method:POST> <timeout:5>");
            return;
        }

        Path lambdaJarPath = Paths.get(args[0]);
        String handler = args[1];
        String resourcePath = args[2];
        Class httpMethod = getHttpMethodFromString(args[3]);
        int timeout = Integer.parseInt(args[4]);

        Map<String, Class> types = Executor.getRequestHandlerTypes(handler);
        Class requestType = types.get("request");
        Class responseType = types.get("response");

        String gatewayType = "jersey";
        String apiImpl = "lambda-api-gateway-" + gatewayType;

        String baseDir = "/data";
        Path coreJarPath = Paths.get(baseDir, "lambda-core-1.0-SNAPSHOT.jar");
        Path propsPath = Paths.get(baseDir, "/template/src/main/resources/application.properties");
        Path endpointSrcPath = Paths.get(baseDir, "/template/src/main/java");
        Path pomFilePath = Paths.get(baseDir, "/template/pom.xml");

        if ("jersey".equals(gatewayType)) {
            new Generator()
                    .installLambdaCore(pomFilePath, coreJarPath)
                    .installLambdaJar(pomFilePath, lambdaJarPath)
                    .generateJerseyResource(endpointSrcPath, httpMethod, resourcePath, requestType)
                    .compileAndPackageGateway(pomFilePath)
                    .exportGatewayJar(
                            Paths.get(baseDir, "/template/target/" + apiImpl + "-1.0-SNAPSHOT.jar"),
                            Paths.get(baseDir, "/export/api.jar"));
        } else if ("spring".equals(gatewayType)) {
            new Generator()
                    .installLambdaCore(pomFilePath, coreJarPath)
                    .installLambdaJar(pomFilePath, lambdaJarPath)
                    .generateProperties(propsPath, handler, timeout)
                    .generateSpringResource(endpointSrcPath, httpMethod, resourcePath, requestType, responseType)
                    .compileAndPackageGateway(pomFilePath)
                    .exportGatewayJar(
                            Paths.get(baseDir, "/template/target/" + apiImpl + "-1.0-SNAPSHOT.jar"),
                            Paths.get(baseDir, "/export/api.jar"));
        } else {
            throw new IllegalArgumentException("Unsupported API Gateway type: " + gatewayType);
        }


    }

    public void exportGatewayJar(Path src, Path target) {
        System.out.println("exporting gateway jar");
        try {
            if (target.toFile().exists()) {
                target.toFile().delete();
            }
            Files.copy(src, target);
        } catch (IOException e) {
            System.err.println("copy failed");
            e.printStackTrace();
        }
        System.out.println("export complete");
    }

    public Generator generateJerseyResource(Path endpointSrcPath,
                                            Class httpMethod,
                                            String resourcePath,
                                            Class requestType) throws IOException {

        FieldSpec executorField = FieldSpec.builder(Executor.class, "executor")
                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                .build();

        MethodSpec ctor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ParameterSpec.builder(Executor.class, "executor").build())
                .addStatement("this.executor = executor")
                .build();

        ParameterSpec paramSpec = getParameterSpec(httpMethod, requestType);

        MethodSpec messageMethod = MethodSpec.methodBuilder("message")
                .addAnnotation(httpMethod)
                .addAnnotation(AnnotationSpec.builder(Consumes.class).addMember("value", "$S", MediaType.APPLICATION_JSON).build())
                .addAnnotation(AnnotationSpec.builder(Produces.class).addMember("value", "$S", MediaType.APPLICATION_JSON).build())
                .addModifiers(Modifier.PUBLIC)
                .addParameter(paramSpec)
                .addException(Exception.class)
                .returns(Response.class)
                .addStatement("Object obj = executor.execute(input).getResult()")
                .addStatement("return $T.status(201).entity(obj).build()", Response.class)
                .build();

        TypeSpec endpoint = TypeSpec.classBuilder("EntryPoint")
                .addAnnotation(AnnotationSpec.builder(javax.ws.rs.Path.class)
                        .addMember("value", "$S", resourcePath)
                        .build())
                .addModifiers(Modifier.PUBLIC)
                .addField(executorField)
                .addMethod(ctor)
                .addMethod(messageMethod)
                .build();

        JavaFile javaFile = JavaFile.builder("com.digitalsanctum.lambda.api.gateway", endpoint)
                .build();

        javaFile.writeTo(System.out);
        javaFile.writeTo(endpointSrcPath);

        return this;
    }

    private static Class getHttpMethodFromString(String in) {
        if ("POST".equalsIgnoreCase(in)) {
            return POST.class;
        } else if ("GET".equalsIgnoreCase(in)) {
            return GET.class;
        } else {
            throw new IllegalArgumentException("Unsupported HTTP method: " + in);
        }
    }

    private Generator invokeMaven(Path pomFile, String... goals) {
        if (pomFile == null) {
            throw new IllegalArgumentException("No path specified for pom.xml");
        }

        System.out.println("invokeMaven pom: " + pomFile + ", goals: " + Arrays.toString(goals));

        InvocationRequest invocationRequest = new DefaultInvocationRequest()
                .setPomFile(pomFile.toFile())
                .setGoals(Arrays.asList(goals));

        Invoker invoker = new DefaultInvoker();
        InvocationResult result = null;
        try {
            result = invoker.execute(invocationRequest);
        } catch (MavenInvocationException e) {
            System.err.println("Error executing Maven goals. " + e.getMessage());
            System.exit(1);
        }
        if (result != null && result.getExitCode() != 0) {
            System.err.println("Maven goal failed with non-zero exit code; pom: "
                    + pomFile.toString() + ", goals: " + Arrays.asList(goals));
            System.exit(1);
        }
        return this;
    }

    private Generator installLambdaCore(Path pomFile, Path coreJar) {
        return invokeMaven(pomFile, "install:install-file -Dfile=" + coreJar.toString() +
                " -DgroupId=com.digitalsanctum.lambda -DartifactId=lambda-core -Dversion=1.0-SNAPSHOT -Dpackaging=jar");
    }

    public Generator installLambdaJar(Path pomFile, Path lambdaJar) {
        return invokeMaven(pomFile, "install:install-file -Dfile=" + lambdaJar.toString() +
                " -DgroupId=com.foo -DartifactId=lambda -Dversion=1.0 -Dpackaging=jar");
    }

    public Generator compileAndPackageGateway(Path pomFile) {
        return invokeMaven(pomFile, "clean", "package");
    }

    private Generator generateProperties(Path propsFilePath, String handler, int timeout) throws IOException {

        Properties props = new Properties();
        props.setProperty("lambda.handler", handler);
        props.setProperty("lambda.timeout", Integer.toString(timeout));

        File propsDir = propsFilePath.getParent().toFile();
        if (!propsDir.exists()) {
            propsDir.mkdirs();
        }
        File f = propsFilePath.toFile();
        System.out.println("saving properties to: " + f.getAbsolutePath());
        props.store(new FileOutputStream(f), "Generated by " + getClass().getName());

        return this;
    }

    private Generator generateSpringResource(Path endpointSrcPath,
                                             Class httpMethod,
                                             String resourcePath,
                                             Class requestType,
                                             Class responseType) throws IOException {
        FieldSpec executorField = FieldSpec.builder(Executor.class, "executor")
                .addModifiers(Modifier.PRIVATE)
                .addAnnotation(Autowired.class)
                .build();

        ParameterSpec paramSpec = getParameterSpec(httpMethod, requestType);

        MethodSpec messageMethod = MethodSpec.methodBuilder("message")
                .addAnnotation(httpMethod)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(paramSpec)
                .addException(Exception.class)
                .returns(responseType)
                .addStatement("return ($T) executor.execute(input).getResult()", responseType)
                .build();

        TypeSpec endpoint = TypeSpec.classBuilder("Endpoint")
                .addAnnotation(Component.class)
                .addAnnotation(AnnotationSpec.builder(javax.ws.rs.Path.class)
                        .addMember("value", "$S", resourcePath)
                        .build())
                .addModifiers(Modifier.PUBLIC)
                .addField(executorField)
                .addMethod(messageMethod)
                .build();

        JavaFile javaFile = JavaFile.builder("com.digitalsanctum.lambda.api.gateway", endpoint)
                .build();

        javaFile.writeTo(System.out);
        javaFile.writeTo(endpointSrcPath);

        return this;
    }

    private ParameterSpec getParameterSpec(Class httpMethod, Class requestType) {
        ParameterSpec paramSpec;
        if (httpMethod.equals(POST.class)) {
            paramSpec = ParameterSpec.builder(requestType, "input").build();

        } else if (httpMethod.equals(GET.class)) {
            paramSpec = ParameterSpec.builder(requestType, "input")
                    .addAnnotation(AnnotationSpec.builder(QueryParam.class)
                            .addMember("value", "$S", "input")
                            .build())
                    .build();
        } else {
            throw new IllegalArgumentException("unsupported http method " + httpMethod.getName());
        }
        return paramSpec;
    }
}
