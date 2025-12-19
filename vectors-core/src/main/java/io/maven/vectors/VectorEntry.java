package io.maven.vectors;

/**
 * A code chunk paired with its embedding.
 */
public record VectorEntry(
    CodeChunk chunk,
    float[] embedding
) {
    public VectorEntry {
        if (chunk == null) throw new IllegalArgumentException("chunk cannot be null");
        if (embedding == null) throw new IllegalArgumentException("embedding cannot be null");
    }
}
