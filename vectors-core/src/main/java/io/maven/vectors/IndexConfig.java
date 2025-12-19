package io.maven.vectors;

/**
 * Configuration for creating a VectorIndex.
 */
public record IndexConfig(
    /** Embedding model identifier */
    String modelId,
    
    /** Vector dimensions */
    int dimensions,
    
    /** HNSW M parameter (max connections) */
    int hnswM,
    
    /** HNSW efConstruction parameter */
    int hnswEfConstruction,
    
    /** HNSW efSearch parameter */
    int hnswEfSearch
) {
    public static IndexConfig defaultConfig() {
        return new IndexConfig(
            "microsoft/unixcoder-base",
            768,
            16,
            200,
            50
        );
    }
    
    public static IndexConfig forModel(String modelId, int dimensions) {
        return new IndexConfig(modelId, dimensions, 16, 200, 50);
    }
}
