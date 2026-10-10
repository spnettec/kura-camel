/* SPDX-License-Identifier: EPL-2.0 */
package org.eclipse.kura.camel.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fork DSL contract: load and execute each supported format with Camel 4.20. */
class ForkDslRoutesProviderTest {
    @ParameterizedTest
    @ValueSource(strings = { "xml", "java", "yaml" })
    void loadsAndExecutesConfiguredDsl(String extension) throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.disableJMX();
            DslRoutesProvider.fromString(route(extension), "ForkJavaRoutes." + extension).applyRoutes(context);
            context.start();
            try (var producer = context.createProducerTemplate()) {
                assertEquals("fork-" + extension, producer.requestBody("direct:fork-dsl", "input", String.class));
                assertEquals(1, context.getRoutes().size());
            }
        }
    }

    private static String route(String extension) {
        return switch (extension) {
        case "xml" -> """
                <routes xmlns="http://camel.apache.org/schema/spring">
                  <route id="fork-xml"><from uri="direct:fork-dsl"/>
                    <setBody><constant>fork-xml</constant></setBody>
                  </route>
                </routes>
                """;
        case "yaml" -> """
                - route:
                    id: fork-yaml
                    from:
                      uri: direct:fork-dsl
                      steps:
                        - setBody:
                            constant: fork-yaml
                """;
        case "java" -> """
                import org.apache.camel.builder.RouteBuilder;
                public class ForkJavaRoutes extends RouteBuilder {
                    public void configure() {
                        from("direct:fork-dsl").routeId("fork-java").setBody(constant("fork-java"));
                    }
                }
                """;
        default -> throw new IllegalArgumentException(extension);
        };
    }
}
