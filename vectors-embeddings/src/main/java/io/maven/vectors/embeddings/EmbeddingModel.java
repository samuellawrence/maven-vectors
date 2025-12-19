package io.maven.vectors.embeddings;

import java.io.Closeable;
import java.util.List;

/**
 * Interface for generating code embeddings.
 * 
 * <p>Implementations can use local models (ONNX) or remote APIs (OpenAI, etc.)</p>
 */
public interface EmbeddingModel extends Closeable {
    
    /**
     * Generates an embedding for a single code snippet.
     * 
     * @param code The source code to embed
     * @return Vector embedding as float array
     */
    float[] embed(String code);
    
    /**
     * Generates embeddings for multiple code snippets.
     * 
     * <p>Implementations may batch requests for efficiency.</p>
     * 
     * @param codes List of source code snippets
     * @return List of embeddings in the same order
     */
    List<float[]> embedBatch(List<String> codes);
    
    /**
     * Returns the model identifier (e.g., "microsoft/unixcoder-base").
     */
    String getModelId();
    
    /**
     * Returns the embedding dimensions.
     */
    int getDimensions();
    
    /**
     * Returns a hash for model compatibility checks.
     */
    long getModelHash();
    
    /**
     * Loads an embedding model by ID.
     * 
     * @param modelId Model identifier
     * @return Configured embedding model
     */
    static EmbeddingModel load(String modelId) {
        return load(modelId, EmbeddingConfig.defaults());
    }
    
    /**
     * Loads an embedding model with custom configuration.
     * 
     * @param modelId Model identifier
     * @param config Configuration options
     * @return Configured embedding model
     */
    static EmbeddingModel load(String modelId, EmbeddingConfig config) {
        return switch (config.backend()) {
            case ONNX -> new OnnxEmbeddingModel(modelId, config);
            case SIMPLE -> new SimpleEmbeddingModel(modelId, config);
        };
    }
}
