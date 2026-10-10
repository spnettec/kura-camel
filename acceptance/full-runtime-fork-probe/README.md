# Complete Mac Camel fork acceptance

This opt-in helper is outside the default reactor and production packages. It
uses the complete stopped Mac runtime in a fresh owned profile, plus a separate
authenticated loopback MQTT broker and an independent HTTP server. All bundle
dependencies are provided by the application. Handwritten metadata is preserved;
optional bundle overlays allow a production repair to be tested without rebuilding
or modifying the stopped runtime.

`--template-profile` supplies the copied configuration and keystores. It may
differ from the runtime's declared `kura.home`. The helper reads that home from
`jvm.args` and relocates both source paths to the new owned profile, retaining
the original ownership-marker check. `runtime-home-relocation.json` records the
three paths.

The actual configurable router, ConfigurationService, Camel 4.22.1, Kapua cloud stack,
Groovy/Vertx WebClient bindings, rebind helper and file watcher execute here.
Assertions cover KuraPayload/string/binary production routing, inbound cloud
callbacks, file precedence, script hot update on the same context, inline script
configuration update, unchanged configuration and changed XML context lifecycle.
`--javascript` additionally executes the separate provider bundle's GraalJS engine,
using `Java.extend` for a Camel Processor and the injected Vertx/rebind objects.

`--dsl java`, `--dsl yaml` or `--dsl both` also updates the actual configurable
router to the selected DSL. Each format must replace and stop the old context,
deliver a producer payload, execute the custom Groovy/Vertx script and receive an
independent MQTT message through the real cloud callback. This tests the fork's
OSGi-aware Java compiler and YAML bundle dependencies in the complete runtime.

`--shared-yaml` checks that Camel and the YOFC OPC UA Server bundle resolve the
same SnakeYAML Engine class from `org.snakeyaml.engine:3.0.1`, and executes the
server bundle's actual Jackson YAML read/write path. `--additional-bundle PATH`
adds a copied standalone library to the owned configuration; repeatable
`--bundle-overlay SYMBOLIC_NAME=PATH` can replace another existing test consumer.

`--vertx` uses YOFC's actual registered Vertx 5.2.1 service for EventBus
request/reply and HTTP. On the required JDK21 it also requires virtual-thread
availability and confirms the HTTP handler actually runs on a virtual thread.
This catches a wrapper that retains Java21 classes but loses `Multi-Release`.

`--browser` pauses after writing `camel-browser-ready.json`. In the real console,
select its router, switch the language to Groovy, paste the provided initCode into
ACE, validate, and apply the configuration. Record the completed UI actions and
screenshot in `browser-actions.json` only after the browser confirms saving. The
probe then checks the actual saved language/script (including the console's
terminal-newline normalization), executes the script, and requires an independent
HTTP response and MQTT delivery. A ConfigurationService update is not counted as
a browser operation. The existing local HTTP console can be used; the fresh owned
profile also receives a localhost HTTPS certificate, without changing browser or
personal trust stores.

Build with Maven 3.10/JDK21 and the migration cache. `run.py --help` describes the
explicit paths; `--broker-module` points to the already built test-only
`kura-cloud/acceptance/full-runtime-protocol-probe` broker, not a production sibling
dependency. The archive must be new and outside runtime/personal profiles. Logs,
owned profiles, packets and failed attempts are retained. Only owned processes
are terminated.

`--camel-bundle` and `--camel-xml-bundle` copy the supplied artifacts into the
archive and replace their URIs only in the new configuration. The template's
plugins, configuration, profile and keystores remain untouched. Results include
overlay, helper, source and log hashes. The browser run has a ten-minute UI deadline.
