package org.modelmap.gen;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.modelmap.core.FieldId;
import org.modelmap.gen.processor.PropertyParsingException;

import java.beans.IntrospectionException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import static java.nio.file.Files.createDirectories;
import static java.time.LocalDateTime.now;
import static java.time.format.DateTimeFormatter.ofLocalizedDateTime;
import static java.time.format.FormatStyle.SHORT;
import static java.util.Arrays.asList;
import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;
import static org.modelmap.gen.FieldInfoGen.literals;
import static org.modelmap.gen.ModelWrapperGen.*;
import static org.modelmap.gen.processor.MacroProcessor.replaceProperties;

@Mojo(name = "generate", defaultPhase = GENERATE_SOURCES, threadSafe = true)
public final class ModelMapGenMojo extends AbstractMojo {

    @Parameter(required = true, property = "project.build.sourceDirectory")
    private File outputDirectory;

    @Parameter(required = true, property = "project.build.outputDirectory")
    private File buildDirectory;

    @Parameter(required = true, property = "project.build.outputDirectory")
    private File buildResourceDirectory;

    @Parameter(required = true)
    private List<String> sourceClasses;

    @Parameter(required = true)
    private List<String> fieldClasses;

    @Parameter(required = true, readonly = true, property = "project")
    private MavenProject project;

    @Parameter(required = true)
    private String packageFilter;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (sourceClasses == null)
            getLog().warn("no project classes");
        if (sourceClasses.isEmpty())
            getLog().warn("project is empty");
        if (fieldClasses == null)
            getLog().warn("no tunnel classes");
        if (fieldClasses.isEmpty())
            getLog().warn("tunnel is empty");
        if (fieldClasses.size() != sourceClasses.size())
            getLog().warn("tunnel and projet have different size");
        if (outputDirectory.exists() && !outputDirectory.isDirectory())
            throw new MojoFailureException(outputDirectory + " is not directory");

        // add source directory to current project
        try {
            createDirectories(outputDirectory.toPath());
            project.addCompileSourceRoot(outputDirectory.getPath());
        } catch (IOException e) {
            throw new MojoExecutionException("unable to create source folder", e);
        }


        final List<URL> urls = new ArrayList<>();
        try {
            for (String element : project.getCompileClasspathElements()) {
                urls.add(new File(element).toURI().toURL());
            }
            for (Artifact artifact : project.getDependencyArtifacts()) {
                urls.add(artifact.getFile().toURI().toURL());
            }
            urls.add(new File(buildDirectory + "/classes").toURI().toURL());
        } catch (DependencyResolutionRequiredException | MalformedURLException e) {
            throw new MojoFailureException(e.getMessage());
        }

        final URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]),
                Thread.currentThread().getContextClassLoader());
        try {
            for (int i = 0; i < sourceClasses.size(); i++) {
                @SuppressWarnings("unchecked")
                final Class<? extends FieldId> fieldClazz = (Class<? extends FieldId>)
                        Class.forName(fieldClasses.get(i), true, classLoader);
                final List<FieldId> fieldsOrder = asList(fieldClazz.getEnumConstants());
                final Class<?> modelClazz = Class.forName(sourceClasses.get(i), true, classLoader);
                final List<VisitorPath> collected = process(modelClazz, packageFilter);
                generateCsv(collected, modelClazz);
                generateWrapper(collected, modelClazz, fieldClazz);
                generateFieldInfo(collected, fieldsOrder, fieldClazz);
            }
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private List<VisitorPath> process(Class<?> projetClass, String packageFilter)
            throws IllegalArgumentException, SecurityException, IllegalAccessException,
            InvocationTargetException, IntrospectionException {
        final List<VisitorPath> collected = new ArrayList<>();
        new ModelVisitor(getLog()).visitModel(projetClass, new Visitor(projetClass, collected), packageFilter);
        return collected;
    }

    private void generateCsv(List<VisitorPath> collected, Class<?> clazz) throws IOException, MojoExecutionException {
        final File targetFile = new File(buildResourceDirectory, clazz.getSimpleName() + ".csv");
        targetFile.getParentFile().mkdirs();
        try (FileWriter writter = new FileWriter(targetFile)) {
            for (VisitorPath path : collected) {
                writter.write(path.toCsv());
            }
            for (FieldId field : fieldsWithoutPath(collected)) {
                writter.write("NO_PATH;");
                writter.write(field.toString());
                writter.write('\n');
            }
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        getLog().info("written : " + targetFile);
    }

    static String template(String template) throws IOException {
        URL resource = Resources.getResource(ModelMapGenMojo.class, template);
        return Resources.toString(resource, Charsets.UTF_8);
    }

    private void generateFieldInfo(List<VisitorPath> collected, List<FieldId> fieldsOrder, Class<?> clazz)
            throws IOException, PropertyParsingException {
        final String targetClassName = clazz.getSimpleName() + "Info";
        final String targetPackage = clazz.getPackage().getName();
        final File targetFile = new File(outputDirectory + "/" + targetPackage.replace('.', '/'), targetClassName
                + ".java");
        final String classTemplate = template("FieldInfoEnum.template");
        createDirectories(targetFile.getParentFile().toPath());
        final Map<String, String> conf = new HashMap<>();
        conf.put("package.name", targetPackage);
        conf.put("process.class", clazz.getName());
        conf.put("process.date", ofLocalizedDateTime(SHORT).format(now()));
        conf.put("target.class.name", targetClassName);
        conf.put("literals", literals(fieldsOrder, collected));
        conf.put("source.generator.name", getClass().getName());
        final String content = replaceProperties(classTemplate, conf, MISSING_VALUE);
        Files.write(content.getBytes(), targetFile);
        getLog().info("written : " + targetFile);
    }

    private void generateWrapper(List<VisitorPath> collected, Class<?> modelClass, Class<?> fieldClass)
            throws IOException, PropertyParsingException {
        final String targetClassName = modelClass.getSimpleName() + "Wrapper";
        final String targetPackage = modelClass.getPackage().getName();
        final File targetFile = new File(outputDirectory + "/" + targetPackage.replace('.', '/'),
                targetClassName + ".java");
        final String classTemplate = template("WrapperClass.template");
        createDirectories(targetFile.getParentFile().toPath());
        final Map<String, String> conf = new HashMap<>();
        conf.put("package.name", targetPackage);
        conf.put("process.class", modelClass.getName());
        conf.put("process.date", ofLocalizedDateTime(SHORT).format(now()));
        conf.put("target.project.class.name", modelClass.getSimpleName());
        conf.put("target.project.class.full.name", modelClass.getName());
        conf.put("target.field.info.package.name", fieldClass.getPackage().getName());
        conf.put("target.field.info.class.name", fieldClass.getSimpleName() + "Info");
        conf.put("target.class.name", targetClassName);
        conf.put("map.getter", mapGetter(collected));
        conf.put("map.getter.if", mapFieldTypeIfStatement("MapGetIfStatement.template", collected));
        conf.put("map.setter", mapSetter(collected));
        conf.put("map.setter.if", mapFieldTypeIfStatement("MapSetIfStatement.template", collected));
        conf.put("source.generator.name", getClass().getName());
        final String content = replaceProperties(classTemplate, conf, MISSING_VALUE);
        Files.write(content.getBytes(), targetFile);
        getLog().info("written : " + targetFile);
    }

    private static Collection<FieldId> fieldsWithoutPath(List<VisitorPath> collected) throws IllegalArgumentException,
            SecurityException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        final Set<FieldId> fields = new HashSet<>();
        for (VisitorPath path : collected) {
            final FieldId[] values = (FieldId[]) path.getFieldId().getClass().getMethod("values").invoke(null);
            fields.addAll(asList(values));
        }
        for (VisitorPath path : collected) {
            fields.remove(path.getFieldId());
        }
        return fields;
    }
}