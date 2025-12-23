package io.maven.vectors.embeddings;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for embedding models.
 */
public record EmbeddingConfig(
    /** Backend to use for embedding generation */
    EmbeddingBackend backend,
    
    /** Directory to cache downloaded models */
    Path cacheDir,
    
    /** Maximum sequence length for tokenization */
    int maxSequenceLength,
    
    /** Batch size for batch embedding operations */
    int batchSize,
    
    /** Whether to normalize output vectors */
    boolean normalizeOutput,
    
    /** Whether to preprocess code (split camelCase, etc.) */
    boolean preprocessCode,
    
    /** API key for cloud-based embedding providers (Voyage AI, OpenAI) */
    String apiKey,
    
    /** Output dimensions (0 = use model default) */
    int dimensions
) {
    
    public static EmbeddingConfig defaults() {
        return new EmbeddingConfig(
            EmbeddingBackend.SIMPLE,
            Paths.get(System.getProperty("user.home"), ".maven-vectors", "models"),
            512,
            32,
            true,
            false,
            null,
            0
        );
    }
    
    public static EmbeddingConfig onnx() {
        return new EmbeddingConfig(
            EmbeddingBackend.ONNX,
            Paths.get(System.getProperty("user.home"), ".maven-vectors", "models"),
            512,
            32,
            true,
            true,  // Enable preprocessing for better code search
            null,
            0
        );
    }
    
    public static EmbeddingConfig voyage() {
        return new EmbeddingConfig(
            EmbeddingBackend.VOYAGE,
            Paths.get(System.getProperty("user.home"), ".maven-vectors", "models"),
            512,
            128,   // Larger batch size for API
            true,
            true,  // Enable preprocessing
            null,  // Will use VOYAGE_API_KEY env var
            1024   // Default dimensions for voyage-code-3
        );
    }
    
    public static EmbeddingConfig voyage(String apiKey) {
        return voyage().withApiKey(apiKey);
    }
    
    public EmbeddingConfig withBackend(EmbeddingBackend backend) {
        return new EmbeddingConfig(backend, cacheDir, maxSequenceLength, batchSize, normalizeOutput, preprocessCode, apiKey, dimensions);
    }
    
    public EmbeddingConfig withCacheDir(Path cacheDir) {
        return new EmbeddingConfig(backend, cacheDir, maxSequenceLength, batchSize, normalizeOutput, preprocessCode, apiKey, dimensions);
    }
    
    public EmbeddingConfig withPreprocessing(boolean enabled) {
        return new EmbeddingConfig(backend, cacheDir, maxSequenceLength, batchSize, normalizeOutput, enabled, apiKey, dimensions);
    }
    
    public EmbeddingConfig withApiKey(String apiKey) {
        return new EmbeddingConfig(backend, cacheDir, maxSequenceLength, batchSize, normalizeOutput, preprocessCode, apiKey, dimensions);
    }
    
    public EmbeddingConfig withDimensions(int dimensions) {
        return new EmbeddingConfig(backend, cacheDir, maxSequenceLength, batchSize, normalizeOutput, preprocessCode, apiKey, dimensions);
    }
}

/**
 * Available embedding backends.
 */
enum EmbeddingBackend {
    /** ONNX Runtime - local model execution */
    ONNX,
    
    /** Voyage AI API - best code embeddings (200M tokens free) */
    VOYAGE,
    
    /** Simple hash-based embeddings (for testing/development) */
    SIMPLE
}
