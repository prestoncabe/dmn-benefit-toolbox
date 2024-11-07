package org.prestoncabe.functions;

import java.util.Map;

public class OpenFiscaService {
    public static Boolean calculateOpenFiscaVariable(String variableName, Map<String, Object> inputs) {
        // replace this code with actual code that calls a running instance of the openFisca API.
        if (inputs.get("testField") == null) {
            return true;
        }
        return (Integer) inputs.get("testField") < 500;

    }    
}