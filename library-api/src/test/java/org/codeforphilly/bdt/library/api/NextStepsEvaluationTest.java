package org.codeforphilly.bdt.library.api;

import org.junit.jupiter.api.Test;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.internal.io.ResourceFactory;

import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NextStepsEvaluationTest {

    @Test
    void evaluatesFeelEscapesAndPreservesOptionalNulls() {
        DMNRuntime runtime = runtime("", """
            {eligible: {body: "Apply\\n\\nUse \\\"Search for a property\\\".", helpPhone: null},
             ineligible: {note: null}}
            """);
        Map<String, Object> content = ModelRegistry.evaluateNextSteps(runtime, runtime.getModels().getFirst());
        Map<?, ?> eligible = (Map<?, ?>) content.get("eligible");
        assertEquals("Apply\n\nUse \"Search for a property\".", eligible.get("body"));
        assertNull(eligible.get("helpPhone"));
        assertTrue(((Map<?, ?>) content.get("ineligible")).containsKey("note"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsKeyOrderAndTopLevelNullsInAnUnmodifiableCopy() {
        DMNRuntime runtime = runtime("", """
            {eligible: {applyUrl: "a", helpUrl: "b", helpPhone: "c", body: "d"}, ineligible: null}
            """);
        Map<String, Object> content = ModelRegistry.evaluateNextSteps(runtime, runtime.getModels().getFirst());
        assertEquals(List.of("eligible", "ineligible"), List.copyOf(content.keySet()));
        assertNull(content.get("ineligible"));
        Map<String, Object> eligible = (Map<String, Object>) content.get("eligible");
        assertEquals(List.of("applyUrl", "helpUrl", "helpPhone", "body"), List.copyOf(eligible.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> eligible.put("body", "changed"));
    }

    @Test
    void refusesContentThatDependsOnAnswers() {
        DMNRuntime runtime = runtime("""
            <informationRequirement><requiredInput href="#situation"/></informationRequirement>
            """, "{eligible: {body: situation}, ineligible: {note: null}}");
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> ModelRegistry.evaluateNextSteps(runtime, runtime.getModels().getFirst()));
        assertTrue(error.getMessage().contains("no information requirements"));
    }

    @Test
    void refusesANonContextResult() {
        DMNRuntime runtime = runtime("", "42");
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> ModelRegistry.evaluateNextSteps(runtime, runtime.getModels().getFirst()));
        assertTrue(error.getMessage().contains("must return a context"));
    }

    @Test
    void wrapsUnexpectedEvaluationFailuresSoStartupFails() {
        DMNRuntime runtime = runtime("", "{eligible: null, ineligible: null}");
        DMNRuntime failing = (DMNRuntime) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {DMNRuntime.class}, (proxy, method, args) -> {
                if (method.getName().equals("evaluateByName")) {
                    throw new IllegalArgumentException("engine failure");
                }
                return method.invoke(runtime, args);
            });
        var error = assertThrows(ModelRegistry.InvalidNextStepsException.class,
            () -> ModelRegistry.evaluateNextSteps(failing, runtime.getModels().getFirst()));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    private DMNRuntime runtime(String requirements, String expression) {
        String xml = """
            <definitions xmlns="https://www.omg.org/spec/DMN/20240513/MODEL/"
                         id="test" name="ContentTest" namespace="https://bdt.test/next-steps">
              <inputData id="situation" name="situation">
                <variable name="situation" typeRef="string"/>
              </inputData>
              <decision id="nextSteps" name="nextSteps">
                <variable name="nextSteps" typeRef="Any"/>
                %s
                <literalExpression><text>%s</text></literalExpression>
              </decision>
            </definitions>
            """.formatted(requirements, expression);
        return DMNRuntimeBuilder.fromDefaults().buildConfiguration()
            .fromResources(List.of(ResourceFactory.newReaderResource(new StringReader(xml))))
            .getOrElseThrow(error -> new IllegalStateException("Could not compile test DMN", error));
    }
}
