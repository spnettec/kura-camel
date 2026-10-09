/*******************************************************************************
 * Copyright (c) 2016, 2026 Red Hat Inc and others
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Red Hat Inc
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.camel.test.component;

import static org.mockito.Mockito.mock;

import java.util.Map;

import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultRegistry;
import org.apache.camel.support.SimpleRegistry;
import org.eclipse.kura.camel.runner.CamelRunner;
import org.eclipse.kura.camel.runner.ContextFactory;
import org.eclipse.kura.camel.component.AbstractXmlCamelComponent;
import org.osgi.framework.BundleContext;

/**
 * Drives the real XML component and CamelRunner lifecycle with in-process Camel services.
 * OSGi registry/service discovery is the only replaced boundary; Equinox wiring is separate.
 */
public class TestableXmlCamelComponent extends AbstractXmlCamelComponent {

    private final BundleContext bundleContext = mock(BundleContext.class);

    public TestableXmlCamelComponent(final String xmlDataProperty) {
        super(xmlDataProperty);
    }

    @Override
    public BundleContext getBundleContext() {
        return this.bundleContext;
    }

    @Override
    protected ContextFactory getContextFactory() {
        return repository -> {
            DefaultCamelContext context = new DefaultCamelContext(new DefaultRegistry(repository));
            context.setLoadTypeConverters(true);
            return context;
        };
    }

    @Override
    protected void customizeBuilder(CamelRunner.Builder builder, Map<String, Object> properties) {
        // Camel resolves simple and route components from its normal classpath in this fixture.
        builder.registryFactory(SimpleRegistry::new).disableJmx(true);
    }

    @Override
    public void activate(final BundleContext context, final Map<String, Object> properties) throws Exception {
        super.activate(context, properties);
    }

    @Override
    public void deactivate(final BundleContext context) throws Exception {
        super.deactivate(context);
    }

    @Override
    public void modified(final Map<String, Object> properties) throws Exception {
        super.modified(properties);
    }

    @Override
    public void stop() throws Exception {
        super.stop();
    }

}
