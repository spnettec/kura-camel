/* SPDX-License-Identifier: EPL-2.0 */
package org.eclipse.kura.camel.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.HashMap;
import java.util.Map;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.SimpleBindings;
import javax.script.SimpleScriptContext;
import org.junit.jupiter.api.Test;

/** Real separately supplied engines: no synthetic ScriptEngine/factory. */
public class ScriptRunnerInteropTest {
    private static final String JS = "rebind.accept('value', String(Java.type('java.lang.StringBuilder').class.getSimpleName()));";

    public static final class Rebind {
        final Map<String, String> values = new HashMap<>();
        public void accept(String name, String value) { values.put(name, value); }
    }

    @Test
    void javascriptCallsInjectedJavaObjectWithBindings() throws Exception {
        Rebind rebind = new Rebind();
        Bindings bindings = new SimpleBindings(); bindings.put("rebind", rebind);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            ScriptRunner.create(getClass().getClassLoader(), "JavaScript", JS).run(bindings);
            assertEquals("StringBuilder", rebind.values.get("value"));
            assertSame(original, Thread.currentThread().getContextClassLoader());
        } finally { closeContext(bindings); }
    }

    @Test
    void javascriptCallsInjectedJavaObjectWithScriptContext() throws Exception {
        Rebind rebind = new Rebind();
        Bindings bindings = new SimpleBindings(); bindings.put("rebind", rebind);
        SimpleScriptContext context = new SimpleScriptContext(); context.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try {
            ScriptRunner.create(getClass().getClassLoader(), "JavaScript", JS).run(context);
            assertEquals("StringBuilder", rebind.values.get("value"));
            assertSame(original, Thread.currentThread().getContextClassLoader());
        } finally { closeContext(bindings); }
    }

    @Test
    void groovyUsesItsOwnEngineAndInjectedJavaObject() throws Exception {
        Rebind rebind = new Rebind();
        Bindings bindings = new SimpleBindings(); bindings.put("rebind", rebind);
        ScriptRunner.create(getClass().getClassLoader(), "Groovy", "rebind.accept('value', 'groovy')").run(bindings);
        assertEquals("groovy", rebind.values.get("value"));
    }

    private static void closeContext(Bindings bindings) throws Exception {
        if (bindings.get("polyglot.context") instanceof AutoCloseable context) context.close();
    }
}
