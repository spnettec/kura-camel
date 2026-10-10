# Complete Mac Camel fork acceptance

This opt-in helper is outside the default reactor and production packages. It
uses the complete stopped Mac runtime in a fresh owned profile, plus a separate
authenticated loopback MQTT broker and an independent HTTP server. All bundle
dependencies are provided by the application; production sources and handwritten
metadata remain unchanged.

The actual XML router, ConfigurationService, Camel 4.20, Kapua cloud stack,
Groovy/Vertx WebClient bindings, rebind helper and file watcher execute here.
Assertions cover KuraPayload/string/binary production routing, inbound cloud
callbacks, file precedence, script hot update on the same context, inline script
configuration update, unchanged configuration and changed XML context lifecycle.
The control panel ACE editor and browser save operation require separate UI
acceptance; a ConfigurationService update is not counted as a browser operation.

Build with Maven 3.10/JDK21 and the migration cache. `run.py --help` describes the
explicit paths; `--broker-module` points to the already built test-only
`kura-cloud/acceptance/full-runtime-protocol-probe` broker, not a production sibling
dependency. The archive must be new and outside runtime/personal profiles. Logs,
owned profiles, packets and failed attempts are retained. Only owned processes
are terminated.
