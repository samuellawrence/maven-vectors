package io.maven.vectors;

import java.util.Objects;

/**
 * Represents a search result from a vector similarity query.
 */
public record SearchResult(
    /** The matching code chunk */
    CodeChunk chunk,
    
    /** Similarity score (0.0 to 1.0, higher is more similar) */
    float similarity,
    
    /** Source artifact (groupId:artifactId:version) */
    String artifactId
) implements Comparable<SearchResult> {
    
    public SearchResult {
        Objects.requireNonNull(chunk, "chunk cannot be null");
        // Clamp similarity to valid range (handles floating point errors)
        similarity = Math.max(0f, Math.min(1f, similarity));
    }
    
    /**
     * Creates a SearchResult without artifact information.
     */
    public static SearchResult of(CodeChunk chunk, float similarity) {
        return new SearchResult(chunk, similarity, null);
    }
    
    /**
     * Creates a SearchResult with artifact information.
     */
    public static SearchResult of(CodeChunk chunk, float similarity, String artifactId) {
        return new SearchResult(chunk, similarity, artifactId);
    }
    
    /**
     * Returns the similarity as a percentage string.
     */
    public String similarityPercent() {
        return String.format("%.1f%%", similarity * 100);
    }
    
    /**
     * Compares by similarity (descending order).
     */
    @Override
    public int compareTo(SearchResult other) {
        return Float.compare(other.similarity, this.similarity);
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%.2f] %s", similarity, chunk.qualifiedName()));
        if (artifactId != null) {
            sb.append(" (").append(artifactId).append(")");
        }
        sb.append("\n  ").append(chunk.file()).append(":").append(chunk.lineStart());
        return sb.toString();
    }
}
