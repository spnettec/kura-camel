/*******************************************************************************
 * Copyright (c) 2017, 2026 Eurotech and/or its affiliates and others
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
package org.eclipse.kura.camel.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.camel.CamelContext;
import org.apache.camel.Endpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.Registry;
import org.eclipse.kura.camel.internal.cloud.CloudClientCache;
import org.eclipse.kura.cloud.CloudClient;
import org.eclipse.kura.cloud.CloudService;
import org.eclipse.kura.core.testutil.TestUtil;
import org.junit.jupiter.api.Test;

public class KuraCloudComponentTest {

    @Test
    public void testDoStartNoRegistry() {
        final CamelContext ctxMock = mock(CamelContext.class);

        KuraCloudComponent kcc = new KuraCloudComponent() {

            @Override
            public CamelContext getCamelContext() {
                return ctxMock;
            }
        };

        try {
            kcc.doStart();
            fail("Exception was expected.");
        } catch (IllegalArgumentException e) {
            assertEquals("Registry cannot be null.", e.getMessage());
        } catch (Exception e) {
            fail("This exception was not expected.");
        }
    }

    @Test
    public void testDoStartNullSvc() throws Exception {
        final CamelContext ctxMock = mock(CamelContext.class);
        Registry regMock = mock(Registry.class);
        when(ctxMock.getRegistry()).thenReturn(regMock);

        Class<CloudService> clazz = CloudService.class;
        Set<CloudService> set = new HashSet<CloudService>();
        set.add(null);
        when(regMock.findByType(clazz)).thenReturn(set);

        KuraCloudComponent kcc = new KuraCloudComponent() {

            @Override
            public CamelContext getCamelContext() {
                return ctxMock;
            }
        };

        try {
            kcc.doStart();
            fail("Exception was expected.");
        } catch (IllegalStateException e) {
            assertEquals("'cloudService' is not set and not found in Camel context service registry", e.getMessage());
        } catch (Exception e) {
            fail("This exception was not expected.");
        }

        assertNull(TestUtil.getFieldValue(kcc, "cloudService"));
    }

    @Test
    public void testDoStart() throws Exception {
        final CamelContext ctxMock = mock(CamelContext.class);
        Registry regMock = mock(Registry.class);
        when(ctxMock.getRegistry()).thenReturn(regMock);

        Class<CloudService> clazz = CloudService.class;
        Set<CloudService> set = new HashSet<CloudService>();
        CloudService cs = mock(clazz);
        set.add(cs);
        when(regMock.findByType(clazz)).thenReturn(set);

        KuraCloudComponent kcc = new KuraCloudComponent(ctxMock);

        kcc.doStart();

        assertEquals(cs, TestUtil.getFieldValue(kcc, "cloudService"));
        assertNotNull(TestUtil.getFieldValue(kcc, "cache"));
    }

    @Test
    public void testDoStop() throws Exception {
        final CloudClientCache cacheMock = mock(CloudClientCache.class);

        KuraCloudComponent kcc = new KuraCloudComponent();

        TestUtil.setFieldValue(kcc, "cache", cacheMock);

        assertNotNull(TestUtil.getFieldValue(kcc, "cache"));

        kcc.doStop();

        assertNull(TestUtil.getFieldValue(kcc, "cache"));
        verify(cacheMock, times(1)).close();
    }

    @Test
    public void testCreateEndpointWrongRem() throws Exception {
        KuraCloudComponent kcc = new KuraCloudComponent();

        String uri = "uri";
        String remain = "remain";
        Map<String, Object> parameters = new HashMap<String, Object>();

        try {
            kcc.createEndpoint(uri, remain, parameters);
            fail("Exception was expected.");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("Wrong kura-cloud URI format"));
        }
    }

    @Test
    public void testEndpointCreatedBeforeComponentStart() throws Exception {
        try (CamelContext context = new DefaultCamelContext()) {
            CloudService cs = mock(CloudService.class);
            CloudClient client = mock(CloudClient.class);
            when(cs.newCloudClient("app")).thenReturn(client);
            context.getRegistry().bind("cloudService", CloudService.class, cs);

            KuraCloudComponent kcc = new KuraCloudComponent(context);

            Endpoint endpoint = kcc.createEndpoint("kura-cloud:app/topic", "app/topic",
                    new HashMap<String, Object>());

            kcc.start();
            endpoint.start();

            verify(cs, times(1)).newCloudClient("app");

            endpoint.stop();
            kcc.stop();
        }
    }

    @Test
    public void testCreateEndpoint() throws Exception {
        KuraCloudComponent kcc = new KuraCloudComponent();
        /*
         * A real context rather than a mock: as of Camel 4 endpoint creation reaches into the
         * registry and into several context plugins, which a bare mock cannot satisfy.
         */
        try (CamelContext context = new DefaultCamelContext()) {
            kcc.setCamelContext(context);

            String uri = "uri";
            String remain = "app/topic";
            Map<String, Object> parameters = new HashMap<String, Object>();

            Endpoint endpoint = kcc.createEndpoint(uri, remain, parameters);

            assertNotNull(endpoint);
            assertEquals(uri, endpoint.getEndpointUri());
        }
    }

}
