package io.maven.vectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Item;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HNSW-based implementation of VectorIndex for fast approximate nearest neighbor search.
 * 
 * <p>Uses the Hierarchical Navigable Small World (HNSW) algorithm which provides
 * O(log n) search complexity instead of O(n) brute-force search.</p>
 * 
 * <p>Recommended for indexes with more than 10,000 vectors.</p>
 */
public class HnswVectorIndex implements VectorIndex {


    private static final Logger log = LoggerFactory.getLogger(HnswVectorIndex.class);
    
    // Format constants
    private static final byte[] MAGIC = "MHNS".getBytes(); // Different magic for HNSW format
    private static final short FORMAT_VERSION = 1;
    
    // HNSW parameters
    private static final int DEFAULT_M = 16;              // Max connections per node
    private static final int DEFAULT_EF_CONSTRUCTION = 200; // Build-time quality
    private static final int DEFAULT_EF = 50;             // Query-time quality
    
    private final IndexConfig config;
    private final List<CodeChunk> chunks;
    private final Map<String, Integer> idToIndex;
    private HnswIndex<String, float[], CodeVectorItem, Float> hnswIndex;
    
    // Embedding provider for query-time embedding
    private EmbeddingProvider embeddingProvider;
    
    public HnswVectorIndex(IndexConfig config) {
        this(config, 100_000); // Default max items
    }
    
    public HnswVectorIndex(IndexConfig config, int maxItems) {
        this.config = config;
        this.chunks = new ArrayList<>();
        this.idToIndex = new HashMap<>();
        
        // Initialize HNSW index
        this.hnswIndex = HnswIndex.newBuilder(
                config.dimensions(),
                DistanceFunctions.FLOAT_COSINE_DISTANCE,
                maxItems
            )
            .withM(DEFAULT_M)
            .withEfConstruction(DEFAULT_EF_CONSTRUCTION)
            .withEf(DEFAULT_EF)
            .build();
        
        log.info("Created HNSW index: dims={}, maxItems={}", config.dimensions(), maxItems);
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
        idToIndex.put(chunk.id(), index);
        
        // Add to HNSW index
        CodeVectorItem item = new CodeVectorItem(chunk.id(), embedding, index);
        hnswIndex.add(item);
        
        log.debug("Added chunk to HNSW: {} (index={})", chunk.name(), index);
    }
    
    @Override
    public void addAll(List<VectorEntry> entries) {
        // Batch add for efficiency
        List<CodeVectorItem> items = new ArrayList<>(entries.size());
        
        for (VectorEntry entry : entries) {
            CodeChunk chunk = entry.chunk();
            float[] embedding = entry.embedding();
            
            if (embedding.length != config.dimensions()) {
                throw new IllegalArgumentException(String.format(
                    "Embedding dimension mismatch: expected %d, got %d",
                    config.dimensions(), embedding.length
                ));
            }
            
            int index = chunks.size();
            chunks.add(chunk);
            idToIndex.put(chunk.id(), index);
            items.add(new CodeVectorItem(chunk.id(), embedding, index));
        }
        
        // Batch add to HNSW (more efficient than individual adds)
        try {
            hnswIndex.addAll(items);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while adding items to HNSW index", e);
        }
        log.info("Batch added {} chunks to HNSW index", items.size());
    }
    
    @Override
    public void merge(VectorIndex other) {
        if (!Objects.equals(getModelId(), other.getModelId())) {
            throw new IncompatibleModelException(getModelId(), other.getModelId());
        }
        
        if (other instanceof HnswVectorIndex otherIndex) {
            for (int i = 0; i < otherIndex.chunks.size(); i++) {
                CodeChunk chunk = otherIndex.chunks.get(i);
                if (!idToIndex.containsKey(chunk.id())) {
                    // Need to get the vector from HNSW index
                    Optional<CodeVectorItem> item = otherIndex.hnswIndex.get(chunk.id());
                    item.ifPresent(cvi -> add(chunk, cvi.vector()));
                }
            }
        } else if (other instanceof InMemoryVectorIndex) {
            // Convert from brute-force index
            throw new UnsupportedOperationException(
                "Direct merge from InMemoryVectorIndex not supported. " +
                "Load as InMemoryVectorIndex first, then iterate and add."
            );
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
        
        // HNSW search returns nearest neighbors
        List<com.github.jelmerk.hnswlib.core.SearchResult<CodeVectorItem, Float>> hnswResults = 
            hnswIndex.findNearest(queryVector, topK);
        
        // Convert to our SearchResult format
        // Note: HNSW returns distance, we need similarity (1 - distance for cosine)
        return hnswResults.stream()
            .map(r -> {
                int chunkIndex = r.item().chunkIndex();
                float similarity = 1.0f - r.distance(); // Convert distance to similarity
                return SearchResult.of(chunks.get(chunkIndex), similarity);
            })
            .collect(Collectors.toList());
    }
    
    @Override
    public List<SearchResult> searchByType(String query, ChunkType type, int topK) {
        if (embeddingProvider == null) {
            throw new IllegalStateException("No embedding provider configured for text queries");
        }
        
        float[] queryVector = embeddingProvider.embed(query);
        
        // Search with larger K, then filter by type
        int searchK = Math.min(topK * 10, chunks.size());
        List<com.github.jelmerk.hnswlib.core.SearchResult<CodeVectorItem, Float>> hnswResults = 
            hnswIndex.findNearest(queryVector, searchK);
        
        return hnswResults.stream()
            .filter(r -> chunks.get(r.item().chunkIndex()).type() == type)
            .limit(topK)
            .map(r -> {
                int chunkIndex = r.item().chunkIndex();
                float similarity = 1.0f - r.distance();
                return SearchResult.of(chunks.get(chunkIndex), similarity);
            })
            .collect(Collectors.toList());
    }
    
    // ==================== Analysis ====================
    
    @Override
    public List<CodeChunk> findAnomalies(float threshold) {
        if (chunks.size() < 5) {
            return List.of();
        }
        
        List<CodeChunk> anomalies = new ArrayList<>();
        
        // For each chunk, find its nearest neighbors and compute average similarity
        for (int i = 0; i < chunks.size(); i++) {
            Optional<CodeVectorItem> itemOpt = hnswIndex.get(chunks.get(i).id());
            if (itemOpt.isEmpty()) continue;
            
            float[] vector = itemOpt.get().vector();
            
            // Find 10 nearest neighbors
            List<com.github.jelmerk.hnswlib.core.SearchResult<CodeVectorItem, Float>> neighbors = 
                hnswIndex.findNearest(vector, 11); // +1 because it includes itself
            
            // Calculate average similarity (skip self)
            float avgSimilarity = 0;
            int count = 0;
            for (var neighbor : neighbors) {
                if (neighbor.item().chunkIndex() != i) {
                    avgSimilarity += (1.0f - neighbor.distance());
                    count++;
                }
            }
            
            if (count > 0) {
                avgSimilarity /= count;
                if (avgSimilarity < threshold) {
                    anomalies.add(chunks.get(i));
                }
            }
        }
        
        return anomalies;
    }
    
    @Override
    public List<DuplicateGroup> findDuplicates(float threshold) {
        List<DuplicateGroup> groups = new ArrayList<>();
        Set<Integer> processed = new HashSet<>();
        
        float distanceThreshold = 1.0f - threshold; // Convert similarity to distance
        
        for (int i = 0; i < chunks.size(); i++) {
            if (processed.contains(i)) continue;
            
            Optional<CodeVectorItem> itemOpt = hnswIndex.get(chunks.get(i).id());
            if (itemOpt.isEmpty()) continue;
            
            float[] vector = itemOpt.get().vector();
            
            // Find very similar items
            List<com.github.jelmerk.hnswlib.core.SearchResult<CodeVectorItem, Float>> neighbors = 
                hnswIndex.findNearest(vector, 20);
            
            List<CodeChunk> group = new ArrayList<>();
            group.add(chunks.get(i));
            processed.add(i);
            
            for (var neighbor : neighbors) {
                int neighborIndex = neighbor.item().chunkIndex();
                if (neighborIndex != i && !processed.contains(neighborIndex)) {
                    if (neighbor.distance() <= distanceThreshold) {
                        group.add(chunks.get(neighborIndex));
                        processed.add(neighborIndex);
                    }
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
        dos.writeUTF(config.modelId());
        
        // Write chunks as JSON
        ObjectMapper mapper = new ObjectMapper();
        byte[] chunksJson = mapper.writeValueAsBytes(chunks);
        dos.writeInt(chunksJson.length);
        dos.write(chunksJson);
        
        // Write HNSW index
        ByteArrayOutputStream hnswBytes = new ByteArrayOutputStream();
        hnswIndex.save(hnswBytes);
        byte[] hnswData = hnswBytes.toByteArray();
        dos.writeInt(hnswData.length);
        dos.write(hnswData);
        
        dos.flush();
        log.info("Saved HNSW index: {} chunks, {} bytes HNSW data", chunks.size(), hnswData.length);
    }
    
    @Override
    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        save(baos);
        return baos.toByteArray();
    }
    
    public static HnswVectorIndex loadFrom(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return loadFrom(is);
        }
    }
    
    public static HnswVectorIndex loadFrom(InputStream is) throws IOException {
        DataInputStream dis = new DataInputStream(new BufferedInputStream(is));
        
        // Read and verify header
        byte[] magic = new byte[4];
        dis.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("Invalid file format: not an HNSW index (magic: " + new String(magic) + ")");
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
        List<CodeChunk> loadedChunks = mapper.readValue(chunksJson,
            mapper.getTypeFactory().constructCollectionType(List.class, CodeChunk.class));
        
        // Read HNSW index
        int hnswDataLength = dis.readInt();
        byte[] hnswData = new byte[hnswDataLength];
        dis.readFully(hnswData);
        
        // Create index
        IndexConfig config = IndexConfig.forModel(modelId, dimensions);
        HnswVectorIndex index = new HnswVectorIndex(config, Math.max(chunkCount * 2, 100_000));
        
        // Load HNSW from bytes
        index.hnswIndex = HnswIndex.load(new ByteArrayInputStream(hnswData));
        
        // Restore chunks and id mapping
        for (int i = 0; i < loadedChunks.size(); i++) {
            CodeChunk chunk = loadedChunks.get(i);
            index.chunks.add(chunk);
            index.idToIndex.put(chunk.id(), i);
        }
        
        log.info("Loaded HNSW index: {} chunks", index.chunks.size());
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
        // HNSW index doesn't need explicit closing
    }
    
    private long estimateSizeBytes() {
        long vectorBytes = (long) chunks.size() * config.dimensions() * 4;
        long hnswOverhead = (long) chunks.size() * DEFAULT_M * 8; // Approximate graph overhead
        long chunkEstimate = chunks.stream()
            .mapToLong(c -> c.code().length() + c.name().length() + c.file().length() + 100)
            .sum();
        return vectorBytes + hnswOverhead + chunkEstimate;
    }
    
    // ==================== HNSW Item Implementation ====================
    
    /**
     * Item wrapper for HNSW index.
     */
    private static class CodeVectorItem implements Item<String, float[]>, Serializable {
        private static final long serialVersionUID = 1L;
        
        private final String id;
        private final float[] vector;
        private final int chunkIndex;
        
        CodeVectorItem(String id, float[] vector, int chunkIndex) {
            this.id = id;
            this.vector = vector;
            this.chunkIndex = chunkIndex;
        }
        
        @Override
        public String id() {
            return id;
        }
        
        @Override
        public float[] vector() {
            return vector;
        }
        
        @Override
        public int dimensions() {
            return vector.length;
        }
        
        int chunkIndex() {
            return chunkIndex;
        }
    }
    
    /**
     * Interface for embedding text queries.
     */
    @FunctionalInterface
    public interface EmbeddingProvider {
        float[] embed(String text);
    }
}
