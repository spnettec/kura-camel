/*******************************************************************************
 * Copyright (c) 2026 YOFC
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.kura.camel.validation;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.camel.CamelContext;
import org.apache.camel.core.osgi.OsgiBeanRepository;
import org.eclipse.kura.camel.runner.DslRoutesProvider;
import org.eclipse.kura.camel.runner.OsgiDefaultKuraCamelContext;
import org.eclipse.kura.script.validation.ScriptValidationError;
import org.eclipse.kura.script.validation.ScriptValidationResult;
import org.eclipse.kura.script.validation.ScriptValidationService;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXParseException;

/**
 * Validates <em>Camel DSL</em> (not generic Java/XML/YAML) by loading it through Camel's own route loaders against a
 * throwaway {@link OsgiDefaultKuraCamelContext}: the Java DSL is compiled with jOOR (camel-java-joor-dsl), the XML and
 * YAML DSLs are parsed into the route model. Any exception thrown while loading is reported as a validation error, so
 * the editor surfaces Camel-aware DSL problems instead of generic syntax-only checks.
 * <p>
 * Registered as a {@link ScriptValidationService} provider for the languages {@code camel-java}, {@code camel-xml} and
 * {@code camel-yaml}; the generic provider in the monorepo keeps handling js/groovy/python/wasm. The web UI dispatches
 * to whichever provider declares the requested language, so the editor component stays Camel-agnostic.
 */
public class CamelDslValidationService implements ScriptValidationService {

    private static final Logger logger = LoggerFactory.getLogger(CamelDslValidationService.class);

    private static final String LANG_JAVA = "camel-java";
    private static final String LANG_XML = "camel-xml";
    private static final String LANG_YAML = "camel-yaml";

    private static final Set<String> SUPPORTED_LANGUAGES = Collections
            .unmodifiableSet(new LinkedHashSet<>(Arrays.asList(LANG_JAVA, LANG_XML, LANG_YAML)));

    private static final Pattern JAVA_PUBLIC_CLASS_PATTERN = Pattern
            .compile("(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");

    // Position embedded in loader error messages: "...:line:col" (javac/jOOR) or "line N, column M" (snakeyaml).
    private static final Pattern LINE_COL_PATTERN = Pattern.compile(":(\\d+):(\\d+)");
    private static final Pattern LINE_COMMA_COL_PATTERN = Pattern.compile("line (\\d+),?\\s+column (\\d+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public Set<String> getSupportedLanguages() {
        return SUPPORTED_LANGUAGES;
    }

    @Override
    public ScriptValidationResult validate(final String language, final String script) {
        if (script == null || script.trim().isEmpty()) {
            return ScriptValidationResult.valid();
        }

        final String fileName = fileNameFor(language == null ? "" : language.trim().toLowerCase(), script);
        if (fileName == null) {
            logger.debug("No Camel DSL validator for language '{}', skipping.", language);
            return ScriptValidationResult.valid();
        }

        // The route loaders (jOOR java compile, model parsing) resolve Camel classes through the thread context class
        // loader; the validation call arrives on a web-server thread, so point the TCCL at this bundle.
        final BundleContext bundleContext = FrameworkUtil.getBundle(getClass()).getBundleContext();

        final Thread current = Thread.currentThread();
        final ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(getClass().getClassLoader());

        CamelContext camelContext = null;
        try {
            camelContext = new OsgiDefaultKuraCamelContext(bundleContext, new OsgiBeanRepository(bundleContext));
            new DslRoutesProvider(script, fileName).applyRoutes(camelContext);
            return ScriptValidationResult.valid();
        } catch (final Exception e) {
            return ScriptValidationResult.invalid(Collections.singletonList(toError(e)));
        } finally {
            if (camelContext != null) {
                try {
                    camelContext.close();
                } catch (final Exception e) {
                    logger.debug("Failed to close throwaway CamelContext after validation.", e);
                }
            }
            current.setContextClassLoader(previous);
        }
    }

    private static String fileNameFor(final String language, final String script) {
        switch (language) {
        case LANG_JAVA:
            final Matcher m = JAVA_PUBLIC_CLASS_PATTERN.matcher(script);
            return (m.find() ? m.group(1) : "Routes") + ".java";
        case LANG_XML:
            return "data.xml";
        case LANG_YAML:
            return "data.yaml";
        default:
            return null;
        }
    }

    private static ScriptValidationError toError(final Throwable e) {
        final SAXParseException sax = findCause(e, SAXParseException.class);
        if (sax != null) {
            return new ScriptValidationError(sax.getLineNumber(), sax.getColumnNumber(), sax.getMessage(),
                    ScriptValidationError.SEVERITY_ERROR);
        }

        final Throwable root = rootCause(e);
        final String message = root.getMessage() != null ? root.getMessage() : root.toString();

        final Matcher lineCol = LINE_COL_PATTERN.matcher(message);
        if (lineCol.find()) {
            return new ScriptValidationError(Integer.parseInt(lineCol.group(1)), Integer.parseInt(lineCol.group(2)),
                    message, ScriptValidationError.SEVERITY_ERROR);
        }
        final Matcher lineComma = LINE_COMMA_COL_PATTERN.matcher(message);
        if (lineComma.find()) {
            return new ScriptValidationError(Integer.parseInt(lineComma.group(1)),
                    Integer.parseInt(lineComma.group(2)), message, ScriptValidationError.SEVERITY_ERROR);
        }
        return new ScriptValidationError(-1, -1, message, ScriptValidationError.SEVERITY_ERROR);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(final Throwable throwable, final Class<T> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return (T) current;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }

    private static Throwable rootCause(final Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
