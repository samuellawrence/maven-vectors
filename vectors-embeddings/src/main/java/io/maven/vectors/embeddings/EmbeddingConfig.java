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
    boolean normalizeOutput
) {
    
    public static EmbeddingConfig defaults() {
        return new EmbeddingConfig(
            EmbeddingBackend.SIMPLE,
            Paths.get(System.getProperty("user.home"), ".maven-vectors", "models"),
            512,
            32,
            true
        );
    }
    
    public static EmbeddingConfig onnx() {
        return new EmbeddingConfig(
            EmbeddingBackend.ONNX,
            Paths.get(System.getProperty("user.home"), ".maven-vectors", "models"),
            512,
            32,
            true
        );
    }
    
    public EmbeddingConfig withBackend(EmbeddingBackend backend) {
        return new EmbeddingConfig(backend, cacheDir, maxSequenceLength, batchSize, normalizeOutput);
    }
    
    public EmbeddingConfig withCacheDir(Path cacheDir) {
        return new EmbeddingConfig(backend, cacheDir, maxSequenceLength, batchSize, normalizeOutput);
    }
}

/**
 * Available embedding backends.
 */
enum EmbeddingBackend {
    /** ONNX Runtime - local model execution */
    ONNX,
    
    /** Simple hash-based embeddings (for testing/development) */
    SIMPLE
}
