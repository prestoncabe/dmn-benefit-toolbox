package org.prestoncabe.generators;

import org.kie.dmn.model.v1_2.*;
import org.kie.dmn.model.v1_2.dmndi.*;
import org.kie.dmn.backend.marshalling.v1x.DMNMarshallerFactory;
import javax.xml.namespace.QName;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

public class SimpleKogitoDMNExample {
    public static void main(String[] args) {
        // Load the sample file to see how it structures QNames
        try {
            // Generate unique IDs
            String modelId = "_" + UUID.randomUUID().toString().toUpperCase();
            String modelName = "discount_calculator";
            String namespaceURI = "https://kiegroup.org/dmn/" + UUID.randomUUID().toString();

            // Create the DMN model definition
            TDefinitions definitions = new TDefinitions();
            definitions.setId(modelId);
            definitions.setName(modelName);
            definitions.setNamespace(namespaceURI);
            
            // Set required namespaces
            definitions.setTypeLanguage("http://www.omg.org/spec/DMN/20180521/FEEL/");
            definitions.getNsContext().put("dmn", "http://www.omg.org/spec/DMN/20180521/MODEL/");
            definitions.getNsContext().put("feel", "http://www.omg.org/spec/DMN/20180521/FEEL/");
            definitions.getNsContext().put("kie", "http://www.drools.org/kie/dmn/1.2");
            definitions.getNsContext().put("dmndi", "http://www.omg.org/spec/DMN/20180521/DMNDI/");
            definitions.getNsContext().put("di", "http://www.omg.org/spec/DMN/20180521/DI/");
            definitions.getNsContext().put("dc", "http://www.omg.org/spec/DMN/20180521/DC/");

            // Create input data node
            TInputData purchaseAmountInput = new TInputData();
            String purchaseInputId = "_" + UUID.randomUUID().toString().toUpperCase();
            purchaseAmountInput.setId(purchaseInputId);
            purchaseAmountInput.setName("Purchase Amount");
            
            // Create input variable
            TInformationItem purchaseVariable = new TInformationItem();
            purchaseVariable.setId("_" + UUID.randomUUID().toString().toUpperCase());
            purchaseVariable.setName("Purchase Amount");
            purchaseVariable.setTypeRef(new QName("number"));
            purchaseAmountInput.setVariable(purchaseVariable);

            // Create decision node
            TDecision discountDecision = new TDecision();
            String decisionId = "_" + UUID.randomUUID().toString().toUpperCase();
            discountDecision.setId(decisionId);
            discountDecision.setName("Discount Amount");

            // Create decision variable
            TInformationItem decisionVariable = new TInformationItem();
            decisionVariable.setId("_" + UUID.randomUUID().toString().toUpperCase());
            decisionVariable.setName("Discount Amount");
            decisionVariable.setTypeRef(new QName("number"));
            discountDecision.setVariable(decisionVariable);

            // Create literal expression
            TLiteralExpression expression = new TLiteralExpression();
            String expressionId = "_" + UUID.randomUUID().toString().toUpperCase();
            expression.setId(expressionId);
            expression.setText("if Purchase Amount > 100 then Purchase Amount * 0.1 else 0");
            discountDecision.setExpression(expression);

            // Create information requirement
            TInformationRequirement requirement = new TInformationRequirement();
            String requirementId = "_" + UUID.randomUUID().toString().toUpperCase();
            requirement.setId(requirementId);
            TDMNElementReference reference = new TDMNElementReference();
            reference.setHref("#" + purchaseInputId);
            requirement.setRequiredInput(reference);
            discountDecision.getInformationRequirement().add(requirement);

            // Add nodes to definitions
            definitions.getDrgElement().add(purchaseAmountInput);
            definitions.getDrgElement().add(discountDecision);

            // Marshal to XML
            String dmnXML = DMNMarshallerFactory.newDefaultMarshaller().marshal(definitions);

            // Add DMNDI using string manipulation
            String dmndiXml = createDMNDI(purchaseInputId, decisionId, requirementId);
            dmnXML = dmnXML.replace("</dmn:definitions>", dmndiXml + "\n</dmn:definitions>");

            // Save the file
            Path resourcesPath = Paths.get("src", "main", "resources");
            Files.createDirectories(resourcesPath);
            File dmnFile = new File(resourcesPath.toFile(), "discount-calculator.dmn");
            
            try (FileWriter writer = new FileWriter(dmnFile)) {
                writer.write(dmnXML);
                System.out.println("DMN file saved to: " + dmnFile.getAbsolutePath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static String createDMNDI(String inputId, String decisionId, String requirementId) {
        StringBuilder dmndi = new StringBuilder();
        
        dmndi.append("  <dmndi:DMNDI>\n");
        dmndi.append("    <dmndi:DMNDiagram id=\"_" + UUID.randomUUID().toString().toUpperCase() + "\" name=\"DRG\">\n");
        
        // Input shape
        dmndi.append("      <dmndi:DMNShape id=\"dmnshape-" + inputId + "\" dmnElementRef=\"" + inputId + "\" isCollapsed=\"false\">\n");
        dmndi.append("        <dmndi:DMNStyle>\n");
        dmndi.append("          <dmndi:FillColor red=\"255\" green=\"255\" blue=\"255\"/>\n");
        dmndi.append("          <dmndi:StrokeColor red=\"0\" green=\"0\" blue=\"0\"/>\n");
        dmndi.append("          <dmndi:FontColor red=\"0\" green=\"0\" blue=\"0\"/>\n");
        dmndi.append("        </dmndi:DMNStyle>\n");
        dmndi.append("        <dc:Bounds x=\"100\" y=\"200\" width=\"100\" height=\"50\"/>\n");
        dmndi.append("        <dmndi:DMNLabel/>\n");
        dmndi.append("      </dmndi:DMNShape>\n");
        
        // Decision shape
        dmndi.append("      <dmndi:DMNShape id=\"dmnshape-" + decisionId + "\" dmnElementRef=\"" + decisionId + "\" isCollapsed=\"false\">\n");
        dmndi.append("        <dmndi:DMNStyle>\n");
        dmndi.append("          <dmndi:FillColor red=\"255\" green=\"255\" blue=\"255\"/>\n");
        dmndi.append("          <dmndi:StrokeColor red=\"0\" green=\"0\" blue=\"0\"/>\n");
        dmndi.append("          <dmndi:FontColor red=\"0\" green=\"0\" blue=\"0\"/>\n");
        dmndi.append("        </dmndi:DMNStyle>\n");
        dmndi.append("        <dc:Bounds x=\"100\" y=\"100\" width=\"100\" height=\"50\"/>\n");
        dmndi.append("        <dmndi:DMNLabel/>\n");
        dmndi.append("      </dmndi:DMNShape>\n");
        
        // Edge
        dmndi.append("      <dmndi:DMNEdge id=\"dmnedge-" + requirementId + "\" dmnElementRef=\"" + requirementId + "\">\n");
        dmndi.append("        <di:waypoint x=\"150\" y=\"200\"/>\n");
        dmndi.append("        <di:waypoint x=\"150\" y=\"150\"/>\n");
        dmndi.append("      </dmndi:DMNEdge>\n");
        
        dmndi.append("    </dmndi:DMNDiagram>\n");
        dmndi.append("  </dmndi:DMNDI>");
        
        return dmndi.toString();
    }
}