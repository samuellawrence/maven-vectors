package io.maven.vectors.embeddings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Voyage AI embedding model using their REST API.
 * 
 * <p>Voyage AI offers state-of-the-art code embeddings with voyage-code-3.
 * Free tier includes 200M tokens.</p>
 * 
 * <p>API key can be provided via:
 * <ol>
 *   <li>Environment variable: VOYAGE_API_KEY</li>
 *   <li>EmbeddingConfig.apiKey()</li>
 * </ol>
 * </p>
 * 
 * @see <a href="https://docs.voyageai.com/docs/embeddings">Voyage AI Embeddings</a>
 */
public class VoyageEmbeddingModel implements EmbeddingModel {
    
    private static final Logger log = LoggerFactory.getLogger(VoyageEmbeddingModel.class);
    
    private static final String API_URL = "https://api.voyageai.com/v1/embeddings";
    private static final int MAX_BATCH_SIZE = 128;
    private static final int MAX_RETRIES = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    
    /** Available Voyage AI models */
    public static final String MODEL_CODE_3 = "voyage-code-3";
    public static final String MODEL_3_LARGE = "voyage-3-large";
    public static final String MODEL_3_5 = "voyage-3.5";
    public static final String MODEL_3_5_LITE = "voyage-3.5-lite";
    
    private final String modelId;
    private final String apiKey;
    private final int dimensions;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CodePreprocessor preprocessor;
    
    public VoyageEmbeddingModel(String modelId, EmbeddingConfig config) {
        this.modelId = resolveModelId(modelId);
        this.apiKey = resolveApiKey(config);
        this.dimensions = resolveDimensions(modelId, config);
        this.preprocessor = config.preprocessCode() ? CodePreprocessor.defaults() : null;
        
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        this.objectMapper = new ObjectMapper();
        
        log.info("Initialized Voyage AI embedding model: {} ({}D)", this.modelId, dimensions);
    }
    
    private String resolveModelId(String modelId) {
        // Map short names to full model IDs
        return switch (modelId.toLowerCase()) {
            case "voyage-code", "voyage-code-3", "code" -> MODEL_CODE_3;
            case "voyage-3-large", "large" -> MODEL_3_LARGE;
            case "voyage-3.5", "3.5" -> MODEL_3_5;
            case "voyage-3.5-lite", "lite" -> MODEL_3_5_LITE;
            default -> modelId;
        };
    }
    
    private String resolveApiKey(EmbeddingConfig config) {
        // Priority: config > environment variable
        String key = config.apiKey();
        if (key == null || key.isBlank()) {
            key = System.getenv("VOYAGE_API_KEY");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "Voyage AI API key not found. Set VOYAGE_API_KEY environment variable or use --api-key option.");
        }
        return key;
    }
    
    private int resolveDimensions(String modelId, EmbeddingConfig config) {
        // voyage-code-3 supports: 256, 512, 1024 (default), 2048
        if (config.dimensions() > 0) {
            return config.dimensions();
        }
        return 1024; // Default for voyage-code-3
    }
    
    @Override
    public float[] embed(String code) {
        List<float[]> results = embedBatch(List.of(code));
        return results.isEmpty() ? new float[dimensions] : results.get(0);
    }
    
    @Override
    public List<float[]> embedBatch(List<String> codes) {
        List<float[]> allEmbeddings = new ArrayList<>();
        
        // Process in batches
        for (int i = 0; i < codes.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, codes.size());
            List<String> batch = codes.subList(i, end);
            
            // Preprocess if enabled
            List<String> processedBatch = batch;
            if (preprocessor != null) {
                processedBatch = batch.stream()
                    .map(preprocessor::preprocess)
                    .toList();
            }
            
            List<float[]> batchEmbeddings = embedBatchInternal(processedBatch, "document");
            allEmbeddings.addAll(batchEmbeddings);
        }
        
        return allEmbeddings;
    }
    
    /**
     * Embeds a query (uses input_type="query" for better retrieval).
     */
    public float[] embedQuery(String query) {
        String processed = preprocessor != null ? preprocessor.preprocess(query) : query;
        List<float[]> results = embedBatchInternal(List.of(processed), "query");
        return results.isEmpty() ? new float[dimensions] : results.get(0);
    }
    
    private List<float[]> embedBatchInternal(List<String> texts, String inputType) {
        try {
            // Build request as Map to ensure correct JSON field names
            var requestMap = new java.util.LinkedHashMap<String, Object>();
            requestMap.put("input", texts);
            requestMap.put("model", modelId);
            requestMap.put("input_type", inputType);
            // Only include output_dimension if non-default
            if (dimensions != 1024) {
                requestMap.put("output_dimension", dimensions);
            }
            
            String requestBody = objectMapper.writeValueAsString(requestMap);
            log.debug("Voyage API request: {}", requestBody);
            
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
            
            // Retry logic
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    HttpResponse<String> response = httpClient.send(httpRequest, 
                        HttpResponse.BodyHandlers.ofString());
                    
                    if (response.statusCode() == 200) {
                        EmbeddingResponse embeddingResponse = objectMapper.readValue(
                            response.body(), EmbeddingResponse.class);
                        
                        return embeddingResponse.data.stream()
                            .map(d -> d.embedding)
                            .toList();
                    } else if (response.statusCode() == 401) {
                        log.error("Invalid API key. Response: {}", response.body());
                        throw new RuntimeException("Invalid Voyage API key. Check your VOYAGE_API_KEY or --api-key value.");
                    } else if (response.statusCode() == 400) {
                        log.error("Bad request. Response: {}", response.body());
                        throw new RuntimeException("Bad request to Voyage API: " + response.body());
                    } else if (response.statusCode() == 429) {
                        // Rate limited - wait and retry
                        long waitMs = (long) Math.pow(2, attempt) * 1000;
                        if (attempt == 1) {
                            // Log full response on first rate limit to help debug
                            log.info("Rate limited on first attempt. Response: {}", response.body());
                        }
                        log.warn("Rate limited, waiting {}ms before retry {}/{}", 
                            waitMs, attempt, MAX_RETRIES);
                        Thread.sleep(waitMs);
                    } else {
                        log.error("Voyage API error: {} - {}", response.statusCode(), response.body());
                        throw new RuntimeException("Voyage API error " + response.statusCode() + ": " + response.body());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during API call", e);
                }
            }
            
            throw new RuntimeException("Max retries exceeded for Voyage API");
            
        } catch (IOException e) {
            log.error("Failed to call Voyage API", e);
            throw new RuntimeException("Failed to call Voyage API", e);
        }
    }
    
    @Override
    public String getModelId() {
        return "voyage:" + modelId;
    }
    
    @Override
    public int getDimensions() {
        return dimensions;
    }
    
    @Override
    public long getModelHash() {
        return (modelId + ":" + dimensions).hashCode();
    }
    
    @Override
    public void close() {
        // HttpClient doesn't need explicit closing in Java 11+
    }
    
    // ==================== Response DTOs ====================
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingResponse {
        @JsonProperty("data")
        public List<EmbeddingData> data;
        
        @JsonProperty("usage")
        public Usage usage;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingData {
        @JsonProperty("embedding")
        public float[] embedding;
        
        @JsonProperty("index")
        public int index;
    }
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Usage {
        @JsonProperty("total_tokens")
        public int totalTokens;
    }
}
