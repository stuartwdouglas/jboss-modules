/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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
package org.jboss.modules;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

/**
 * Java NIO2 Path-based ResourceLoader
 *
 * @author <a href="mailto:bsideup@gmail.com">Sergei Egorov</a>
 */
class PathResourceLoader extends AbstractResourceLoader implements IterableResourceLoader {

    private final String rootName;
    protected final Path root;
    protected final AccessControlContext context;

    private final Manifest manifest;
    private final CodeSource codeSource;

    PathResourceLoader(final String rootName, final Path root, final AccessControlContext context) {
        if (rootName == null) {
            throw new IllegalArgumentException("rootName is null");
        }
        if (root == null) {
            throw new IllegalArgumentException("root is null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context is null");
        }
        this.rootName = rootName;
        this.root = root;
        this.context = context;
        final Path manifestFile = root.resolve("META-INF").resolve("MANIFEST.MF");
        manifest = readManifestFile(manifestFile);

        try {
            codeSource = doPrivilegedIfNeeded(context, () -> new CodeSource(root.toUri().toURL(), (CodeSigner[]) null));
        } catch (UndeclaredThrowableException e) {
            if (e.getUndeclaredThrowable() instanceof MalformedURLException) {
                throw new IllegalArgumentException("Invalid root file specified", e);
            } else {
                throw e;
            }
        }
    }

    private Manifest readManifestFile(final Path manifestFile) {
        try {
            return doPrivilegedIfNeeded(context, () -> {
                if (Files.isDirectory(manifestFile)) {
                    return null;
                }

                try (InputStream is = Files.newInputStream(manifestFile)) {
                    return new Manifest(is);
                }
            });
        } catch (UndeclaredThrowableException e) {
            if (e.getUndeclaredThrowable() instanceof IOException) {
                return null;
            } else {
                throw e;
            }
        }
    }

    @Override
    public String getRootName() {
        return rootName;
    }

    @Override
    public String getLibrary(String name) {
        final String mappedName = System.mapLibraryName(name);
        for (String path : NativeLibraryResourceLoader.Identification.NATIVE_SEARCH_PATHS) {
            Path testFile = root.resolve(path).resolve(mappedName);
            if (Files.exists(testFile)) {
                return testFile.toAbsolutePath().toString();
            }
        }
        return null;
    }

    @Override
    public ClassSpec getClassSpec(final String fileName) throws IOException {
        final Path file = root.resolve(fileName);

        try {
            return doPrivilegedIfNeeded(context, () -> {
                if (!Files.exists(file)) {
                    return null;
                }
                final ClassSpec spec = new ClassSpec();
                spec.setCodeSource(codeSource);
                spec.setBytes(Files.readAllBytes(file));
                return spec;
            });
        } catch (UndeclaredThrowableException e) {
            if (e.getUndeclaredThrowable() instanceof IOException) {
                throw (IOException) e.getUndeclaredThrowable();
            } else {
                throw e;
            }
        }
    }

    @Override
    public PackageSpec getPackageSpec(final String name) throws IOException {
        try {
            URL rootUrl = doPrivilegedIfNeeded(context, () -> root.toUri().toURL());
            return getPackageSpec(name, manifest, rootUrl);
        } catch (UndeclaredThrowableException e) {
            if (e.getUndeclaredThrowable() instanceof IOException) {
                throw (IOException) e.getUndeclaredThrowable();
            } else {
                throw e;
            }
        }
    }

    @Override
    public Resource getResource(final String name) {
        final Path file = root.resolve(PathUtils.canonicalize(PathUtils.relativize(name)));

        if (!doPrivilegedIfNeeded(context, () -> Files.exists(file))) {
            return null;
        }

        return new PathResource(file, context);
    }

    @Override
    public Iterator<Resource> iterateResources(final String startPath, final boolean recursive) {
        try {
            Path path = root.resolve(PathUtils.canonicalize(PathUtils.relativize(startPath)));
            return Files.walk(path, recursive ? Integer.MAX_VALUE : 1)
                    .filter(it -> !Files.isDirectory(it))
                    .map(root::relativize)
                    .<Resource>map(resourcePath -> new PathResource(resourcePath, context))
                    .iterator();
        } catch (IOException e) {
            return Collections.emptyIterator();
        }
    }

    @Override
    public Collection<String> getPaths() {
        try {
            final String separator = root.getFileSystem().getSeparator();

            return doPrivilegedIfNeeded(context, () -> {
                return Files.walk(root)
                        .filter(Files::isDirectory)
                        .map(dir -> {
                            final String result = root.relativize(dir).toString();

                            // JBoss modules expect folders not to end with a slash, so we have to strip it.
                            if (result.endsWith(separator)) {
                                return result.substring(0, result.length() - separator.length());
                            } else {
                                return result;
                            }
                        })
                        .collect(Collectors.toList());
            });
        } catch (UndeclaredThrowableException e) {
            if (e.getUndeclaredThrowable() instanceof IOException) {
                return Collections.emptyList();
            } else {
                throw e;
            }
        }
    }

    @Override
    public URI getLocation() {
        return doPrivilegedIfNeeded(context, root::toUri);
    }

    static <T> T doPrivilegedIfNeeded(AccessControlContext context, PrivilegedExceptionAction<T> action) {
        SecurityManager sm = System.getSecurityManager();

        if (sm == null) {
            try {
                return action.run();
            } catch (Exception e) {
                throw new UndeclaredThrowableException(e);
            }
        } else {
            try {
                return AccessController.doPrivileged(action, context);
            } catch (PrivilegedActionException e) {
                try {
                    throw e.getException();
                } catch (Exception e1) {
                    throw new UndeclaredThrowableException(e1);
                }
            }
        }
    }
}
