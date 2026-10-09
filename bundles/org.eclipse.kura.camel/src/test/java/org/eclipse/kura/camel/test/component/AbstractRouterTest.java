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
 *******************************************************************************/
package org.eclipse.kura.camel.test.component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test base for testing an activated router
 */
public abstract class AbstractRouterTest {

    private static final String XML_PROPERTY = "xml.data";
    protected TestableXmlCamelComponent router;

    @BeforeEach
    public void before() throws Exception {
        this.router = createRouter();
        this.router.activate(this.router.getBundleContext(),
                Collections.<String, Object> emptyMap());
        assertNotNull(getCamelContext(), "Component activation must create a Camel context");
        assertTrue(getCamelContext().isStarted(), "Component activation must start Camel");
    }

    @AfterEach
    public void after() throws Exception {
        if (this.router != null) {
            this.router.deactivate(this.router.getBundleContext());
        }
    }

    protected CamelContext getCamelContext() {
        return this.router.getCamelContext();
    }

    protected Route firstRoute() {
        return this.router.getCamelContext().getRoutes().iterator().next();
    }

    protected static Map<String, Object> xmlProperties(String resourceName) {
        return Collections.<String, Object> singletonMap(XML_PROPERTY, readStringResource(resourceName));
    }

    protected static TestableXmlCamelComponent createRouter() {
        return new TestableXmlCamelComponent(XML_PROPERTY);
    }

    protected static String readStringResource(String resourceName) {
        try (InputStreamReader reader = new InputStreamReader(RouterTest.class.getResourceAsStream(resourceName),
                StandardCharsets.UTF_8)) {
            return IOUtils.toString(reader);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get a mock endpoint
     * <br>
     * <strong>Note:</strong> This call will fail if the endpoint is not of
     * instance MockEndpoint.
     */
    protected MockEndpoint getMockEndpoint(String endpoint) {
        MockEndpoint result = this.router.getCamelContext().getEndpoint(endpoint, MockEndpoint.class);
        result.setResultWaitTime(5000);
        return result;
    }

    /**
     * Assert all mock endpoints
     */
    protected void assertMockEndpoints() throws InterruptedException {
        MockEndpoint.assertIsSatisfied(this.router.getCamelContext());
    }
}
