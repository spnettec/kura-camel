/* SPDX-License-Identifier: EPL-2.0 */
package org.eclipse.kura.camel.testing.fullruntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import com.google.gson.Gson;
import org.apache.camel.CamelContext;
import org.eclipse.kura.cloud.CloudService;
import org.eclipse.kura.cloudconnection.CloudConnectionManager;
import org.eclipse.kura.cloudconnection.factory.CloudConnectionFactory;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.configuration.Password;
import org.eclipse.kura.data.DataTransportService;
import org.eclipse.kura.marshalling.Marshaller;
import org.eclipse.kura.marshalling.Unmarshaller;
import org.eclipse.kura.message.KuraPayload;
import org.eclipse.kura.security.SecurityService;
import org.eclipse.kura.system.SystemService;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.osgi.framework.*;

/** Uses production fork behavior, never substitutes an upstream router. */
public final class ForkProbe implements BundleActivator {
    private static final String ROUTER = "org.eclipse.kura.camel.xml.XmlRouterComponent";
    private static final String JSON = "org.eclipse.kura.json.marshaller.unmarshaller.provider";
    private static final String ACCOUNT = "complete-mac-account";
    private static final String USER = "complete-mac-user", PASSWORD = "isolated-test-password";
    private final List<ServiceReference<?>> references = new ArrayList<>();
    private final List<Map<String, Object>> packets = new ArrayList<>();
    private Thread worker;

    public void start(BundleContext context) { worker = new Thread(() -> run(context), "CompleteMacCamelForkAcceptance"); worker.start(); }
    public void stop(BundleContext context) throws InterruptedException { if (worker != null && worker.isAlive()) { worker.interrupt(); worker.join(5000); } }

    private void run(BundleContext context) {
        Path root = Path.of(System.getProperty("kura.acceptance.root")).toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("kura.home")).toAbsolutePath().normalize();
        String nonce = UUID.randomUUID().toString(), routerPid = "acceptance.mac.camel." + nonce;
        String cloudPid = routerPid + ".cloud", client = "camel-cloud-" + nonce, observerId = "camel-observer-" + nonce;
        String broker = System.getProperty("kura.acceptance.broker"), http = System.getProperty("kura.acceptance.http");
        Map<String, Object> evidence = new LinkedHashMap<>();
        ConfigurationService configuration = null;
        CloudConnectionFactory factory = null;
        CloudConnectionManager manager = null;
        boolean cloudCreated = false, routerCreated = false, databaseCreated = false;
        Throwable failure = null;
        try {
            require(home.startsWith(root) && !home.equals(root) && Files.isRegularFile(home.resolve(".camel-fork-acceptance-owned")), "Owned isolated profile");
            require(broker.startsWith("tcp://127.0.0.1:") && http.startsWith("http://127.0.0.1:"), "Loopback services");
            require(context.getBundles().length >= 278, "Complete application");
            SystemService system = service(context, SystemService.class, null);
            require(system.getClass().getName().equals("org.eclipse.kura.core.system.SystemServiceImpl"), "Actual host service");
            require(Path.of(system.getKuraHome()).toAbsolutePath().normalize().equals(home), "Owned host home");
            evidence.put("securityServiceProviderCount", context.getServiceReferences(SecurityService.class, null).size());
            configuration = service(context, ConfigurationService.class, null);
            factory = service(context, CloudConnectionFactory.class, "(service.pid=org.eclipse.kura.core.cloud.factory.DefaultCloudServiceFactory)");
            require("org.eclipse.kura.cloud.CloudService".equals(factory.getFactoryPid()), "Fork cloud factory PID");
            if (!configuration.getConfigurableComponentPids().contains("org.eclipse.kura.db.H2DbService")) {
                configuration.createFactoryConfiguration("org.eclipse.kura.core.db.H2DbService", "org.eclipse.kura.db.H2DbService",
                        new HashMap<>(Map.of("db.connector.url", "jdbc:h2:file:" + home.resolve("data/camel-messages"))), true);
                databaseCreated = true;
            }
            factory.createConfiguration(cloudPid, "测试 Camel 云连接", "保留定制脚本及名称描述"); cloudCreated = true;
            var stack = factory.getStackComponentsPids(cloudPid);
            require(stack.size() == 3 && stack.getFirst().equals(cloudPid), "Actual cloud stack identity");
            CloudService cloud = service(context, CloudService.class, "(kura.service.pid=" + cloudPid + ")");
            var cloudRef = context.getServiceReferences(CloudService.class, "(kura.service.pid=" + cloudPid + ")").iterator().next();
            require("org.eclipse.kura.cloudconnection.kapua.mqtt.provider".equals(cloudRef.getBundle().getSymbolicName()), "Actual Kapua provider");
            require("测试 Camel 云连接".equals(cloudRef.getProperty("kura.cloud.factory.name"))
                    && "保留定制脚本及名称描述".equals(cloudRef.getProperty("kura.cloud.factory.desc")), "Fork name and description");
            configuration.updateConfiguration(stack.get(2), new HashMap<>(Map.of("broker-url", broker, "username", USER,
                    "password", new Password(PASSWORD.toCharArray()), "client-id", client, "topic.context.account-name", ACCOUNT, "timeout", 3)), false);
            configuration.updateConfiguration(cloudPid, new HashMap<>(Map.of("topic.control-prefix", "EDC", "payload.encoding", "simple-json", "encode.gzip", false)), false);
            DataTransportService transport = service(context, DataTransportService.class, "(kura.service.pid=" + stack.get(2) + ")");
            await(() -> broker.equals(transport.getBrokerUrl()) && client.equals(transport.getClientId())
                    && ACCOUNT.equals(transport.getAccountName()) && "simple-json".equals(cloudRef.getProperty("payload.encoding")), "Transport configuration");
            manager = service(context, CloudConnectionManager.class, "(kura.service.pid=" + cloudPid + ")");
            manager.connect(); await(cloud::isConnected, "Actual cloud connection");
            Marshaller marshaller = service(context, Marshaller.class, "(kura.service.pid=" + JSON + ")");
            Unmarshaller unmarshaller = service(context, Unmarshaller.class, "(kura.service.pid=" + JSON + ")");
            Path script = home.resolve("tmp/init-" + nonce + ".groovy");
            Files.writeString(script, script("groovy-v1"));
            Map<String, Object> props = new HashMap<>();
            props.put("xml.data", routes(nonce, "")); props.put("file.extension", "xml");
            props.put("component.prereqs", "direct"); props.put("cloudService.prereqs", "acceptance-cloud=" + cloudPid);
            props.put("scriptEngineName", "Groovy"); props.put("initCode", script("unused-inline"));
            props.put("initCode.file", script.toString()); props.put("disableJmx", true);
            configuration.createFactoryConfiguration(ROUTER, routerPid, props, true); routerCreated = true;
            CamelContext camel = readyContext(context, routerPid, "groovy-v1");
            require(camel.getVersion().equals("4.20.0"), "Fork Camel version");
            evidence.put("camelVersion", camel.getVersion()); evidence.put("filePrecedencePassed", true);
            if (Boolean.getBoolean("kura.acceptance.sharedYaml")) {
                sharedYaml(context, evidence);
            }
            try (MqttClient observer = new MqttClient(broker, observerId, new MemoryPersistence())) {
                var queue = new LinkedBlockingQueue<Packet>();
                observer.setCallback(new MqttCallback() {
                    public void connectionLost(Throwable cause) { }
                    public void deliveryComplete(IMqttDeliveryToken token) { }
                    public void messageArrived(String topic, MqttMessage message) { queue.add(new Packet(topic, message)); }
                });
                MqttConnectOptions opts = new MqttConnectOptions(); opts.setUserName(USER); opts.setPassword(PASSWORD.toCharArray()); opts.setConnectionTimeout(3);
                observer.connect(opts);
                try {
                    String base = ACCOUNT + "/" + client + "/FULL_CAMEL/";
                    observer.subscribe(base + "#", 1);
                    KuraPayload payload = new KuraPayload(); payload.addMetric("nonce", nonce); payload.addMetric("sequence", 1);
                    payload.setBody(("KuraPayload-" + nonce).getBytes(StandardCharsets.UTF_8));
                    send(camel, "direct:out-" + nonce, payload);
                    KuraPayload received = receive(queue, base + "out", unmarshaller, "KuraPayload producer");
                    require(nonce.equals(received.getMetric("nonce")) && Arrays.equals(payload.getBody(), received.getBody()), "Actual payload metrics and body");
                    String text = "定制 Camel 中文 " + nonce;
                    send(camel, "direct:out-" + nonce, text);
                    require(Arrays.equals(text.getBytes(StandardCharsets.UTF_8), receive(queue, base + "out", unmarshaller, "String producer").getBody()), "String conversion");
                    byte[] binary = new byte[] {0, 1, 2, 127, -1}; send(camel, "direct:out-" + nonce, binary);
                    require(Arrays.equals(binary, receive(queue, base + "out", unmarshaller, "Binary producer").getBody()), "Binary conversion");
                    inbound(observer, queue, base, "", nonce, marshaller, unmarshaller);
                    scriptMessage(camel, queue, base, "", nonce, http, "groovy-v1", unmarshaller);
                    Files.writeString(script, script("groovy-v2"));
                    CamelContext same = readyContext(context, routerPid, "groovy-v2");
                    require(same == camel, "File hot update preserves context");
                    scriptMessage(same, queue, base, "", nonce, http, "groovy-v2", unmarshaller);
                    evidence.put("fileHotUpdateSameContext", true);
                    props.put("acceptance.noop", nonce);
                    configuration.updateConfiguration(routerPid, new HashMap<>(props), false);
                    require(readyContext(context, routerPid, "groovy-v2") == camel, "Unchanged route and script preserve context");
                    evidence.put("unchangedConfigurationSameContext", true);
                    props.put("initCode.file", ""); props.put("initCode", script("groovy-inline-v3"));
                    configuration.updateConfiguration(routerPid, new HashMap<>(props), false);
                    CamelContext inline = readyContext(context, routerPid, "groovy-inline-v3");
                    require(inline != camel && camel.isStopped(), "Inline script update replaces and stops context");
                    scriptMessage(inline, queue, base, "", nonce, http, "groovy-inline-v3", unmarshaller);
                    evidence.put("inlineScriptUpdatedViaConfiguration", true);
                    props.put("xml.data", routes(nonce, "-next"));
                    configuration.updateConfiguration(routerPid, new HashMap<>(props), false);
                    await(() -> current(context, routerPid) != null && current(context, routerPid) != inline, "Changed XML context arrival");
                    CamelContext next = readyContext(context, routerPid, "groovy-inline-v3");
                    require(next != inline && inline.isStopped(), "Changed XML replaces and stops context");
                    scriptMessage(next, queue, base, "-next", nonce, http, "groovy-inline-v3", unmarshaller);
                    inbound(observer, queue, base, "-next", nonce, marshaller, unmarshaller);
                    evidence.put("changedXmlContextLifecyclePassed", true);
                    if (Boolean.getBoolean("kura.acceptance.javascript")) {
                        props.put("scriptEngineName", "JavaScript"); props.put("initCode", javascript("javascript-v4"));
                        configuration.updateConfiguration(routerPid, new HashMap<>(props), false);
                        CamelContext js = readyContext(context, routerPid, "javascript-v4");
                        require(js != next && next.isStopped(), "JavaScript selection replaces context");
                        scriptMessage(js, queue, base, "-next", nonce, http, "javascript-v4", unmarshaller);
                        evidence.put("javascriptInitAndVertxPassed", true);
                    }
                    if (Boolean.getBoolean("kura.acceptance.browser")) {
                        configuration.updateConfiguration("org.eclipse.kura.internal.rest.provider.RestService",
                                new HashMap<>(Map.of("allowed.ports", new Integer[] {18443})), false);
                        Map<String, Object> ready = new LinkedHashMap<>();
                        ready.put("routerPid", routerPid); ready.put("version", "groovy-browser-v5");
                        ready.put("scriptEngineName", "Groovy"); ready.put("initCode", script("groovy-browser-v5"));
                        Files.writeString(root.resolve("camel-browser-ready.json"), new Gson().toJson(ready) + "\n");
                        awaitBrowser(context, routerPid, root);
                        CamelContext edited = readyContext(context, routerPid, "groovy-browser-v5");
                        require("Groovy".equals(configuration.getComponentConfiguration(routerPid)
                                .getConfigurationProperties().get("scriptEngineName")), "Browser saved selected language");
                        // The console trims the terminal newline when saving text fields.
                        require(script("groovy-browser-v5").stripTrailing().equals(configuration.getComponentConfiguration(routerPid)
                                .getConfigurationProperties().get("initCode")), "Browser saved script after console newline normalization");
                        scriptMessage(edited, queue, base, "-next", nonce, http, "groovy-browser-v5", unmarshaller);
                        evidence.put("browserEditedScriptExecuted", true);
                    }
                    String dsl = System.getProperty("kura.acceptance.dsl", "");
                    List<String> formats = dsl.equals("both") ? List.of("java", "yaml")
                            : dsl.isEmpty() ? List.of() : List.of(dsl);
                    for (String format : formats) {
                        evidence.put("dslUnderTest", format);
                        CamelContext previous = current(context, routerPid);
                        String version = "groovy-dsl-" + format, suffix = "-" + format;
                        props.put("file.extension", format); props.put("xml.data", dslRoutes(format, nonce, suffix));
                        props.put("scriptEngineName", "Groovy"); props.put("initCode", script(version));
                        configuration.updateConfiguration(routerPid, new HashMap<>(props), false);
                        CamelContext loaded = readyContext(context, routerPid, version);
                        require(loaded != previous && previous.isStopped(), "Changed DSL replaces and stops context");
                        String body = "dsl-" + format + "-" + nonce;
                        send(loaded, "direct:out-" + nonce, body);
                        require(Arrays.equals(body.getBytes(StandardCharsets.UTF_8), receive(queue, base + "out" + suffix,
                                unmarshaller, "Actual " + format + " DSL producer").getBody()), "DSL producer exact body");
                        scriptMessage(loaded, queue, base, suffix, nonce, http, version, unmarshaller);
                        inbound(observer, queue, base, suffix, nonce, marshaller, unmarshaller);
                        evidence.put(format + "DslConfigurationAndDeliveryPassed", true);
                    }
                } finally { if (observer.isConnected()) observer.disconnect(); }
            }
            evidence.put("routerPid", routerPid); evidence.put("cloudPid", cloudPid); evidence.put("clientId", client); evidence.put("observerId", observerId);
            evidence.put("bundleCount", context.getBundles().length); evidence.put("packets", packets);
            evidence.put("browserEditorSaveExecuted", Boolean.getBoolean("kura.acceptance.browser"));
        } catch (Throwable error) { failure = error; }
        finally {
            try {
                if (routerCreated) {
                    configuration.deleteFactoryConfiguration(routerPid, true);
                    await(() -> current(context, routerPid) == null, "Router service cleanup");
                    await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive()
                            && t.getName().equals("FileChangeWatcher-init-" + nonce + ".groovy")), "File watcher cleanup");
                }
                if (manager != null && manager.isConnected()) manager.disconnect();
                if (cloudCreated) { factory.deleteConfiguration(cloudPid); require(!factory.getManagedCloudConnectionPids().contains(cloudPid), "Cloud stack cleanup"); }
                if (databaseCreated) configuration.deleteFactoryConfiguration("org.eclipse.kura.db.H2DbService", true);
                evidence.put("cleanupPassed", true);
            } catch (Throwable cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            for (var ref : references) context.ungetService(ref);
        }
        evidence.put("passed", failure == null);
        if (failure != null) { evidence.put("error", failure.toString()); failure.printStackTrace(); }
        try { Files.writeString(root.resolve("camel-probe-result.json"), new Gson().toJson(evidence) + "\n"); }
        catch (Exception error) { error.printStackTrace(); }
    }

    private static void sharedYaml(BundleContext context, Map<String, Object> evidence) throws Exception {
        Bundle camel = Arrays.stream(context.getBundles()).filter(b -> b.getSymbolicName().equals("org.eclipse.kura.camel")).findFirst().orElseThrow();
        Bundle opcua = Arrays.stream(context.getBundles()).filter(b -> b.getSymbolicName()
                .equals("com.yofc.iot.yofc-iot-apps-opcuaserver")).findFirst().orElseThrow();
        Class<?> engine = camel.loadClass("org.snakeyaml.engine.v2.api.LoadSettings");
        require(engine == opcua.loadClass(engine.getName()), "Camel and YOFC share the same Engine class");
        Bundle owner = FrameworkUtil.getBundle(engine);
        require(owner != null && owner.getSymbolicName().equals("org.snakeyaml.engine")
                && owner.getVersion().toString().equals("3.0.1"), "Public SnakeYAML Engine provider");
        Class<?> jsonFactory = opcua.loadClass("tools.jackson.core.TokenStreamFactory");
        Object yamlFactory = opcua.loadClass("tools.jackson.dataformat.yaml.YAMLFactory").getConstructor().newInstance();
        Class<?> mapperClass = opcua.loadClass("tools.jackson.databind.ObjectMapper");
        Object mapper = mapperClass.getConstructor(jsonFactory).newInstance(yamlFactory);
        var read = mapperClass.getMethod("readValue", String.class, Class.class);
        Object value = read.invoke(mapper, "public-engine: true\nmessage: shared-中文\n", Map.class);
        require(value instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("public-engine"))
                && "shared-中文".equals(map.get("message")), "YOFC actual YAML parser reads public Engine");
        String encoded = (String) mapperClass.getMethod("writeValueAsString", Object.class).invoke(mapper, value);
        require(value.equals(read.invoke(mapper, encoded, Map.class)), "YOFC YAML serialization round trip");
        evidence.put("sharedYamlEngine", Map.of("symbolicName", owner.getSymbolicName(), "version", owner.getVersion().toString(),
                "sameClassIdentity", true, "yofcYamlRoundTripPassed", true));
    }
    private static String script(String version) {
        return """
                import org.apache.camel.Processor
                import org.apache.camel.Exchange
                import java.util.concurrent.TimeUnit
                assert camelContext.registry.lookupByName('vertx').is(vertx)
                assert camelContext.registry.lookupByName('webClient').is(webClient)
                // Camel resolves process ref once. The stable dispatcher looks up
                // the newly rebound script processor for each incoming exchange.
                if (camelContext.registry.lookupByName('probeDispatcher') == null) {
                    rebind('probeDispatcher', { Exchange exchange ->
                        ((Processor) exchange.context.registry.lookupByName('probeProcessor')).process(exchange)
                    } as Processor)
                }
                rebind('probeProcessor', { Exchange exchange ->
                    def response = webClient.getAbs(exchange.message.getHeader('acceptance.url', String))
                        .send().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS)
                    assert response.statusCode() == 200
                    exchange.message.body = '%s|' + response.bodyAsString()
                } as Processor)
                rebind('acceptance.script.version', '%s')
                """.formatted(version, version);
    }
    private static String routes(String nonce, String suffix) {
        return """
                <routes xmlns="http://camel.apache.org/schema/spring">
                  <route id="out"><from uri="direct:out-%s"/><to uri="acceptance-cloud:FULL_CAMEL/out%s?qos=1"/></route>
                  <route id="script"><from uri="direct:script-%s"/><process ref="probeDispatcher"/><to uri="acceptance-cloud:FULL_CAMEL/script%s?qos=1"/></route>
                  <route id="in"><from uri="acceptance-cloud:FULL_CAMEL/in%s"/><removeHeaders pattern="CamelKuraCloud.*"/><to uri="acceptance-cloud:FULL_CAMEL/echo%s?qos=1"/></route>
                </routes>
                """.formatted(nonce, suffix, nonce, suffix, suffix, suffix);
    }
    private static String dslRoutes(String format, String nonce, String suffix) {
        return switch (format) {
        case "java" -> """
                import org.apache.camel.builder.RouteBuilder;
                public class ForkAcceptanceRoutes extends RouteBuilder {
                    public void configure() {
                        from("direct:out-%s").routeId("out").to("acceptance-cloud:FULL_CAMEL/out%s?qos=1");
                        from("direct:script-%s").routeId("script").process("probeDispatcher")
                            .to("acceptance-cloud:FULL_CAMEL/script%s?qos=1");
                        from("acceptance-cloud:FULL_CAMEL/in%s").routeId("in").removeHeaders("CamelKuraCloud.*")
                            .to("acceptance-cloud:FULL_CAMEL/echo%s?qos=1");
                    }
                }
                """.formatted(nonce, suffix, nonce, suffix, suffix, suffix);
        case "yaml" -> """
                - route:
                    id: out
                    from:
                      uri: direct:out-%s
                      steps:
                        - to: acceptance-cloud:FULL_CAMEL/out%s?qos=1
                - route:
                    id: script
                    from:
                      uri: direct:script-%s
                      steps:
                        - process:
                            ref: probeDispatcher
                        - to: acceptance-cloud:FULL_CAMEL/script%s?qos=1
                - route:
                    id: in
                    from:
                      uri: acceptance-cloud:FULL_CAMEL/in%s
                      steps:
                        - removeHeaders:
                            pattern: CamelKuraCloud.*
                        - to: acceptance-cloud:FULL_CAMEL/echo%s?qos=1
                """.formatted(nonce, suffix, nonce, suffix, suffix, suffix);
        default -> throw new IllegalArgumentException(format);
        };
    }
    private static String javascript(String version) {
        return """
                var Processor = Java.extend(Java.type('org.apache.camel.Processor'));
                var TimeUnit = Java.type('java.util.concurrent.TimeUnit');
                if (camelContext.getRegistry().lookupByName('vertx') !== vertx) throw new Error('Vertx binding');
                if (camelContext.getRegistry().lookupByName('webClient') !== webClient) throw new Error('WebClient binding');
                rebind.accept('probeDispatcher', new Processor({process: function(exchange) {
                    exchange.getContext().getRegistry().lookupByName('probeProcessor').process(exchange);
                }}));
                rebind.accept('probeProcessor', new Processor({process: function(exchange) {
                    var response = webClient.getAbs(String(exchange.getMessage().getHeader('acceptance.url')))
                        .send().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    if (response.statusCode() !== 200) throw new Error('HTTP status');
                    exchange.getMessage().setBody('%s|' + response.bodyAsString());
                }}));
                rebind.accept('acceptance.script.version', '%s');
                """.formatted(version, version);
    }
    private static void awaitBrowser(BundleContext context, String pid, Path root) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (true) {
            require(System.nanoTime() < deadline, "Browser edit/save deadline");
            CamelContext value = current(context, pid);
            if (value != null && value.isStarted() && "groovy-browser-v5".equals(value.getRegistry().lookupByName("acceptance.script.version"))
                    && Files.isRegularFile(root.resolve("browser-actions.json"))) return;
            Thread.sleep(100);
        }
    }
    private static void send(CamelContext context, String endpoint, Object body) throws Exception {
        try (var producer = context.createProducerTemplate()) { producer.sendBody(endpoint, body); }
    }
    private void scriptMessage(CamelContext context, LinkedBlockingQueue<Packet> queue, String base, String suffix,
            String nonce, String http, String version, Unmarshaller decoder) throws Exception {
        try (var producer = context.createProducerTemplate()) {
            producer.sendBodyAndHeader("direct:script-" + nonce, "request", "acceptance.url", http + "/" + nonce + "/" + version);
        }
        var value = receive(queue, base + "script" + suffix, decoder, "Vertx WebClient " + version + suffix);
        require((version + "|acceptance-http:/" + nonce + "/" + version).equals(new String(value.getBody(), StandardCharsets.UTF_8)), "Actual script and Vertx HTTP response");
    }
    private void inbound(MqttClient observer, LinkedBlockingQueue<Packet> queue, String base, String suffix,
            String nonce, Marshaller encoder, Unmarshaller decoder) throws Exception {
        KuraPayload value = new KuraPayload(); value.addMetric("nonce", nonce + suffix); value.setBody(("incoming-" + nonce + suffix).getBytes(StandardCharsets.UTF_8));
        observer.publish(base + "in" + suffix, encoder.marshal(value).getBytes(StandardCharsets.UTF_8), 1, false);
        var reply = receive(queue, base + "echo" + suffix, decoder, "Real cloud consumer callback" + suffix);
        require((nonce + suffix).equals(reply.getMetric("nonce")) && Arrays.equals(value.getBody(), reply.getBody()), "Consumer exact correlation");
    }
    private KuraPayload receive(LinkedBlockingQueue<Packet> queue, String topic, Unmarshaller decoder, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Packet packet = queue.poll(250, TimeUnit.MILLISECONDS);
            if (packet == null || !topic.equals(packet.topic())) continue;
            require(packet.message().getQos() == 1 && !packet.message().isRetained(), "QoS/retain " + label);
            packets.add(Map.of("case", label, "topic", topic, "qos", packet.message().getQos(), "retained", packet.message().isRetained(),
                    "payloadSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packet.message().getPayload()))));
            return decoder.unmarshal(new String(packet.message().getPayload(), StandardCharsets.UTF_8), KuraPayload.class);
        }
        throw new IllegalStateException("Timed out: " + label + " on " + topic);
    }
    private static CamelContext current(BundleContext context, String pid) {
        try {
            var refs = context.getServiceReferences(CamelContext.class, "(camel.context.id=" + pid + ")");
            if (refs.isEmpty()) return null;
            var ref = refs.iterator().next(); var value = context.getService(ref); context.ungetService(ref); return value;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static CamelContext readyContext(BundleContext context, String pid, String version) throws Exception {
        await(() -> { CamelContext value = current(context, pid); return value != null && value.isStarted()
                && version.equals(value.getRegistry().lookupByName("acceptance.script.version"))
                && value.getRoutes().size() == 3 && value.getRoutes().stream().allMatch(r -> {
                    var status = value.getRouteController().getRouteStatus(r.getId()); return status != null && status.isStarted();
                }); }, "Actual router/script " + version);
        return current(context, pid);
    }
    private <T> T service(BundleContext context, Class<T> type, String filter) throws Exception {
        await(() -> { try { return !context.getServiceReferences(type, filter).isEmpty(); } catch (Exception e) { throw new IllegalStateException(e); } }, "Service " + type.getName());
        var ref = context.getServiceReferences(type, filter).iterator().next(); references.add(ref);
        T value = context.getService(ref); require(value != null, "Available " + type.getName()); return value;
    }
    private static void await(BooleanSupplier condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) { require(System.nanoTime() < deadline, "Timed out: " + label); Thread.sleep(25); }
    }
    private static void require(boolean condition, String label) { if (!condition) throw new IllegalStateException(label); }
    private record Packet(String topic, MqttMessage message) { }
}
