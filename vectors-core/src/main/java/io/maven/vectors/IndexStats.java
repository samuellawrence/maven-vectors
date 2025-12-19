package io.maven.vectors;

import java.util.Map;

/**
 * Statistics about a VectorIndex.
 */
public record IndexStats(
    /** Total number of chunks */
    int totalChunks,
    
    /** Chunks by type */
    Map<ChunkType, Integer> chunksByType,
    
    /** Number of source files */
    int fileCount,
    
    /** Embedding model info */
    String modelId,
    
    /** Vector dimensions */
    int dimensions,
    
    /** Index size in bytes */
    long sizeBytes
) {}
