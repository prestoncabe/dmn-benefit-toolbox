package org.codeforphilly.bdt.builder.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sends structured-output requests to Gemini. Alias generation and form drafting share
 * one connection, configured under the alias-generation.gemini.* properties.
 */
@ApplicationScoped
public class GeminiClient {
    /** Safe provider diagnostics; never includes the response body, prompt, or credentials. */
    public static class GenerationException extends IOException {
        public GenerationException(String message) {
            super(message);
        }
    }

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "alias-generation.gemini.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "alias-generation.gemini.model", defaultValue = "gemini-3.5-flash-lite")
    String model;

    @ConfigProperty(
        name = "alias-generation.gemini.base-url",
        defaultValue = "https://generativelanguage.googleapis.com/v1beta/models/"
    )
    String baseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();

    public boolean isConfigured() {
        return apiKey.isPresent() && !apiKey.get().isBlank();
    }

    /**
     * Requests JSON matching responseSchema and returns the generated text. Returns empty when
     * Gemini stops before finishing, such as at the token limit. Provider HTTP failures throw
     * a GenerationException with a safe diagnostic for callers to display.
     * Never logs the prompt, the response, or the API key.
     */
    public Optional<String> generateJson(String purpose, String prompt, Map<String, Object> responseSchema,
                                         int maxOutputTokens, Duration timeout)
        throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
            "generationConfig", Map.of(
                "temperature", 0.2,
                "maxOutputTokens", maxOutputTokens,
                "responseMimeType", "application/json",
                "responseJsonSchema", responseSchema
            )
        );
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + model + ":generateContent"))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey.orElse(""))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Log.warnf("Gemini %s returned HTTP %d", purpose, response.statusCode());
            throw new GenerationException(switch (response.statusCode()) {
                case 400 -> "Gemini rejected the AI request (HTTP 400). Try again; if it persists, the request needs to be checked.";
                case 401, 403 -> "Gemini access was denied. Check the configured API key and permissions.";
                case 404 -> "The configured Gemini model is unavailable. Check GEMINI_MODEL.";
                case 429 -> "Gemini's rate or usage limit was reached. Wait a moment and try again.";
                default -> "Gemini is unavailable (HTTP " + response.statusCode() + "). Try again later.";
            });
        }
        JsonNode candidate = objectMapper.readTree(response.body()).path("candidates").path(0);
        String finishReason = candidate.path("finishReason").asText("no candidate");
        if (!"STOP".equals(finishReason)) {
            Log.warnf("Gemini %s did not finish: %s", purpose, finishReason);
            return Optional.empty();
        }
        return Optional.of(candidate.path("content").path("parts").path(0).path("text").asText());
    }
}
