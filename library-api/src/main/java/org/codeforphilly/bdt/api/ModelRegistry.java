package org.codeforphilly.bdt.api;

import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.kogito.decision.DecisionModels;
import org.kie.kogito.dmn.AbstractDecisionModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import io.quarkus.runtime.Startup;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Registry that automatically discovers all DMN models at runtime using reflection.
 * Provides mapping from model names to their namespaces and metadata.
 */
@Startup
@ApplicationScoped
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    @Inject
    org.kie.kogito.Application application;

    /**
     * Cached model registry built once at startup.
     * Volatile to ensure visibility across threads.
     */
    private volatile Map<String, ModelInfo> cachedModels;

    /**
     * Cached path-to-model mapping built once at startup.
     * Volatile to ensure visibility across threads.
     */
    private volatile Map<String, ModelInfo> cachedModelsByPath;

    /**
     * Get metadata for a specific model by name.
     * Returns cached result built at startup.
     *
     * @param modelName the DMN model name (e.g., "PersonMinAge", "PhlHomesteadExemption")
     * @return ModelInfo containing namespace and available services, or null if not found
     */
    public ModelInfo getModelInfo(String modelName) {
        return cachedModels.get(modelName);
    }

    /**
     * Get metadata for a specific model by path.
     * Returns cached result built at startup.
     *
     * @param path the relative path (e.g., "age/PersonMinAge", "checks/test/TestOne")
     * @return ModelInfo or null if not found
     */
    public ModelInfo getModelInfoByPath(String path) {
        return cachedModelsByPath.get(path);
    }

    /**
     * Get all discovered DMN models.
     * Returns cached results built once at startup.
     * Hot-reload compatibility: Quarkus dev mode reloads the entire class, triggering cache rebuild.
     *
     * @return map of model name to ModelInfo
     */
    public Map<String, ModelInfo> getAllModels() {
        return cachedModels;
    }

    /**
     * Get the compiled DMN models used by the running application.
     *
     * Kogito 10 generates JSON schemas from these runtime models rather than
     * emitting the legacy aggregate dmnDefinitions.json resource.
     */
    List<DMNModel> getDmnModels() {
        DecisionModels decisionModels = application.get(DecisionModels.class);
        DMNRuntime dmnRuntime = getDMNRuntime(decisionModels);
        return dmnRuntime == null ? List.of() : List.copyOf(dmnRuntime.getModels());
    }

    /**
     * Build the model registry cache.
     * This is called once during @PostConstruct initialization.
     * Scans the classpath for DMN files and builds the model metadata registry.
     */
    private void buildModelCache() {
        Map<String, ModelInfo> modelMap = new HashMap<>();
        Map<String, ModelInfo> modelsByPath = new HashMap<>();

        try {
            // Build mappings of model name -> file path and model name -> description
            Map<String, String> modelDescriptions = new HashMap<>();
            Map<String, List<BenefitCheckInfo>> benefitChecks = new HashMap<>();
            Map<String, String> modelNameToPath = scanDMNFiles(modelDescriptions, benefitChecks);

            DecisionModels decisionModels = application.get(DecisionModels.class);
            DMNRuntime dmnRuntime = getDMNRuntime(decisionModels);

            if (dmnRuntime == null) {
                log.warn("DMNRuntime is null, no models discovered");
                this.cachedModels = modelMap;
                this.cachedModelsByPath = modelsByPath;
                return;
            }

            List<DMNModel> models = dmnRuntime.getModels();
            log.debug("Discovered {} DMN models", models.size());

            for (DMNModel model : models) {
                String modelName = model.getName();
                String namespace = model.getNamespace();

                List<String> decisionServices = model.getDecisionServices().stream()
                    .map(ds -> ds.getName())
                    .collect(Collectors.toList());

                List<String> decisions = model.getDecisions().stream()
                    .map(d -> d.getName())
                    .collect(Collectors.toList());

                // Get the file path and description for this model
                String path = modelNameToPath.getOrDefault(modelName, modelName);
                String description = modelDescriptions.get(modelName);

                ModelInfo info = new ModelInfo(namespace, modelName, decisionServices, decisions, path, description,
                    benefitChecks.getOrDefault(modelName, List.of()));
                modelMap.put(modelName, info);
                modelsByPath.put(path, info);

                log.debug("Registered model: {} (namespace: {}, services: {}, decisions: {}, path: {}, description: {})",
                    modelName, namespace, decisionServices.size(), decisions.size(), path,
                    description != null ? description.substring(0, Math.min(30, description.length())) + "..." : "null");
            }

            log.info("Model registry cache built: {} models registered", modelMap.size());

        } catch (Exception e) {
            log.error("Error building DMN model cache", e);
        }

        // Assign to volatile fields for thread-safe publication
        this.cachedModels = modelMap;
        this.cachedModelsByPath = modelsByPath;
    }

    /**
     * Initializes the model registry and validates that all DMN model names are unique.
     * This runs at application startup and on every hot reload.
     * Builds the model cache FIRST, then validates for duplicate model names.
     *
     * @throws IllegalStateException if duplicate model names are detected
     */
    @PostConstruct
    public void initialize() {
        // Build the cache first
        buildModelCache();

        // Then validate model names
        try {
            // Get models directly from DMNRuntime BEFORE deduplication
            DecisionModels decisionModels = application.get(DecisionModels.class);
            DMNRuntime dmnRuntime = getDMNRuntime(decisionModels);

            if (dmnRuntime == null) {
                log.warn("DMNRuntime is null, skipping validation");
                return;
            }

            List<DMNModel> models = dmnRuntime.getModels();
            Map<String, String> namespaceToPath = scanDMNFilesForNamespaces();
            Map<String, List<String>> nameToFiles = new HashMap<>();

            // Check all models from runtime (including duplicates)
            for (DMNModel model : models) {
                String modelName = model.getName();
                String namespace = model.getNamespace();
                String path = namespaceToPath.getOrDefault(namespace, "unknown");
                String shortNamespace = namespace.substring(namespace.lastIndexOf('/') + 1);

                nameToFiles.computeIfAbsent(modelName, k -> new ArrayList<>())
                           .add(path + " (namespace: " + shortNamespace + ")");
            }

            // Find and report duplicates
            List<String> duplicateErrors = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : nameToFiles.entrySet()) {
                if (entry.getValue().size() > 1) {
                    duplicateErrors.add(
                        "  - Model name '" + entry.getKey() + "' found in:\n    " +
                        String.join("\n    ", entry.getValue())
                    );
                }
            }

            if (!duplicateErrors.isEmpty()) {
                String errorMessage = "Duplicate DMN model names detected:\n" +
                                    String.join("\n", duplicateErrors) +
                                    "\n\nEach DMN model must have a unique 'name' attribute in its <dmn:definitions> element.";
                log.error(errorMessage);
                throw new IllegalStateException(errorMessage);
            }

            log.info("DMN model name validation passed - {} unique model names found across {} total models",
                     nameToFiles.size(), models.size());

        } catch (IllegalStateException e) {
            // Re-throw validation failures
            throw e;
        } catch (Exception e) {
            log.error("Error during model name validation", e);
            throw new IllegalStateException("Failed to validate DMN model names", e);
        }
    }

    /**
     * Use reflection to access the private static dmnRuntime field from AbstractDecisionModels.
     * This is necessary because Kogito doesn't expose a public API to enumerate all models.
     *
     * @param decisionModels the generated DecisionModels instance
     * @return the DMNRuntime, or null if reflection fails
     */
    private DMNRuntime getDMNRuntime(DecisionModels decisionModels) {
        try {
            Field runtimeField = AbstractDecisionModels.class.getDeclaredField("dmnRuntime");
            runtimeField.setAccessible(true);
            return (DMNRuntime) runtimeField.get(null); // static field
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.error("Failed to access DMNRuntime via reflection. " +
                "This may be due to a Kogito version incompatibility.", e);
            return null;
        }
    }

    /**
     * Scan the classpath for .dmn files and build mappings of model name to relative path and description.
     * Uses ClassLoader-based scanning that works in both dev mode and production (JAR).
     * Paths are relative to the classpath root, excluding the .dmn extension.
     *
     * @param modelDescriptions if not null, will be populated with model name to description mappings
     * @return map of model name to relative path
     */
    private Map<String, String> scanDMNFiles(Map<String, String> modelDescriptions,
                                             Map<String, List<BenefitCheckInfo>> benefitChecks) {
        Map<String, String> modelNameToPath = new HashMap<>();

        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            // Try to find a known DMN file to locate the resources root
            URL resourceUrl = classLoader.getResource("BDT.dmn");

            if (resourceUrl != null) {
                String protocol = resourceUrl.getProtocol();
                log.debug("Found BDT.dmn at: {} (protocol: {})", resourceUrl, protocol);

                if ("file".equals(protocol)) {
                    // Dev mode or exploded deployment - get parent directory
                    try {
                        Path dmnPath = Paths.get(resourceUrl.toURI());
                        Path rootPath = dmnPath.getParent(); // This should be target/classes or similar
                        log.debug("Scanning filesystem for DMN files from root: {}", rootPath);
                        scanFilesystemForDMN(rootPath, rootPath, modelNameToPath, modelDescriptions, benefitChecks);
                    } catch (Exception e) {
                        log.error("Error scanning filesystem from resource URL", e);
                    }
                } else if ("jar".equals(protocol)) {
                    // Production mode - extract JAR path
                    try {
                        String jarPath = resourceUrl.getPath();
                        if (jarPath.contains("!")) {
                            jarPath = jarPath.substring(0, jarPath.indexOf("!"));
                            if (jarPath.startsWith("file:")) {
                                jarPath = jarPath.substring(5);
                            }
                            log.debug("Scanning JAR for DMN files: {}", jarPath);
                            scanJarForDMN(jarPath, modelNameToPath, modelDescriptions, benefitChecks);
                        }
                    } catch (Exception e) {
                        log.error("Error scanning JAR from resource URL", e);
                    }
                }
            } else {
                log.warn("Could not locate BDT.dmn resource, falling back to target/classes scan");
                // Fallback for dev mode: check if target/classes exists
                Path targetClasses = Paths.get("target/classes");
                if (Files.exists(targetClasses)) {
                    log.debug("Scanning fallback directory: {}", targetClasses);
                    scanFilesystemForDMN(targetClasses, targetClasses, modelNameToPath, modelDescriptions, benefitChecks);
                } else {
                    log.error("Could not find DMN files - neither BDT.dmn resource nor target/classes directory found");
                }
            }

            log.info("DMN classpath scan complete: found {} models", modelNameToPath.size());

        } catch (Exception e) {
            log.error("Error scanning DMN files from classpath", e);
        }

        return modelNameToPath;
    }

    /**
     * Scan a filesystem directory for DMN files.
     */
    private void scanFilesystemForDMN(Path rootPath, Path currentPath,
                                       Map<String, String> modelNameToPath,
                                       Map<String, String> modelDescriptions,
                                       Map<String, List<BenefitCheckInfo>> benefitChecks) throws Exception {
        log.debug("Walking filesystem from {} (root: {})", currentPath, rootPath);
        try (Stream<Path> paths = Files.walk(currentPath)) {
            paths.filter(path -> path.toString().endsWith(".dmn"))
                 .forEach(path -> {
                     try (InputStream is = Files.newInputStream(path)) {
                         String modelName = extractModelName(is);
                         if (modelName != null) {
                             String relativePath = rootPath.relativize(path).toString();
                             relativePath = relativePath.substring(0, relativePath.length() - 4); // remove .dmn
                             modelNameToPath.put(modelName, relativePath);
                             log.debug("Mapped DMN file: {} -> {}", modelName, relativePath);

                             if (modelDescriptions != null) {
                                 try (InputStream is2 = Files.newInputStream(path)) {
                                     String description = extractModelDescription(is2);
                                     modelDescriptions.put(modelName, description);
                                     log.debug("Extracted description for {}: {}", modelName,
                                               description != null ? description.substring(0, Math.min(50, description.length())) + "..." : "null");
                                 }
                             }
                             if (relativePath.replace('\\', '/').startsWith("benefits/")) {
                                 try (InputStream is3 = Files.newInputStream(path)) {
                                     List<BenefitCheckInfo> checks = extractBenefitChecks(is3);
                                     if (!checks.isEmpty()) {
                                         benefitChecks.put(modelName, checks);
                                     }
                                 }
                             }
                         }
                     } catch (Exception e) {
                         log.warn("Failed to parse DMN file: {}", path, e);
                     }
                 });
        }
    }

    /**
     * Scan a JAR file for DMN files.
     */
    private void scanJarForDMN(String jarPath, Map<String, String> modelNameToPath,
                               Map<String, String> modelDescriptions,
                               Map<String, List<BenefitCheckInfo>> benefitChecks) {
        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".dmn") && !entry.isDirectory()) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        String modelName = extractModelName(is);
                        if (modelName != null) {
                            String relativePath = name.substring(0, name.length() - 4); // remove .dmn
                            modelNameToPath.put(modelName, relativePath);
                            log.debug("Mapped DMN file from JAR: {} -> {}", modelName, relativePath);

                            if (modelDescriptions != null) {
                                try (InputStream is2 = jarFile.getInputStream(entry)) {
                                    String description = extractModelDescription(is2);
                                    modelDescriptions.put(modelName, description);
                                    log.debug("Extracted description for {}: {}", modelName,
                                              description != null ? description.substring(0, Math.min(50, description.length())) + "..." : "null");
                                }
                            }
                            if (relativePath.startsWith("benefits/")) {
                                try (InputStream is3 = jarFile.getInputStream(entry)) {
                                    List<BenefitCheckInfo> checks = extractBenefitChecks(is3);
                                    if (!checks.isEmpty()) {
                                        benefitChecks.put(modelName, checks);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse DMN file from JAR: {}", name, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error scanning JAR for DMN files: {}", jarPath, e);
        }
    }

    /**
     * Extract the model name from a DMN file by parsing its XML.
     *
     * @param inputStream InputStream of the DMN file
     * @return the model name, or null if parsing fails
     */
    private String extractModelName(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(inputStream);

            Element root = doc.getDocumentElement();
            if (root != null && root.hasAttribute("name")) {
                return root.getAttribute("name");
            }
        } catch (Exception e) {
            log.debug("Failed to extract model name: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract the model description from a DMN file by parsing its XML.
     * Looks for the <dmn:description> element within the root <dmn:definitions> element.
     *
     * @param inputStream InputStream of the DMN file
     * @return the model description, or null if not present or parsing fails
     */
    private String extractModelDescription(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(inputStream);

            Element root = doc.getDocumentElement();
            if (root != null) {
                // Look for <dmn:description> child element
                org.w3c.dom.NodeList descNodes = root.getElementsByTagNameNS(
                    "https://www.omg.org/spec/DMN/20240513/MODEL/",
                    "description"
                );
                if (descNodes.getLength() > 0) {
                    String description = descNodes.item(0).getTextContent();
                    // Return null if description is just whitespace
                    return (description != null && !description.trim().isEmpty()) ? description.trim() : null;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to extract description: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extract benefit composition from the conventional top-level decision named {@code checks}.
     * The DMN remains the source of truth; this representation is published in OpenAPI for clients.
     */
    private List<BenefitCheckInfo> extractBenefitChecks(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(inputStream);
            Element definitions = doc.getDocumentElement();
            Element checksDecision = null;
            for (Node node = definitions.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && "decision".equals(element.getLocalName())
                    && "checks".equals(element.getAttribute("name"))) {
                    checksDecision = element;
                    break;
                }
            }
            if (checksDecision == null) {
                return List.of();
            }

            Element context = directChild(checksDecision, "context");
            if (context == null) {
                throw new IllegalArgumentException("decision 'checks' must contain a context");
            }

            List<BenefitCheckInfo> checks = new ArrayList<>();
            for (Element entry : directChildren(context, "contextEntry")) {
                Element variable = directChild(entry, "variable");
                Element invocation = directChild(entry, "invocation");
                if (variable == null || invocation == null) {
                    throw new IllegalArgumentException(
                        "every named entry in decision 'checks' must invoke a library decision service");
                }

                String alias = variable.getAttribute("name");
                Element serviceExpression = directChild(invocation, "literalExpression");
                String qualifiedService = expressionText(serviceExpression);
                String operationId = qualifiedService.substring(qualifiedService.lastIndexOf('.') + 1);
                Map<String, Object> parameters = new LinkedHashMap<>();
                Map<String, String> bindings = new LinkedHashMap<>();

                for (Element binding : directChildren(invocation, "binding")) {
                    Element parameter = directChild(binding, "parameter");
                    if (parameter == null || !"parameters".equals(parameter.getAttribute("name"))) {
                        continue;
                    }
                    Element parameterContext = directChild(binding, "context");
                    if (parameterContext == null) {
                        continue;
                    }
                    for (Element parameterEntry : directChildren(parameterContext, "contextEntry")) {
                        Element parameterVariable = directChild(parameterEntry, "variable");
                        String key = parameterVariable.getAttribute("name");
                        String expression = expressionText(directChild(parameterEntry, "literalExpression"));
                        if (expression.matches("situation\\.[A-Za-z_][A-Za-z0-9_.]*")) {
                            bindings.put(key, expression.substring("situation.".length()));
                        } else {
                            parameters.put(key, parseLiteral(expression, key));
                        }
                    }
                }
                checks.add(new BenefitCheckInfo(operationId, alias, parameters, bindings));
            }
            return List.copyOf(checks);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to extract benefit checks", e);
        }
    }

    private Object parseLiteral(String expression, String parameterName) {
        if (expression.length() >= 2 && expression.startsWith("\"") && expression.endsWith("\"")) {
            return expression.substring(1, expression.length() - 1).replace("\\\"", "\"");
        }
        if ("true".equals(expression) || "false".equals(expression)) {
            return Boolean.valueOf(expression);
        }
        if ("null".equals(expression)) {
            return null;
        }
        try {
            return new java.math.BigDecimal(expression);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("Benefit parameter '" + parameterName + "' uses unsupported FEEL '"
                + expression + "'. Move computed logic into a library check or bind a situation field.");
        }
    }

    private String expressionText(Element expression) {
        Element text = directChild(expression, "text");
        if (text == null || text.getTextContent().trim().isEmpty()) {
            throw new IllegalArgumentException("Expected a non-empty FEEL expression");
        }
        return text.getTextContent().trim();
    }

    private Element directChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private List<Element> directChildren(Element parent, String localName) {
        List<Element> children = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                children.add(element);
            }
        }
        return children;
    }

    /**
     * Scan the classpath for .dmn files and build a mapping of namespace to relative path.
     * Uses ClassLoader-based scanning that works in both dev mode and production (JAR).
     * This is used for validation to show the correct file paths for duplicate models.
     *
     * @return map of namespace to relative path
     */
    private Map<String, String> scanDMNFilesForNamespaces() {
        Map<String, String> namespaceToPath = new HashMap<>();

        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

            // Try to find a known DMN file to locate the resources root
            URL resourceUrl = classLoader.getResource("BDT.dmn");

            if (resourceUrl != null) {
                String protocol = resourceUrl.getProtocol();

                if ("file".equals(protocol)) {
                    // Dev mode or exploded deployment
                    Path dmnPath = Paths.get(resourceUrl.toURI());
                    Path rootPath = dmnPath.getParent();
                    scanFilesystemForNamespaces(rootPath, rootPath, namespaceToPath);
                } else if ("jar".equals(protocol)) {
                    // Production mode - scan JAR file
                    String jarPath = resourceUrl.getPath();
                    if (jarPath.contains("!")) {
                        jarPath = jarPath.substring(0, jarPath.indexOf("!"));
                        if (jarPath.startsWith("file:")) {
                            jarPath = jarPath.substring(5);
                        }
                        scanJarForNamespaces(jarPath, namespaceToPath);
                    }
                }
            } else {
                // Fallback for dev mode
                Path targetClasses = Paths.get("target/classes");
                if (Files.exists(targetClasses)) {
                    scanFilesystemForNamespaces(targetClasses, targetClasses, namespaceToPath);
                }
            }

        } catch (Exception e) {
            log.error("Error scanning DMN files for namespaces from classpath", e);
        }

        return namespaceToPath;
    }

    /**
     * Scan a filesystem directory for DMN namespaces.
     */
    private void scanFilesystemForNamespaces(Path rootPath, Path currentPath,
                                             Map<String, String> namespaceToPath) throws Exception {
        try (Stream<Path> paths = Files.walk(currentPath)) {
            paths.filter(path -> path.toString().endsWith(".dmn"))
                 .forEach(path -> {
                     try (InputStream is = Files.newInputStream(path)) {
                         DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                         factory.setNamespaceAware(true);
                         DocumentBuilder builder = factory.newDocumentBuilder();
                         Document doc = builder.parse(is);

                         Element root = doc.getDocumentElement();
                         if (root != null && root.hasAttribute("namespace")) {
                             String namespace = root.getAttribute("namespace");
                             String relativePath = rootPath.relativize(path).toString();
                             relativePath = relativePath.substring(0, relativePath.length() - 4); // remove .dmn
                             namespaceToPath.put(namespace, relativePath);
                             log.debug("Mapped DMN namespace: {} -> {}", namespace, relativePath);
                         }
                     } catch (Exception e) {
                         log.warn("Failed to parse DMN file: {}", path, e);
                     }
                 });
        }
    }

    /**
     * Scan a JAR file for DMN namespaces.
     */
    private void scanJarForNamespaces(String jarPath, Map<String, String> namespaceToPath) {
        try (JarFile jarFile = new JarFile(jarPath)) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".dmn") && !entry.isDirectory()) {
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setNamespaceAware(true);
                        DocumentBuilder builder = factory.newDocumentBuilder();
                        Document doc = builder.parse(is);

                        Element root = doc.getDocumentElement();
                        if (root != null && root.hasAttribute("namespace")) {
                            String namespace = root.getAttribute("namespace");
                            String relativePath = name.substring(0, name.length() - 4); // remove .dmn
                            namespaceToPath.put(namespace, relativePath);
                            log.debug("Mapped DMN namespace from JAR: {} -> {}", namespace, relativePath);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse DMN file from JAR: {}", name, e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error scanning JAR for namespaces: {}", jarPath, e);
        }
    }
}
