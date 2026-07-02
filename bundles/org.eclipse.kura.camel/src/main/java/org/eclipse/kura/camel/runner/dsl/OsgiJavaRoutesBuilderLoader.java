/*******************************************************************************
 * Copyright (c) 2026 Eurotech and/or its affiliates and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Eurotech
 ******************************************************************************/
package org.eclipse.kura.camel.runner.dsl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.dsl.java.joor.JavaRoutesBuilderLoader;
import org.apache.camel.spi.CamelBeanPostProcessor;
import org.apache.camel.spi.CompilePostProcessor;
import org.apache.camel.support.PluginHelper;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Camel-java-joor-dsl was designed for app-classpath runtimes. In OSGi the
 * camel jars are inside the bundle's {@code lib/} as embedded jars; the JDK
 * compiler that joor invokes can't reach them, so user routes that import
 * camel classes fail with "package org.apache.camel.builder does not exist".
 *
 * <p>
 * This subclass materializes the embedded {@code lib/*.jar} entries to the
 * bundle data area on first use and exposes them via a {@link URLClassLoader}.
 * Camel's MultiCompile then scrapes those URLs to build the {@code -classpath}
 * option for the underlying javac invocation.
 */
public class OsgiJavaRoutesBuilderLoader extends JavaRoutesBuilderLoader {

    private static final Logger logger = LoggerFactory.getLogger(OsgiJavaRoutesBuilderLoader.class);

    @Override
    protected void doBuild() throws Exception {
        super.doBuild();
        // Camel's JavaRoutesBuilderLoader runs bean post-processing only for
        // non-RouteBuilder pojos, so @BindToRegistry methods on a user
        // RouteBuilder are silently dropped. camel-main / spring-boot
        // post-process all RoutesBuilders via their runtime; in Kura we have
        // to do it ourselves. Hook a post-compile step that bean-post-
        // processes RouteBuilders after they're instantiated.
        addCompilePostProcessor(new RouteBuilderBeanPostProcessor());
    }

    private static final class RouteBuilderBeanPostProcessor implements CompilePostProcessor {

        @Override
        public void postCompile(CamelContext context, String name, Class<?> clazz, byte[] byteCode, Object instance)
                throws Exception {
            if (!(instance instanceof RouteBuilder)) {
                return;
            }
            CamelBeanPostProcessor bpp = PluginHelper.getBeanPostProcessor(context);
            if (bpp != null) {
                bpp.postProcessBeforeInitialization(instance, instance.getClass().getName());
                bpp.postProcessAfterInitialization(instance, instance.getClass().getName());
            }
        }
    }

    @Override
    protected ClassLoader resolveParentClassLoader() {
        final ClassLoader parent = super.resolveParentClassLoader();
        final Bundle bundle = FrameworkUtil.getBundle(JavaRoutesBuilderLoader.class);
        if (bundle == null) {
            return parent;
        }
        final BundleContext ctx = bundle.getBundleContext();
        final File storage = ctx.getDataFile("joor-libs");
        if (storage == null) {
            return parent;
        }
        if (!storage.isDirectory() && !storage.mkdirs()) {
            logger.warn("Cannot create joor-libs storage at {}", storage);
            return parent;
        }

        // Resolve the Equinox install area as an absolute directory for
        // resolving relative bundle locations (common in PDE).
        final File installArea = resolveInstallArea(ctx);

        final List<URL> runtimeUrls = new ArrayList<>();
        final List<URL> compileUrls = new ArrayList<>();

        // (1) Materialize embedded lib/*.jar (Camel + jOOR itself).
        // These are the runtime classpath — they contain Camel types that
        // are NOT available via OSGi imports.
        final Enumeration<URL> entries = bundle.findEntries("lib", "*.jar", false);
        while (entries != null && entries.hasMoreElements()) {
            final URL entry = entries.nextElement();
            final String path = entry.getPath();
            final int slash = path.lastIndexOf('/');
            final String name = slash >= 0 ? path.substring(slash + 1) : path;
            final File target = new File(storage, name);
            try {
                if (!target.exists() || target.length() == 0) {
                    try (InputStream is = entry.openStream(); OutputStream os = new FileOutputStream(target)) {
                        is.transferTo(os);
                    }
                }
                runtimeUrls.add(target.toURI().toURL());
            } catch (Exception e) {
                logger.warn("Failed to materialize {} for joor compile classpath", entry, e);
            }
        }

        // (2) Collect classpath roots from other bundles for javac
        // compilation only. These go into the URL[] returned by
        // getURLs() (seen by javac) but NOT into the runtime
        // loadClass() search path — that avoids ClassCastException
        // when the same class is loaded by both this URLClassLoader
        // and OSGi wiring.
        int bundleCount = 0;
        int jarCount = 0;
        int dirCount = 0;
        final List<String> skipped = new ArrayList<>();
        final List<String> sampleLocations = new ArrayList<>();
        for (Bundle b : ctx.getBundles()) {
            if (b.getBundleId() == 0)
                continue;
            final int state = b.getState();
            if (state != Bundle.ACTIVE && state != Bundle.RESOLVED && state != Bundle.STARTING)
                continue;
            if (addBundleClasspathRoot(compileUrls, b, installArea)) {
                bundleCount++;
                // track JAR vs directory
                URL loc = extractFileUrl(b.getLocation(), installArea);
                if (loc != null && loc.getPath() != null && loc.getPath().endsWith(".jar")) {
                    jarCount++;
                } else if (loc != null) {
                    dirCount++;
                }
            } else {
                skipped.add(b.getSymbolicName());
                // collect a few sample locations for debugging
                if (sampleLocations.size() < 5) {
                    sampleLocations.add(b.getSymbolicName() + " → " + b.getLocation());
                }
            }
        }
        logger.info("joor: {} external roots ({} jars, {} dirs) from {} bundles checked, {} skipped", bundleCount,
                jarCount, dirCount, bundleCount + skipped.size(), skipped.size());
        if (!sampleLocations.isEmpty()) {
            logger.info("joor: sample skipped locations: {}", sampleLocations);
        }

        // (3) Return a URLClassLoader whose getURLs() exposes ALL jars (for
        // javac -classpath) but whose loadClass() only searches runtimeUrls.
        // Vert.x/SLF4J classes in compileUrls fall through to the OSGi
        // parent → same Class instances as the rest of the runtime.
        class CompileAwareClassLoader extends URLClassLoader {

            private final URL[] allUrls;

            CompileAwareClassLoader(URL[] runtime, URL[] compile, ClassLoader parent) {
                super(runtime, parent);
                this.allUrls = new URL[runtime.length + compile.length];
                System.arraycopy(runtime, 0, allUrls, 0, runtime.length);
                System.arraycopy(compile, 0, allUrls, runtime.length, compile.length);
            }

            @Override
            public URL[] getURLs() {
                return allUrls;
            }
        }

        if (runtimeUrls.isEmpty()) {
            logger.warn("joor compile classpath has 0 jars for bundle {}", bundle.getSymbolicName());
            return parent;
        }
        logger.info("Built joor classpath: {} runtime + {} compile jars (parent={})", runtimeUrls.size(),
                compileUrls.size(), parent.getClass().getName());
        return new CompileAwareClassLoader(runtimeUrls.toArray(new URL[0]), compileUrls.toArray(new URL[0]), parent);
    }

    /**
     * Resolve the Equinox install area to an absolute {@link File}.
     * Used as the base directory for resolving relative {@code file:} URLs
     * that PDE emits for workspace bundles.
     */
    private static File resolveInstallArea(BundleContext ctx) {
        try {
            String installArea = ctx.getProperty("osgi.install.area");
            if (installArea != null) {
                // osgi.install.area is a URL, e.g. file:/path/to/workspace/.metadata/.../
                if (installArea.startsWith("reference:")) {
                    installArea = installArea.substring("reference:".length());
                }
                URI uri = new URI(installArea);
                if (!uri.isAbsolute() || uri.getPath() == null) {
                    // relative install area — resolve against user.dir
                    uri = new File(System.getProperty("user.dir", ".")).toURI().resolve(uri);
                }
                File f = new File(uri);
                if (f.isDirectory())
                    return f;
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve install area", e);
        }
        // fallback to current working directory
        return new File(System.getProperty("user.dir", "."));
    }

    /**
     * Try to extract a classpath root from {@code bundle} and add it to
     * {@code urls} (if not already present).
     *
     * <p>
     * Strategy:
     * <ol>
     * <li><b>JAR location</b> — production + PDE target-platform bundles</li>
     * <li><b>Directory → target/classes/</b> — PDE Maven workspace bundles</li>
     * <li><b>Directory → bin/</b> — PDE Eclipse workspace bundles</li>
     * </ol>
     */
    private static boolean addBundleClasspathRoot(List<URL> urls, Bundle b, File installArea) {
        URL locUrl = extractFileUrl(b.getLocation(), installArea);

        // (a) JAR location → use directly
        if (locUrl != null && locUrl.getPath() != null && locUrl.getPath().endsWith(".jar")) {
            if (!urls.contains(locUrl)) {
                urls.add(locUrl);
            }
            return true;
        }

        // (b) Directory location — likely a PDE workspace project.
        // Try Maven target/classes/ first, then Eclipse bin/.
        if (locUrl != null) {
            try {
                File dir = new File(locUrl.toURI());
                if (dir.isDirectory()) {
                    // Maven / Tycho output
                    File targetClasses = new File(dir, "target/classes");
                    if (targetClasses.isDirectory()) {
                        URL url = targetClasses.toURI().toURL();
                        if (!urls.contains(url))
                            urls.add(url);
                        return true;
                    }
                    // Eclipse / PDE builder output
                    File binDir = new File(dir, "bin");
                    if (binDir.isDirectory()) {
                        URL url = binDir.toURI().toURL();
                        if (!urls.contains(url))
                            urls.add(url);
                        return true;
                    }
                    // No standard output dir found — add project root as
                    // last resort (may or may not contain .class files).
                    if (!urls.contains(locUrl)) {
                        urls.add(locUrl);
                    }
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    /**
     * Strip Equinox location prefixes and return an absolute {@code file:}
     * URL, or null. Relative paths are resolved against {@code installArea}
     * using filesystem semantics (correctly handles {@code ../../}).
     */
    private static URL extractFileUrl(String raw, File installArea) {
        if (raw == null)
            return null;
        // Strip "reference:" prefix (may appear multiple times / locations)
        if (raw.startsWith("reference:"))
            raw = raw.substring("reference:".length());
        int refIdx = raw.indexOf("reference:file:");
        if (refIdx >= 0)
            raw = raw.substring(refIdx + "reference:".length());
        // Strip "initial@" prefix (Equinox installer marker)
        if (raw.startsWith("initial@"))
            raw = raw.substring("initial@".length());
        // Re-check for "reference:" after stripping "initial@"
        if (raw.startsWith("reference:"))
            raw = raw.substring("reference:".length());

        if (!raw.startsWith("file:"))
            return null;
        try {
            // Extract the path portion: "file:/absolute/path" → "/absolute/path"
            // "file:relative/path" → "relative/path"
            String path = raw.substring("file:".length());

            // Resolve against the install area (handles .. correctly via
            // File.getCanonicalFile, unlike URI.resolve).
            File f = new File(path);
            if (!f.isAbsolute()) {
                f = new File(installArea, path);
            }
            f = f.getCanonicalFile();

            if (!f.exists())
                return null;
            return f.toURI().toURL();
        } catch (Exception ignored) {
            return null;
        }
    }

}
