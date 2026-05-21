/*******************************************************************************
 * Copyright (c) 2016, 2020 Red Hat Inc and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Red Hat Inc
 *******************************************************************************/

package org.eclipse.kura.camel.component;

import static org.eclipse.kura.camel.component.Configuration.asString;
import static org.eclipse.kura.camel.component.Configuration.fileNameOf;
import static org.eclipse.kura.camel.component.Configuration.tryReadFile;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.kura.configuration.ConfigurableComponent;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An abstract base class for implementing a {@link ConfigurableComponent} using
 * configured XML
 * <p>
 * This class intended to be subclasses and customized according to needs.
 * </p>
 * <p>
 * <strong>Note:</strong> This class is intended to be used as <em>OSGi Service
 * Component</em>. There the methods {@link #activate(BundleContext, Map)},
 * {@link #modified(Map)} and {@link #deactivate(BundleContext)} need to be
 * configured accordingly.
 * </p>
 * <p>
 * The lifecycle methods of this class declare annotations based on {@link org.osgi.service.component.annotations}.
 * However those annotations are only discovered during build time. They are declared in order
 * to provide proper support when annotation based tooling is used. Otherwise those methods must be
 * mapped manually in the DS declaration.
 * </p>
 */
public abstract class AbstractXmlCamelComponent extends AbstractCamelComponent implements ConfigurableComponent {

    private static final Logger logger = LoggerFactory.getLogger(AbstractXmlCamelComponent.class);
    private static final Pattern JAVA_PUBLIC_CLASS_PATTERN = Pattern
            .compile("(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");

    private final String xmlDataProperty;
    private String data = "";
    private String model = "xml";
    private String dataFilePath = "";
    private volatile FileChangeWatcher dataFileWatcher;
    private volatile Map<String, Object> lastProperties = Collections.emptyMap();

    protected AbstractXmlCamelComponent(final String xmlDataProperty) {
        Objects.requireNonNull(xmlDataProperty);

        this.xmlDataProperty = xmlDataProperty;
    }

    protected final String getDataFilePath() {
        return this.dataFilePath;
    }

    /**
     * The most recent configuration properties seen via {@link #activate} or
     * {@link #modified}. Exposed for subclasses that need to re-trigger the
     * lifecycle on out-of-band events (e.g. a file watcher firing).
     */
    protected final Map<String, Object> getLastProperties() {
        return this.lastProperties;
    }

    private String dataFilePathKey() {
        return this.xmlDataProperty + ".file";
    }

    private static final class ResolvedSource {
        final String content;
        final String model;
        final String fileName;
        final String filePath;

        ResolvedSource(String content, String model, String fileName, String filePath) {
            this.content = content == null ? "" : content;
            this.model = model;
            this.fileName = fileName;
            this.filePath = filePath == null ? "" : filePath;
        }
    }

    private ResolvedSource resolveSource(final Map<String, Object> properties) {
        final String filePath = asString(properties, dataFilePathKey(), "");
        final String fileContent = tryReadFile(filePath, logger);
        if (fileContent != null) {
            final String fileName = fileNameOf(filePath);
            return new ResolvedSource(fileContent, extensionOf(fileName), fileName, filePath.trim());
        }
        final String inline = asString(properties, this.xmlDataProperty);
        final String inlineModel = asString(properties, "file.extension", "xml");
        return new ResolvedSource(inline, inlineModel, deriveFileName(inlineModel, inline), "");
    }

    private static String extensionOf(final String fileName) {
        if (fileName == null) {
            return "xml";
        }
        final int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot >= fileName.length() - 1) {
            return "xml";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    protected void activate(final BundleContext context, final Map<String, Object> properties) throws Exception {
        this.lastProperties = properties;
        try {
            start(properties);

            // apply current routes
            applyRoutes(properties);
            updateDataFileWatcher();
        } catch (Exception e) {
            logger.warn("Problem activating component", e);
            // we need to suppress exceptions during start
            // otherwise Kura cannot configure us anymore
        }
    }

    protected void deactivate(final BundleContext context) throws Exception {
        try {
            closeDataFileWatcher();
            stop();
        } catch (Exception e) {
            logger.warn("Problem deactivating component", e);
            throw e;
        }
    }

    protected synchronized void modified(final Map<String, Object> properties) throws Exception {
        logger.debug("Updating properties: {}", properties);
        this.lastProperties = properties;
        try {
            if (isRestartNeeded(properties)) {
                logger.info("Need restart");
                stop();
                start(properties);
            }

            // apply current routes

            applyRoutes(properties);
            updateDataFileWatcher();
        } catch (Exception e) {
            logger.warn("Problem updating component", e);
            throw e;
        }
    }

    /**
     * Reconcile the data-file watcher with {@link #dataFilePath} set by the
     * most recent {@link #applyRoutes}. Closes a stale watcher, opens a new one
     * for the current path, or removes any existing watcher when the path is
     * empty.
     */
    private void updateDataFileWatcher() {
        final String desired = this.dataFilePath;
        final FileChangeWatcher current = this.dataFileWatcher;

        if (desired == null || desired.isEmpty()) {
            if (current != null) {
                closeDataFileWatcher();
            }
            return;
        }

        final String absoluteDesired = Paths.get(desired).toAbsolutePath().toString();
        if (current != null && current.getAbsoluteFile().toString().equals(absoluteDesired)) {
            return;
        }

        closeDataFileWatcher();
        try {
            final FileChangeWatcher w = new FileChangeWatcher(desired, this::onDataFileChanged);
            w.start();
            this.dataFileWatcher = w;
        } catch (IOException e) {
            logger.warn("Failed to start watcher for {}: {}", desired, e.getMessage());
        }
    }

    private void closeDataFileWatcher() {
        final FileChangeWatcher w = this.dataFileWatcher;
        if (w != null) {
            w.close();
            this.dataFileWatcher = null;
        }
    }

    private void onDataFileChanged() {
        logger.info("Detected change to {}, re-applying configuration", this.dataFilePath);
        // WatchService daemon thread inherits the system classloader as TCCL.
        // jOOR (used by camel-java-joor-dsl) and Camel's RoutesLoader both
        // consult Thread.currentThread().getContextClassLoader() to resolve
        // Camel API packages; without this swap the compile dies with
        // "cannot access unnamed package" on the first import.
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
            modified(this.lastProperties);
        } catch (Exception e) {
            logger.warn("Failed to re-apply configuration after file change", e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    private void applyRoutes(final Map<String, Object> properties) throws Exception {
        final ResolvedSource src = resolveSource(properties);
        this.model = src.model;
        this.data = src.content;
        this.dataFilePath = src.filePath;
        if (!src.filePath.isEmpty()) {
            logger.info("Loaded routes from file {} ({} bytes, model={})",
                    src.filePath, src.content.length(), src.model);
        }
        this.runner.setRoutes(this.data, src.fileName);
    }

    private static String deriveFileName(String extension, String content) {
        if ("java".equalsIgnoreCase(extension) && content != null) {
            Matcher m = JAVA_PUBLIC_CLASS_PATTERN.matcher(content);
            if (m.find()) {
                return m.group(1) + ".java";
            }
        }
        return "data." + extension;
    }

    protected boolean isRestartNeeded(final Map<String, Object> properties) {
        final ResolvedSource src = resolveSource(properties);
        if (!this.data.equals(src.content)) {
            logger.debug("Require restart due to data change (source={})",
                    src.filePath.isEmpty() ? "inline" : src.filePath);
            return true;
        }
        if (!this.model.equals(src.model)) {
            logger.debug("Require restart due to model change ({} -> {})", this.model, src.model);
            return true;
        }
        return false;
    }

}
