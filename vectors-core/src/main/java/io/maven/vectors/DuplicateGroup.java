package io.maven.vectors;

import java.util.List;

/**
 * A group of duplicate code chunks.
 */
public record DuplicateGroup(
    /** Minimum similarity within the group */
    float similarity,
    
    /** Number of chunks in the group */
    int count,
    
    /** The duplicate chunks */
    List<CodeChunk> chunks
) {}
