package io.maven.vectors;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Main interface for working with code vector indexes.
 * 
 * <p>A VectorIndex stores code chunks along with their vector embeddings,
 * enabling semantic search across codebases.</p>
 * 
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a new index
 * VectorIndex index = VectorIndex.create(IndexConfig.defaultConfig());
 * 
 * // Add code chunks with embeddings
 * CodeChunk chunk = CodeChunk.of("UserService.findById", ChunkType.METHOD, code, file, 10, 25);
 * float[] embedding = embeddingModel.embed(code);
 * index.add(chunk, embedding);
 * 
 * // Save to file
 * index.save(Path.of("vectors.mvec"));
 * 
 * // Load and search
 * VectorIndex loaded = VectorIndex.load(Path.of("vectors.mvec"));
 * List<SearchResult> results = loaded.search("find user by id", 10);
 * }</pre>
 */
public interface VectorIndex extends AutoCloseable {
    
    // ==================== Factory Methods ====================
    
    /**
     * Creates a new empty index with default configuration.
     * Uses brute-force search (suitable for small indexes).
     */
    static VectorIndex create() {
        return create(IndexConfig.defaultConfig());
    }
    
    /**
     * Creates a new empty index with the specified configuration.
     * Uses brute-force search (suitable for small indexes).
     */
    static VectorIndex create(IndexConfig config) {
        return new InMemoryVectorIndex(config);
    }
    
    /**
     * Creates a new HNSW-based index for fast approximate search.
     * Recommended for indexes with more than 10,000 vectors.
     * 
     * @param config Index configuration
     * @param maxItems Maximum number of items (pre-allocates graph structure)
     */
    static VectorIndex createHnsw(IndexConfig config, int maxItems) {
        return new HnswVectorIndex(config, maxItems);
    }
    
    /**
     * Creates a new HNSW-based index with default capacity (100,000 items).
     */
    static VectorIndex createHnsw(IndexConfig config) {
        return new HnswVectorIndex(config);
    }
    
    /**
     * Loads an index from a file, auto-detecting the format.
     * Supports both brute-force (.mvec) and HNSW formats.
     * 
     * @param path Path to the index file
     * @return Loaded index
     * @throws IOException if the file cannot be read
     */
    static VectorIndex load(Path path) throws IOException {
        // Peek at magic bytes to determine format
        try (InputStream is = Files.newInputStream(path)) {
            byte[] magic = new byte[4];
            is.read(magic);
            String magicStr = new String(magic);
            
            // Re-open and load with appropriate loader
            if ("MHNS".equals(magicStr)) {
                return HnswVectorIndex.loadFrom(path);
            } else if ("MVEC".equals(magicStr)) {
                return InMemoryVectorIndex.loadFrom(path);
            } else {
                throw new IOException("Unknown index format: " + magicStr);
            }
        }
    }
    
    /**
     * Loads an index from an input stream.
     * Note: Stream must be buffered; this method reads magic bytes first.
     * 
     * @param is Input stream containing index data
     * @return Loaded index
     * @throws IOException if the stream cannot be read
     */
    static VectorIndex load(InputStream is) throws IOException {
        // Wrap in BufferedInputStream to support mark/reset
        BufferedInputStream bis = new BufferedInputStream(is);
        bis.mark(8);
        
        byte[] magic = new byte[4];
        bis.read(magic);
        bis.reset();
        
        String magicStr = new String(magic);
        if ("MHNS".equals(magicStr)) {
            return HnswVectorIndex.loadFrom(bis);
        } else if ("MVEC".equals(magicStr)) {
            return InMemoryVectorIndex.loadFrom(bis);
        } else {
            throw new IOException("Unknown index format: " + magicStr);
        }
    }
    
    // ==================== Modification ====================
    
    /**
     * Adds a code chunk with its embedding to the index.
     * 
     * @param chunk The code chunk
     * @param embedding The vector embedding
     */
    void add(CodeChunk chunk, float[] embedding);
    
    /**
     * Adds multiple entries to the index.
     * 
     * @param entries List of chunk-embedding pairs
     */
    void addAll(List<VectorEntry> entries);
    
    /**
     * Merges another index into this one.
     * 
     * <p>Both indexes must use the same embedding model.</p>
     * 
     * @param other Index to merge
     * @throws IncompatibleModelException if models don't match
     */
    void merge(VectorIndex other);
    
    // ==================== Search ====================
    
    /**
     * Searches for code chunks similar to the query text.
     * 
     * <p>The query will be embedded using the configured model before searching.</p>
     * 
     * @param query Natural language query
     * @param topK Number of results to return
     * @return List of search results, sorted by similarity (descending)
     */
    List<SearchResult> search(String query, int topK);
    
    /**
     * Searches using a pre-computed query vector.
     * 
     * @param queryVector Query embedding
     * @param topK Number of results to return
     * @return List of search results, sorted by similarity (descending)
     */
    List<SearchResult> search(float[] queryVector, int topK);
    
    /**
     * Searches for code chunks of a specific type.
     * 
     * @param query Natural language query
     * @param type Filter by chunk type
     * @param topK Number of results to return
     * @return Filtered search results
     */
    List<SearchResult> searchByType(String query, ChunkType type, int topK);
    
    // ==================== Analysis ====================
    
    /**
     * Finds code chunks that are outliers compared to the rest of the codebase.
     * 
     * @param threshold Distance threshold (lower = stricter)
     * @return List of anomalous chunks
     */
    List<CodeChunk> findAnomalies(float threshold);
    
    /**
     * Finds groups of nearly-duplicate code.
     * 
     * @param threshold Similarity threshold (higher = stricter, e.g., 0.95)
     * @return Groups of duplicate chunks
     */
    List<DuplicateGroup> findDuplicates(float threshold);
    
    /**
     * Returns statistics about the index.
     */
    IndexStats getStats();
    
    // ==================== Persistence ====================
    
    /**
     * Saves the index to a file.
     * 
     * @param path Output path
     * @throws IOException if the file cannot be written
     */
    void save(Path path) throws IOException;
    
    /**
     * Saves the index to an output stream.
     * 
     * @param os Output stream
     * @throws IOException if writing fails
     */
    void save(OutputStream os) throws IOException;
    
    /**
     * Serializes the index to bytes.
     */
    byte[] toBytes() throws IOException;
    
    // ==================== Metadata ====================
    
    /**
     * Returns the embedding model identifier used by this index.
     */
    String getModelId();
    
    /**
     * Returns a hash of the embedding model for compatibility checks.
     */
    long getModelHash();
    
    /**
     * Returns the vector dimensions.
     */
    int getDimensions();
    
    /**
     * Returns the number of indexed chunks.
     */
    int size();
    
    /**
     * Checks if the index is empty.
     */
    default boolean isEmpty() {
        return size() == 0;
    }
    
    // ==================== Lifecycle ====================
    
    /**
     * Releases resources held by this index.
     */
    @Override
    void close();
}
