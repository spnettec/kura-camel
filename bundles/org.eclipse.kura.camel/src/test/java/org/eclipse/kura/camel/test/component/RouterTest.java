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
package org.eclipse.kura.camel.test.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.apache.camel.CamelContext;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.ToDefinition;

public class RouterTest extends AbstractRouterTest {

    @Test
    public void testLoadNothing() throws Exception {

        // Given

        final Map<String, Object> properties = Collections.emptyMap();

        // When

        this.router.modified(properties);

        // Then

        // expect no route
        assertEquals(0, this.router.getCamelContext().getRoutes().size());
    }

    @Test
    public void testLoadXmlOnce() throws Exception {

        // Given

        final Map<String, Object> properties = xmlProperties("xml/test1.xml");

        // When

        this.router.modified(properties);

        // Then

        // expect one route
        assertEquals(1, this.router.getCamelContext().getRoutes().size());

        // with ID = test1
        assertEquals("test1", firstRoute().getId());
    }

    @Test
    public void testLoadXmlTwice() throws Exception {

        // Given

        final Map<String, Object> properties = xmlProperties("xml/test1.xml");

        // When

        this.router.modified(properties);
        CamelContext original = getCamelContext();
        this.router.modified(properties);
        assertSame(original, getCamelContext(), "Unchanged XML must retain its context");

        // Then

        // expect still one route
        assertEquals(1, this.router.getCamelContext().getRoutes().size());

        // with ID = test1
        assertEquals("test1", firstRoute().getId());
    }

    @Test
    public void testLoadXmlUpdateSame() throws Exception {

        // Given

        final Map<String, Object> properties1 = xmlProperties("xml/test1.xml");
        final Map<String, Object> properties2 = xmlProperties("xml/test1a.xml");

        // When

        this.router.modified(properties1);

        // Then

        // expect still one route
        assertEquals(1, this.router.getCamelContext().getRoutes().size());
        // with ID = test1
        assertEquals("test1", firstRoute().getId());

        // When

        CamelContext original = getCamelContext();
        this.router.modified(properties2);
        assertNotSame(original, getCamelContext(), "Changed XML must replace its context");
        assertTrue(original.isStopped(), "The replaced context must release its resources");

        // Then

        // expect still one route
        assertEquals(1, this.router.getCamelContext().getRoutes().size());
        // with ID = test1
        assertEquals("test1", firstRoute().getId());
        ToDefinition output = (ToDefinition) ((ModelCamelContext) getCamelContext())
                .getRouteDefinition("test1").getOutputs().getFirst();
        assertEquals("log:foo2", output.getUri(), "The replacement must contain the new route body");
    }

    @Test
    public void testLoadXmlUpdateOther() throws Exception {

        // Given

        final Map<String, Object> properties1 = xmlProperties("xml/test1.xml");
        final Map<String, Object> properties2 = xmlProperties("xml/test2.xml");

        // When ... load test1

        this.router.modified(properties1);

        // Then

        // expect one route
        assertEquals(1, this.router.getCamelContext().getRoutes().size());
        // with ID = test1
        assertEquals("test1", firstRoute().getId());

        // When ... update to test2

        CamelContext original = getCamelContext();
        this.router.modified(properties2);
        assertNotSame(original, getCamelContext(), "Changed XML must replace its context");
        assertTrue(original.isStopped(), "The replaced context must release its resources");

        // Then

        // expect still one route
        assertEquals(1, this.router.getCamelContext().getRoutes().size());
        // with ID = test2
        assertEquals("test2", firstRoute().getId());
    }

}
