package io.maven.vectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory implementation of VectorIndex using brute-force search.
 * 
 * <p>This implementation is suitable for small to medium indexes (up to ~100k vectors).
 * For larger indexes, consider using the HNSW-based implementation.</p>
 */
public class InMemoryVectorIndex implements VectorIndex {
    
    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorIndex.class);
    
    // Format constants
    private static final byte[] MAGIC = "MVEC".getBytes();
    private static final short FORMAT_VERSION = 1;
    
    private final IndexConfig config;
    private final List<CodeChunk> chunks;
    private final List<float[]> vectors;
    private final Map<String, Integer> idToIndex;
    
    // Embedding model for query-time embedding (optional)
    private EmbeddingProvider embeddingProvider;
    
    public InMemoryVectorIndex(IndexConfig config) {
        this.config = config;
        this.chunks = new ArrayList<>();
        this.vectors = new ArrayList<>();
        this.idToIndex = new HashMap<>();
    }
    
    /**
     * Sets the embedding provider for query-time text embedding.
     */
    public void setEmbeddingProvider(EmbeddingProvider provider) {
        this.embeddingProvider = provider;
    }
    
    // ==================== Modification ====================
    
    @Override
    public void add(CodeChunk chunk, float[] embedding) {
        if (embedding.length != config.dimensions()) {
            throw new IllegalArgumentException(String.format(
                "Embedding dimension mismatch: expected %d, got %d",
                config.dimensions(), embedding.length
            ));
        }
        
        int index = chunks.size();
        chunks.add(chunk);
        vectors.add(embedding.clone());
        idToIndex.put(chunk.id(), index);
        
        log.debug("Added chunk: {} (index={})", chunk.name(), index);
    }
    
    @Override
    public void addAll(List<VectorEntry> entries) {
        for (VectorEntry entry : entries) {
            add(entry.chunk(), entry.embedding());
        }
    }
    
    @Override
    public void merge(VectorIndex other) {
        // Verify model compatibility
        if (!Objects.equals(getModelId(), other.getModelId())) {
            throw new IncompatibleModelException(getModelId(), other.getModelId());
        }
        
        if (other instanceof InMemoryVectorIndex otherIndex) {
            for (int i = 0; i < otherIndex.chunks.size(); i++) {
                CodeChunk chunk = otherIndex.chunks.get(i);
                float[] vector = otherIndex.vectors.get(i);
                
                // Skip duplicates
                if (!idToIndex.containsKey(chunk.id())) {
                    add(chunk, vector);
                }
            }
        } else {
            throw new UnsupportedOperationException("Cannot merge with " + other.getClass().getName());
        }
    }
    
    // ==================== Search ====================
    
    @Override
    public List<SearchResult> search(String query, int topK) {
        if (embeddingProvider == null) {
            throw new IllegalStateException("No embedding provider configured for text queries");
        }
        
        float[] queryVector = embeddingProvider.embed(query);
        return search(queryVector, topK);
    }
    
    @Override
    public List<SearchResult> search(float[] queryVector, int topK) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        
        // Compute similarities
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float similarity = cosineSimilarity(queryVector, vectors.get(i));
            results.add(SearchResult.of(chunks.get(i), similarity));
        }
        
        // Sort and return top-K (compareTo sorts by similarity descending)
        results.sort(Comparator.naturalOrder());
        return results.stream()
            .limit(topK)
            .collect(Collectors.toList());
    }
    
    @Override
    public List<SearchResult> searchByType(String query, ChunkType type, int topK) {
        if (embeddingProvider == null) {
            throw new IllegalStateException("No embedding provider configured for text queries");
        }
        
        float[] queryVector = embeddingProvider.embed(query);
        
        // Filter by type, then search
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).type() == type) {
                float similarity = cosineSimilarity(queryVector, vectors.get(i));
                results.add(SearchResult.of(chunks.get(i), similarity));
            }
        }
        
        results.sort(Comparator.naturalOrder());
        return results.stream()
            .limit(topK)
            .collect(Collectors.toList());
    }
    
    // ==================== Analysis ====================
    
    @Override
    public List<CodeChunk> findAnomalies(float threshold) {
        if (chunks.size() < 5) {
            return List.of();
        }
        
        List<CodeChunk> anomalies = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            // Calculate average similarity to other vectors
            float avgSimilarity = 0;
            for (int j = 0; j < vectors.size(); j++) {
                if (i != j) {
                    avgSimilarity += cosineSimilarity(vectors.get(i), vectors.get(j));
                }
            }
            avgSimilarity /= (vectors.size() - 1);
            
            // If average similarity is below threshold, it's an anomaly
            if (avgSimilarity < threshold) {
                anomalies.add(chunks.get(i));
            }
        }
        
        return anomalies;
    }
    
    @Override
    public List<DuplicateGroup> findDuplicates(float threshold) {
        List<DuplicateGroup> groups = new ArrayList<>();
        Set<Integer> processed = new HashSet<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            if (processed.contains(i)) continue;
            
            List<CodeChunk> group = new ArrayList<>();
            group.add(chunks.get(i));
            processed.add(i);
            
            for (int j = i + 1; j < chunks.size(); j++) {
                if (processed.contains(j)) continue;
                
                float similarity = cosineSimilarity(vectors.get(i), vectors.get(j));
                if (similarity >= threshold) {
                    group.add(chunks.get(j));
                    processed.add(j);
                }
            }
            
            if (group.size() > 1) {
                groups.add(new DuplicateGroup(threshold, group.size(), group));
            }
        }
        
        return groups;
    }
    
    @Override
    public IndexStats getStats() {
        Map<ChunkType, Integer> byType = new EnumMap<>(ChunkType.class);
        Set<String> files = new HashSet<>();
        
        for (CodeChunk chunk : chunks) {
            byType.merge(chunk.type(), 1, Integer::sum);
            files.add(chunk.file());
        }
        
        return new IndexStats(
            chunks.size(),
            byType,
            files.size(),
            config.modelId(),
            config.dimensions(),
            estimateSizeBytes()
        );
    }
    
    // ==================== Persistence ====================
    
    @Override
    public void save(Path path) throws IOException {
        try (OutputStream os = Files.newOutputStream(path)) {
            save(os);
        }
    }
    
    @Override
    public void save(OutputStream os) throws IOException {
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(os));
        
        // Write header
        dos.write(MAGIC);
        dos.writeShort(FORMAT_VERSION);
        dos.writeInt(config.dimensions());
        dos.writeInt(chunks.size());
        dos.writeLong(getModelHash());
        
        // Write model ID
        dos.writeUTF(config.modelId());
        
        // Write chunks as JSON
        ObjectMapper mapper = new ObjectMapper();
        byte[] chunksJson = mapper.writeValueAsBytes(chunks);
        dos.writeInt(chunksJson.length);
        dos.write(chunksJson);
        
        // Write vectors
        for (float[] vector : vectors) {
            for (float v : vector) {
                dos.writeFloat(v);
            }
        }
        
        dos.flush();
    }
    
    @Override
    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        save(baos);
        return baos.toByteArray();
    }
    
    public static InMemoryVectorIndex loadFrom(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadFrom(is);
        }
    }
    
    public static InMemoryVectorIndex loadFrom(InputStream is) throws IOException {
        DataInputStream dis = new DataInputStream(new BufferedInputStream(is));
        
        // Read and verify header
        byte[] magic = new byte[4];
        dis.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Invalid file format: bad magic number");
        }
        
        short version = dis.readShort();
        if (version != FORMAT_VERSION) {
            throw new UnsupportedFormatException(version);
        }
        
        int dimensions = dis.readInt();
        int chunkCount = dis.readInt();
        long modelHash = dis.readLong();
        String modelId = dis.readUTF();
        
        // Read chunks
        int chunksJsonLength = dis.readInt();
        byte[] chunksJson = new byte[chunksJsonLength];
        dis.readFully(chunksJson);
        
        ObjectMapper mapper = new ObjectMapper();
        List<CodeChunk> chunks = mapper.readValue(chunksJson, 
            mapper.getTypeFactory().constructCollectionType(List.class, CodeChunk.class));
        
        // Create index
        IndexConfig config = IndexConfig.forModel(modelId, dimensions);
        InMemoryVectorIndex index = new InMemoryVectorIndex(config);
        
        // Read vectors
        for (int i = 0; i < chunkCount; i++) {
            float[] vector = new float[dimensions];
            for (int j = 0; j < dimensions; j++) {
                vector[j] = dis.readFloat();
            }
            index.add(chunks.get(i), vector);
        }
        
        return index;
    }
    
    // ==================== Metadata ====================
    
    @Override
    public String getModelId() {
        return config.modelId();
    }
    
    @Override
    public long getModelHash() {
        return config.modelId().hashCode();
    }
    
    @Override
    public int getDimensions() {
        return config.dimensions();
    }
    
    @Override
    public int size() {
        return chunks.size();
    }
    
    @Override
    public void close() {
        // No resources to release in memory implementation
    }
    
    // ==================== Helper Methods ====================
    
    private float cosineSimilarity(float[] a, float[] b) {
        float dotProduct = 0;
        float normA = 0;
        float normB = 0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        if (normA == 0 || normB == 0) return 0;
        return (float) (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB)));
    }
    
    private long estimateSizeBytes() {
        long vectorBytes = (long) vectors.size() * config.dimensions() * 4;
        long chunkEstimate = chunks.stream()
            .mapToLong(c -> c.code().length() + c.name().length() + c.file().length() + 100)
            .sum();
        return vectorBytes + chunkEstimate;
    }
    
    /**
     * Interface for embedding text queries.
     */
    @FunctionalInterface
    public interface EmbeddingProvider {
        float[] embed(String text);
    }
}
