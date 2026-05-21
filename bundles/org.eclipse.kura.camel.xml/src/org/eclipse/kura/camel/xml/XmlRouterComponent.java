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
package org.eclipse.kura.camel.xml;

import static java.lang.String.format;
import static org.eclipse.kura.camel.component.Configuration.asBoolean;
import static org.eclipse.kura.camel.component.Configuration.asString;
import static org.eclipse.kura.camel.runner.CamelRunner.createOsgiRegistry;
import static org.eclipse.kura.camel.runner.ScriptRunner.create;
import static org.osgi.framework.FrameworkUtil.getBundle;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.script.SimpleBindings;

import org.eclipse.kura.camel.bean.PayloadFactory;
import org.eclipse.kura.camel.component.AbstractXmlCamelComponent;
import org.eclipse.kura.camel.component.Configuration;
import org.eclipse.kura.camel.component.FileChangeWatcher;
import org.eclipse.kura.camel.runner.CamelRunner.Builder;
import org.eclipse.kura.camel.runner.ScriptRunner;
import org.eclipse.kura.cloud.CloudService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

/**
 * A ready to run XML based Apache Camel component
 *
 * @noextend This class is not intended to be extended
 */
public class XmlRouterComponent extends AbstractXmlCamelComponent {

    private static final String TOKEN_PATTERN = "\\s*,\\s*";

    private static final Logger logger = LoggerFactory.getLogger(XmlRouterComponent.class);

    private static final String CLOUD_SERVICE_PREREQS = "cloudService.prereqs";
    private static final String COMPONENT_PREREQS = "component.prereqs";
    private static final String LANGUAGE_PREREQS = "language.prereqs";
    private static final String DISABLE_JMX = "disableJmx";
    private static final String INIT_CODE = "initCode";
    private static final String INIT_CODE_FILE = "initCode.file";
    private static final String SCRIPT_ENGINE_NAME = "scriptEngineName";

    private final BundleContext bundleContext;

    private Set<String> requiredComponents = new HashSet<>();
    private Set<String> requiredLanguages = new HashSet<>();

    private Map<String, String> cloudServiceRequirements = new HashMap<>();
    private String initCode = "";
    private String initCodeFilePath = "";
    private volatile FileChangeWatcher initCodeFileWatcher;
    private String scriptEngineName = "";

    private Vertx vertx;
    private WebClient webClient;

    private boolean disableJmx;

    public XmlRouterComponent() {
        super("xml.data");
        this.bundleContext = FrameworkUtil.getBundle(XmlRouterComponent.class).getBundleContext();
    }

    @Override
    protected void stop() throws Exception {
        super.stop();
        closeInitCodeFileWatcher();
        if (webClient != null) {
            webClient.close();
            webClient = null;
        }
    }

    private void updateInitCodeFileWatcher() {
        final String desired = this.initCodeFilePath;
        final FileChangeWatcher current = this.initCodeFileWatcher;

        if (desired == null || desired.isEmpty()) {
            if (current != null) {
                closeInitCodeFileWatcher();
            }
            return;
        }

        final String absoluteDesired = Paths.get(desired).toAbsolutePath().toString();
        if (current != null && current.getAbsoluteFile().toString().equals(absoluteDesired)) {
            return;
        }

        closeInitCodeFileWatcher();
        try {
            final FileChangeWatcher w = new FileChangeWatcher(desired, this::onInitCodeFileChanged);
            w.start();
            this.initCodeFileWatcher = w;
        } catch (IOException e) {
            logger.warn("Failed to start watcher for {}: {}", desired, e.getMessage());
        }
    }

    private void closeInitCodeFileWatcher() {
        final FileChangeWatcher w = this.initCodeFileWatcher;
        if (w != null) {
            w.close();
            this.initCodeFileWatcher = null;
        }
    }

    private void onInitCodeFileChanged() {
        logger.info("Detected change to {}, re-running init script on current camel context",
                this.initCodeFilePath);
        // initCode is a JSR-223 Groovy/JS script that registers closures into the
        // camel registry. Re-running it against the existing context re-binds those
        // closures (last bind wins), which is what we want. No need to stop/start
        // the camel context - and crucially, no Java DSL recompile is triggered.
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(XmlRouterComponent.class.getClassLoader());
            final org.apache.camel.CamelContext ctx = getCamelContext();
            if (ctx == null) {
                logger.warn("No camel context available, skipping initCode reload");
                return;
            }
            final String fresh = Configuration.tryReadFile(this.initCodeFilePath, logger);
            if (fresh == null) {
                logger.warn("Could not read {}, skipping initCode reload", this.initCodeFilePath);
                return;
            }
            this.initCode = fresh;
            runInitScript(ctx, fresh);
            logger.info("initCode reloaded ({} bytes)", fresh.length());
        } catch (Exception e) {
            logger.warn("Failed to re-run init script after file change", e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @Override
    protected void customizeBuilder(final Builder builder, final Map<String, Object> properties) {

        super.customizeBuilder(builder, properties);

        // JMX

        final boolean disableJmxTemp = asBoolean(properties, DISABLE_JMX, false);
        builder.disableJmx(disableJmxTemp);

        // parse configuration

        final Set<String> newRequiredComponents = parseRequirements(asString(properties, COMPONENT_PREREQS));
        final Set<String> newRequiredLanguages = parseRequirements(asString(properties, LANGUAGE_PREREQS));

        final Map<String, String> cloudServiceRequirementsTemp = parseCloudServiceRequirements(
                asString(properties, CLOUD_SERVICE_PREREQS));

        final String initCodeTemp = parseInitCode(properties);
        final String scriptEngineNameTemp = parseScriptEngineName(properties);

        // set component requirements

        logger.debug("Setting new component requirements");
        for (final String component : newRequiredComponents) {
            logger.debug("Require component: {}", component);
            builder.requireComponent(component);
        }

        logger.debug("Setting new language requirements");
        for (final String language : newRequiredLanguages) {
            if (!"simple".equals(language)) {
                logger.debug("Require language: {}", language);
                builder.requireLanguage(language);
            }
        }

        // set cloud service requirements

        logger.debug("Setting new cloud service requirements");
        for (final Map.Entry<String, String> entry : cloudServiceRequirementsTemp.entrySet()) {
            final String filter;
            if (entry.getValue().startsWith("(")) {
                filter = entry.getValue();
            } else {
                filter = format("(&(%s=%s)(kura.service.pid=%s))", Constants.OBJECTCLASS, CloudService.class.getName(),
                        entry.getValue());
            }
            builder.cloudService(null, filter, Builder.addAsCloudComponent(entry.getKey()));
        }

        if (vertx == null) {
            vertx = Vertx.vertx(new VertxOptions().setPreferNativeTransport(true));
        }
        final ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(XmlRouterComponent.class.getClassLoader());
            if (webClient != null) {
                webClient.close();
            }
            webClient = WebClient.create(vertx, new WebClientOptions().setIdleTimeout(60000));

        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }

        if (!initCodeTemp.isEmpty()) {

            // call init code before context start

            builder.addBeforeStart(camelContext -> runInitScript(camelContext, this.initCode));
        }

        // build registry

        final BundleContext ctx = getBundle(XmlRouterComponent.class).getBundleContext();
        final Map<String, Map<Class<?>, Object>> services = new HashMap<>();
        services.put("payloadFactory", Map.of(PayloadFactory.class, new PayloadFactory()));
        services.put("vertx", Map.of(Vertx.class, vertx));
        services.put("webClient", Map.of(WebClient.class, webClient));
        builder.registryFactory(createOsgiRegistry(ctx, services));

        // assign new state

        this.requiredComponents = newRequiredComponents;
        this.requiredLanguages = newRequiredLanguages;
        this.cloudServiceRequirements = cloudServiceRequirementsTemp;
        this.initCode = initCodeTemp;
        this.initCodeFilePath = asString(properties, INIT_CODE_FILE, "").trim();
        this.scriptEngineName = scriptEngineNameTemp;
        this.disableJmx = disableJmxTemp;

        updateInitCodeFileWatcher();
    }

    @Override
    protected boolean isRestartNeeded(final Map<String, Object> properties) {
        if (super.isRestartNeeded(properties)) {
            return true;
        }

        final boolean disableJmxTemp = asBoolean(properties, DISABLE_JMX, false);

        final Set<String> newRequiredComponents = parseRequirements(asString(properties, COMPONENT_PREREQS));
        final Set<String> newRequiredLanguages = parseRequirements(asString(properties, LANGUAGE_PREREQS));

        final Map<String, String> cloudServiceRequirementsTemp = parseCloudServiceRequirements(
                asString(properties, CLOUD_SERVICE_PREREQS));

        final String initCodeTemp = parseInitCode(properties);
        final String scriptEngineNameTemp = parseScriptEngineName(properties);

        if (this.disableJmx != disableJmxTemp) {
            logger.debug("Require restart due to '{}' change", DISABLE_JMX);
            return true;
        }

        if (!this.requiredComponents.equals(newRequiredComponents)) {
            logger.debug("Require restart due to '{}' change", COMPONENT_PREREQS);
            return true;
        }

        if (!this.requiredLanguages.equals(newRequiredLanguages)) {
            logger.debug("Require restart due to '{}' change", LANGUAGE_PREREQS);
            return true;
        }

        if (!this.cloudServiceRequirements.equals(cloudServiceRequirementsTemp)) {
            logger.debug("Require restart due to '{}' change", CLOUD_SERVICE_PREREQS);
            return true;
        }

        if (!this.initCode.equals(initCodeTemp)) {
            logger.debug("Require restart due to '{}' change", INIT_CODE);
            return true;
        }

        if (!this.scriptEngineName.equals(scriptEngineNameTemp)) {
            logger.debug("Require restart due to '{}' change", INIT_CODE);
            return true;
        }

        return false;
    }

    private static Map<String, String> parseCloudServiceRequirements(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyMap();
        }

        final Map<String, String> result = new HashMap<>();

        for (final String tok : value.split(TOKEN_PATTERN)) {
            logger.debug("Testing - '{}'", tok);

            final String[] s = tok.split("=", 2);
            if (s.length != 2) {
                continue;
            }

            logger.debug("CloudService - '{}' -> '{}'", s[0], s[1]);
            result.put(s[0], s[1]);
        }

        return result;
    }

    private static Set<String> parseRequirements(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }

        return new HashSet<>(Arrays.asList(value.split(TOKEN_PATTERN)));
    }

    private static String parseInitCode(final Map<String, Object> properties) {
        final String filePath = Configuration.asString(properties, INIT_CODE_FILE, "");
        final String fromFile = Configuration.tryReadFile(filePath, logger);
        if (fromFile != null) {
            logger.info("Loaded initCode from file {} ({} bytes)", filePath.trim(), fromFile.length());
            return fromFile;
        }
        return Configuration.asString(properties, INIT_CODE, "");
    }

    String getInitCodeFilePath() {
        return this.initCodeFilePath;
    }

    /**
     * Run the init script against the given camel context. Used both by the
     * {@code addBeforeStart} callback (at context startup) and by the
     * {@code initCode.file} hot-reload path (against the running context). The
     * latter only re-evaluates the script, which re-binds the registered
     * closures - no camel context restart, no Java DSL recompile.
     *
     * <p>The script gets a {@code rebind(name, bean)} helper closure in its
     * bindings — prefer it over raw {@code camelContext.getRegistry().bind(...)}
     * so each reload truly replaces the previous binding. Camel's
     * {@code DefaultRegistry} keys by {@code (name, runtime-class)} and each
     * Groovy eval generates a new closure subclass, so plain {@code bind}
     * accumulates entries and {@code lookupByName} returns a stale instance.
     * {@code rebind} unbinds-then-binds via reflection (the Registry API methods
     * are x-internal in camel-api so we can't link to them directly).
     */
    private void runInitScript(final org.apache.camel.CamelContext camelContext, final String scriptText) {
        if (scriptText == null || scriptText.isEmpty()) {
            return;
        }
        try {
            final ScriptRunner runner = create(XmlRouterComponent.class.getClassLoader(),
                    this.scriptEngineName, scriptText);
            final SimpleBindings bindings = new SimpleBindings();
            bindings.put("camelContext", camelContext);
            bindings.put("logger", logger);
            bindings.put("webClient", this.webClient);
            bindings.put("vertx", this.vertx);
            // Helper exposed to the script as both `rebind('mes', mes)` (Groovy
            // method-call syntax dispatches to call(...)) and
            // `rebind.accept('mes', mes)` (BiConsumer-style). Other script
            // engines (JS) can use either.
            bindings.put("rebind", new Object() {
                public void call(String name, Object bean) {
                    accept(name, bean);
                }
                public void accept(String name, Object bean) {
                    try {
                        camelContext.getRegistry().unbind(name);
                    } catch (Exception ignored) {
                        // unbind on a missing name may throw on some Registry impls
                    }
                    camelContext.getRegistry().bind(name, bean);
                }
            });
            runner.run(bindings);
        } catch (final Exception e) {
            logger.warn("Failed to run init code", e);
        }
    }

    private static String parseScriptEngineName(final Map<String, Object> properties) {
        return Configuration.asString(properties, SCRIPT_ENGINE_NAME, "Javascript");
    }

    @Override
    protected BundleContext getBundleContext() {
        return this.bundleContext;
    }

}
