/*
 * Copyright 2022 the GradleX team.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradlex.javamodule.moduleinfo;

import org.gradle.api.NonNullApi;
import org.gradle.api.artifacts.CacheableRule;
import org.gradle.api.artifacts.transform.InputArtifact;
import org.gradle.api.artifacts.transform.TransformAction;
import org.gradle.api.artifacts.transform.TransformOutputs;
import org.gradle.api.artifacts.transform.TransformParameters;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.objectweb.asm.*;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

import static org.gradlex.javamodule.moduleinfo.FilePathToModuleCoordinates.gaCoordinatesFromFilePathMatch;
import static org.gradlex.javamodule.moduleinfo.FilePathToModuleCoordinates.versionFromFilePath;

/**
 * An artifact transform that applies additional information to Jars without module information.
 * The transformation fails the build if a Jar does not contain information and no extra information
 * was defined for it. This way we make sure that all Jars are turned into modules.
 */
@CacheableRule
@NonNullApi
public abstract class ExtraJavaModuleInfoTransform implements TransformAction<ExtraJavaModuleInfoTransform.Parameter> {

    private static final Pattern MODULE_INFO_CLASS_MRJAR_PATH = Pattern.compile("META-INF/versions/\\d+/module-info.class");
    private static final Pattern JAR_SIGNATURE_PATH = Pattern.compile("^META-INF/[^/]+\\.(SF|RSA|DSA|sf|rsa|dsa)$");
    private static final String SERVICES_PREFIX = "META-INF/services/";

    public interface Parameter extends TransformParameters {
        @Input
        MapProperty<String, ModuleSpec> getModuleSpecs();

        @Input
        Property<Boolean> getFailOnMissingModuleInfo();

        @Input
        ListProperty<String> getMergeJarIds();

        @InputFiles
        ListProperty<RegularFile> getMergeJars();

        @Input
        MapProperty<String, Set<String>> getCompileClasspathDependencies();

        @Input
        MapProperty<String, Set<String>> getRuntimeClasspathDependencies();
    }

    @InputArtifact
    protected abstract Provider<FileSystemLocation> getInputArtifact();

    @Override
    public void transform(TransformOutputs outputs) {
        Map<String, ModuleSpec> moduleSpecs = getParameters().getModuleSpecs().get();
        File originalJar = getInputArtifact().get().getAsFile();

        ModuleSpec moduleSpec = findModuleSpec(originalJar);

        if (moduleSpec instanceof ModuleInfo moduleInfo) {
            addModuleDescriptor(originalJar, getModuleJar(outputs, originalJar), moduleInfo);
        } else if (moduleSpec instanceof AutomaticModuleName automaticModuleName) {
            addAutomaticModuleName(originalJar, getModuleJar(outputs, originalJar), automaticModuleName);
        } else if (isModule(originalJar)) {
            outputs.file(originalJar);
        } else if (isAutoModule(originalJar)) {
            outputs.file(originalJar);
        } else if (!willBeMerged(originalJar, moduleSpecs.values())) { // No output if this Jar will be merged
            if (Boolean.TRUE.equals(getParameters().getFailOnMissingModuleInfo().get())) {
                System.err.println("Not a module and no mapping defined: " + originalJar.getName());
            } else {
                outputs.file(originalJar);
            }
        }
    }

    @Nullable
    private ModuleSpec findModuleSpec(File originalJar) {
        Map<String, ModuleSpec> moduleSpecs = getParameters().getModuleSpecs().get();

        Optional<String> gaCoordinates = moduleSpecs.keySet().stream().filter(ga -> gaCoordinatesFromFilePathMatch(originalJar.toPath(), ga)).findFirst();
        if (gaCoordinates.isPresent()) {
            return moduleSpecs.get(gaCoordinates.get());
        }

        String originalJarName = originalJar.getName();
        if (moduleSpecs.containsKey(originalJarName)) {
            return moduleSpecs.get(originalJarName);
        }

        return null;
    }

    private boolean willBeMerged(File originalJar, Collection<ModuleSpec> modules) {
        return modules.stream().anyMatch(module -> module.getMergedJars().stream().anyMatch(toMerge ->
                gaCoordinatesFromFilePathMatch(originalJar.toPath(), toMerge) || toMerge.equals(originalJar.getName())));
    }

    private boolean isModule(File jar) {
        if (!jar.isFile()) {
            // If the jar does not exist, we assume that the file, which is produced later is a local artifact and a module.
            // For local files this behavior is ok, because this transform is targeting published artifacts.
            // See also: https://github.com/jjohannes/extra-java-module-info/issues/15
            try {
                //noinspection ResultOfMethodCallIgnored
                jar.getParentFile().mkdirs();
                //noinspection ResultOfMethodCallIgnored
                jar.createNewFile();
            } catch (IOException e) {
                System.err.println("throw: " + e);
                throw new RuntimeException(e);
            }
            return true;
        }
        try (var inputStream = new JarInputStream(Files.newInputStream(jar.toPath()))) {
            boolean isMultiReleaseJar = containsMultiReleaseJarEntry(inputStream);
            ZipEntry next = inputStream.getNextEntry();
            while (next != null) {
                if ("module-info.class".equals(next.getName())) {
                    return true;
                }
                if (isMultiReleaseJar && MODULE_INFO_CLASS_MRJAR_PATH.matcher(next.getName()).matches()) {
                    return true;
                }
                next = inputStream.getNextEntry();
            }
        } catch (IOException e) {
            System.err.println(e);
        }
        return false;
    }

    private boolean containsMultiReleaseJarEntry(JarInputStream jarStream) {
        Manifest manifest = jarStream.getManifest();
        return manifest != null && Boolean.parseBoolean(manifest.getMainAttributes().getValue("Multi-Release"));
    }

    private boolean isAutoModule(File jar) {
        try (var inputStream = new JarInputStream(Files.newInputStream(jar.toPath()))) {
            Manifest manifest = inputStream.getManifest();
            return manifest != null && manifest.getMainAttributes().getValue("Automatic-Module-Name") != null;
        } catch (IOException | NullPointerException e) {
            return false; // regard as noAutoModule when focus error
        }
    }

    private File getModuleJar(TransformOutputs outputs, File originalJar) {
        return outputs.file(originalJar.getName().substring(0, originalJar.getName().lastIndexOf('.')) + "-module.jar");
    }

    private void addAutomaticModuleName(File originalJar, File moduleJar, AutomaticModuleName automaticModule) {
        try (var inputStream = new JarInputStream(Files.newInputStream(originalJar.toPath()))) {
            Manifest manifest = inputStream.getManifest();
            if (manifest == null) {
                manifest = new Manifest();
                manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
            }
            manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticModule.getModuleName());
            try (var outputStream = new JarOutputStream(Files.newOutputStream(moduleJar.toPath()), manifest)) {
                Map<String, List<String>> providers = new LinkedHashMap<>();
                Set<String> packages = new TreeSet<>();
                copyAndExtractProviders(inputStream, outputStream, !automaticModule.getMergedJars().isEmpty(), providers, packages, null);
                mergeJars(automaticModule, outputStream, providers, packages);
            }
        } catch (IOException e) {
            System.err.println(e);
        }
    }

    private void addModuleDescriptor(File originalJar, File moduleJar, ModuleInfo moduleInfo) {
        try (var inputStream = new JarInputStream(Files.newInputStream(originalJar.toPath()));
             var outputStream = newJarOutputStream(Files.newOutputStream(moduleJar.toPath()), inputStream.getManifest())) {
            Map<String, List<String>> providers = new LinkedHashMap<>();
            Set<String> packages = new TreeSet<>();
            copyAndExtractProviders(inputStream, outputStream, !moduleInfo.getMergedJars().isEmpty(), providers, packages, moduleInfo);
            mergeJars(moduleInfo, outputStream, providers, packages);
            outputStream.putNextEntry(new JarEntry("module-info.class"));
            outputStream.write(addModuleInfo(moduleInfo, providers, versionFromFilePath(originalJar.toPath()),
                    moduleInfo.exportAllPackages ? packages : Collections.emptySet()));
            outputStream.closeEntry();
        } catch (IOException e) {
            System.err.println(e);
        }
    }

    private JarOutputStream newJarOutputStream(OutputStream out, @Nullable Manifest manifest) throws IOException {
        return manifest == null ? new JarOutputStream(out) : new JarOutputStream(out, manifest);
    }

    private void copyAndExtractProviders(JarInputStream inputStream, JarOutputStream outputStream, boolean willMergeJars,
                                         Map<String, List<String>> providers, Set<String> packages, @Nullable ModuleInfo moduleInfo) throws IOException {
        JarEntry jarEntry = inputStream.getNextJarEntry();
        while (jarEntry != null) {
            byte[] content = inputStream.readAllBytes();
            String entryName = jarEntry.getName();
            boolean isServiceProviderFile = entryName.startsWith(SERVICES_PREFIX) && !entryName.equals(SERVICES_PREFIX);
            if (isServiceProviderFile) {
                String key = entryName.substring(SERVICES_PREFIX.length());
                if (!providers.containsKey(key)) {
                    providers.put(key, new ArrayList<>());
                }
                providers.get(key).addAll(extractImplementations(content));
            }

            if (!JAR_SIGNATURE_PATH.matcher(jarEntry.getName()).matches() && !"META-INF/MANIFEST.MF".equals(jarEntry.getName())) {
                if (!willMergeJars || !isServiceProviderFile) { // service provider files will be merged later
                    jarEntry.setCompressedSize(-1);
                    try {
                        manageEntries(content, outputStream, jarEntry, moduleInfo);
                    } catch (ZipException e) {
                        if (!e.getMessage().startsWith("duplicate entry:")) {
                            System.err.println("throw： " + e);
                            throw new RuntimeException(e);
                        }
                    }
                    if (entryName.endsWith(".class")) {
                        int i = entryName.lastIndexOf("/");
                        if (i > 0) {
                            packages.add(entryName.substring(0, i));
                        }
                    }
                }
            }
            jarEntry = inputStream.getNextJarEntry();
        }
    }

    private List<String> extractImplementations(byte[] content) {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.UTF_8))
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .distinct()
                .collect(Collectors.toList());
    }


    private void manageEntries(byte[] content, JarOutputStream outputStream, JarEntry jarEntry,
                               @Nullable ModuleInfo moduleInfo) throws IOException {
        outputStream.putNextEntry(jarEntry);
        if (Objects.nonNull(moduleInfo) && jarEntry.getName().equals("module-info.class")) {
            System.err.println("got it !!!");
            var classReader = new ClassReader(content);
            var classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            var cv = new ClassVisitor(Opcodes.ASM9, classWriter) {
                @Override
                public ModuleVisitor visitModule(String name, int access, String version) {
                    var moduleVisitor = super.visitModule(name, access, version);
                    modifyModuleInfo(moduleVisitor, moduleInfo, Collections.emptyMap(), Collections.emptySet());
                    return moduleVisitor;
                }
            };
            classReader.accept(cv, 0);
            outputStream.write(classWriter.toByteArray());
        } else
            outputStream.write(content);
        outputStream.closeEntry();
    }

    private byte[] addModuleInfo(ModuleInfo moduleInfo, Map<String, List<String>> providers, @Nullable String version, Set<String> autoExportedPackages) {
        var classWriter = new ClassWriter(0);
        classWriter.visit(Opcodes.V11, Opcodes.ACC_MODULE, "module-info", null, null, null);
        var moduleVersion = moduleInfo.getModuleVersion() == null ? version : moduleInfo.getModuleVersion();
        var moduleVisitor = classWriter.visitModule(moduleInfo.getModuleName(),
                // access 0x0000 is blank/default; will use open module for that unset exports
                moduleInfo.exports.isEmpty() && autoExportedPackages.isEmpty() ? Opcodes.ACC_OPEN : 0x0000, moduleVersion);
        modifyModuleInfo(moduleVisitor, moduleInfo, providers, autoExportedPackages);
        moduleVisitor.visitEnd();
        classWriter.visitEnd();
        return classWriter.toByteArray();
    }

    private void modifyModuleInfo(ModuleVisitor moduleVisitor, ModuleInfo moduleInfo, Map<String, List<String>> providers, Set<String> autoExportedPackages) {
        for (String packageName : autoExportedPackages) {
            moduleVisitor.visitExport(packageName, 0);
        }

        for (var export : moduleInfo.exports) {
            moduleVisitor.visitExport(getInternalName(export.k), 0, export.v);
        }

        if (moduleInfo.requireAllDefinedDependencies) {
            Set<String> compileDependencies = getParameters().getCompileClasspathDependencies().get().get(moduleInfo.getIdentifier());
            Set<String> runtimeDependencies = getParameters().getRuntimeClasspathDependencies().get().get(moduleInfo.getIdentifier());

            if (compileDependencies == null || runtimeDependencies == null) {
                throw new RuntimeException("[requires directives from metadata] " +
                        "Cannot find dependencies for '" + moduleInfo.getModuleName() + "'. " +
                        "Are '" + moduleInfo.getIdentifier() + "' the correct component coordinates?");
            }

            Set<String> allDependencies = new TreeSet<>();
            allDependencies.addAll(compileDependencies);
            allDependencies.addAll(runtimeDependencies);
            for (String ga : allDependencies) {
                String moduleName = gaToModuleName(ga);
                if (compileDependencies.contains(ga) && runtimeDependencies.contains(ga)) {
                    moduleVisitor.visitRequire(moduleName, Opcodes.ACC_TRANSITIVE, null);
                } else if (runtimeDependencies.contains(ga)) {
                    moduleVisitor.visitRequire(moduleName, 0, null);
                } else if (compileDependencies.contains(ga)) {
                    moduleVisitor.visitRequire(moduleName, Opcodes.ACC_STATIC_PHASE, null);
                }
            }
        }

        for (var requireName : moduleInfo.requires) {
            moduleVisitor.visitRequire(requireName, 0, null);
        }
        for (var requireName : moduleInfo.requiresTransitive) {
            moduleVisitor.visitRequire(requireName, Opcodes.ACC_TRANSITIVE, null);
        }
        for (var requireName : moduleInfo.requiresStatic) {
            moduleVisitor.visitRequire(requireName, Opcodes.ACC_STATIC_PHASE, null);
        }
        for (var open : moduleInfo.opens) {
            moduleVisitor.visitOpen(getInternalName(open.k), 0, open.v);
        }
        for (var use : moduleInfo.uses) {
            moduleVisitor.visitUse(getInternalName(use));
        }
        for (var provide : moduleInfo.provides) {
            var with = provide.v;
            if (Objects.nonNull(with))
                with = Arrays.stream(with).map(ExtraJavaModuleInfoTransform::getInternalName).toArray(String[]::new);
            moduleVisitor.visitProvide(getInternalName(provide.k), with);
        }
        for (var entry : providers.entrySet()) {
            var serviceName = entry.getKey();
            if (!moduleInfo.ignoreServiceProviders.contains(serviceName)) {
                var implementations = entry.getValue().stream().map(ExtraJavaModuleInfoTransform::getInternalName).toArray(String[]::new);
                moduleVisitor.visitProvide(getInternalName(serviceName), implementations);
            }
        }
    }

    static String getInternalName(String originalName) {
        return originalName.replace('.', '/');
    }

    private void mergeJars(ModuleSpec moduleSpec, JarOutputStream outputStream, Map<String, List<String>> providers, Set<String> packages) throws IOException {
        if (moduleSpec.getMergedJars().isEmpty()) {
            return;
        }

        RegularFile mergeJarFile = null;
        for (String identifier : moduleSpec.getMergedJars()) {
            List<String> ids = getParameters().getMergeJarIds().get();
            List<RegularFile> jarFiles = getParameters().getMergeJars().get();
            for (int i = 0; i < ids.size(); i++) {
                // referenced by 'group:version'
                if (ids.get(i).equals(identifier)) {
                    mergeJarFile = jarFiles.get(i);
                    break;
                }
                // referenced by 'jar file name'
                if (jarFiles.get(i).getAsFile().getName().equals(identifier)) {
                    mergeJarFile = jarFiles.get(i);
                    break;
                }
            }

            if (mergeJarFile != null) {
                try (JarInputStream toMergeInputStream = new JarInputStream(Files.newInputStream(mergeJarFile.getAsFile().toPath()))) {
                    copyAndExtractProviders(toMergeInputStream, outputStream, true, providers, packages, null);
                }
            } else {
                throw new RuntimeException("Jar not found: " + identifier);
            }
        }

        mergeServiceProviderFiles(outputStream, providers);
    }

    private void mergeServiceProviderFiles(JarOutputStream outputStream, Map<String, List<String>> providers) throws IOException {
        for (var provider : providers.entrySet()) {
            JarEntry jarEntry = new JarEntry(SERVICES_PREFIX + provider.getKey());
            outputStream.putNextEntry(jarEntry);
            for (String implementation : provider.getValue()) {
                outputStream.write(implementation.getBytes());
                outputStream.write("\n".getBytes());
            }
            outputStream.closeEntry();
        }
    }

    private String gaToModuleName(String ga) {
        ModuleSpec moduleSpec = getParameters().getModuleSpecs().get().get(ga);
        if (moduleSpec == null) {
            throw new RuntimeException("[requires directives from metadata] " +
                    "The module name of the following component is not known: " + ga +
                    "\n - If it is already a module, make the module name known using 'knownModule(\"" + ga + "\", \"<module name>\")'" +
                    "\n - If it is not a module, patch it using 'module()' or 'automaticModule()'");
        }
        return moduleSpec.getModuleName();
    }
}
