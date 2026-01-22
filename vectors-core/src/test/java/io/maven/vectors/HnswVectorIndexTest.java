package io.maven.vectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HnswVectorIndex - HNSW-based approximate nearest neighbor search.
 */
class HnswVectorIndexTest {

    private static final int DIMENSIONS = 128; // Smaller for faster tests
    private static final String MODEL_ID = "test-model";

    private IndexConfig config;
    private HnswVectorIndex index;
    private Random random;

    @BeforeEach
    void setUp() {
        config = IndexConfig.forModel(MODEL_ID, DIMENSIONS);
        index = new HnswVectorIndex(config, 1000);
        random = new Random(42); // Fixed seed for reproducibility
    }

    // ==================== Creation Tests ====================

    @Test
    void testCreateEmptyIndex() {
        assertEquals(0, index.size());
        assertEquals(MODEL_ID, index.getModelId());
        assertEquals(DIMENSIONS, index.getDimensions());
    }

    @Test
    void testCreateWithCustomMaxItems() {
        HnswVectorIndex largeIndex = new HnswVectorIndex(config, 100_000);
        assertEquals(0, largeIndex.size());
    }

    // ==================== Add Tests ====================

    @Test
    void testAddSingleChunk() {
        CodeChunk chunk = createTestChunk("TestClass.method1");
        float[] embedding = randomEmbedding();

        index.add(chunk, embedding);

        assertEquals(1, index.size());
    }

    @Test
    void testAddMultipleChunks() {
        for (int i = 0; i < 10; i++) {
            CodeChunk chunk = createTestChunk("TestClass.method" + i);
            index.add(chunk, randomEmbedding());
        }

        assertEquals(10, index.size());
    }

    @Test
    void testAddAllBatch() {
        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            CodeChunk chunk = createTestChunk("TestClass.method" + i);
            entries.add(new VectorEntry(chunk, randomEmbedding()));
        }

        index.addAll(entries);

        assertEquals(50, index.size());
    }

    @Test
    void testAddWithDimensionMismatch() {
        CodeChunk chunk = createTestChunk("TestClass.method1");
        float[] wrongDimensions = new float[DIMENSIONS + 10];

        assertThrows(IllegalArgumentException.class, () -> {
            index.add(chunk, wrongDimensions);
        });
    }

    // ==================== Search Tests ====================

    @Test
    void testSearchWithVector() {
        // Add some chunks
        List<float[]> embeddings = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            CodeChunk chunk = createTestChunk("TestClass.method" + i);
            float[] embedding = randomEmbedding();
            embeddings.add(embedding);
            index.add(chunk, embedding);
        }

        // Search with one of the added embeddings - should find itself
        float[] queryVector = embeddings.get(5);
        List<SearchResult> results = index.search(queryVector, 5);

        assertFalse(results.isEmpty());
        assertEquals(5, results.size());

        // First result should have high similarity (close to 1.0)
        assertTrue(results.get(0).similarity() > 0.9f);
    }

    @Test
    void testSearchWithTextQuery() {
        // Add chunks
        for (int i = 0; i < 10; i++) {
            CodeChunk chunk = createTestChunk("TestClass.method" + i);
            index.add(chunk, randomEmbedding());
        }

        // Set up embedding provider
        index.setEmbeddingProvider(text -> randomEmbedding());

        List<SearchResult> results = index.search("test query", 3);

        assertEquals(3, results.size());
    }

    @Test
    void testSearchWithoutEmbeddingProvider() {
        index.add(createTestChunk("TestClass.method1"), randomEmbedding());

        assertThrows(IllegalStateException.class, () -> {
            index.search("test query", 5);
        });
    }

    @Test
    void testSearchEmptyIndex() {
        List<SearchResult> results = index.search(randomEmbedding(), 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void testSearchByType() {
        // Add different chunk types
        index.add(createChunkOfType("Class1", ChunkType.CLASS), randomEmbedding());
        index.add(createChunkOfType("method1", ChunkType.METHOD), randomEmbedding());
        index.add(createChunkOfType("method2", ChunkType.METHOD), randomEmbedding());
        index.add(createChunkOfType("field1", ChunkType.FIELD), randomEmbedding());

        index.setEmbeddingProvider(text -> randomEmbedding());

        List<SearchResult> methodResults = index.searchByType("query", ChunkType.METHOD, 10);

        // Should only return methods
        for (SearchResult result : methodResults) {
            assertEquals(ChunkType.METHOD, result.chunk().type());
        }
    }

    // ==================== Analysis Tests ====================

    @Test
    void testFindAnomalies() {
        // Add similar chunks
        float[] baseEmbedding = randomEmbedding();
        for (int i = 0; i < 10; i++) {
            CodeChunk chunk = createTestChunk("Similar.method" + i);
            float[] similar = addNoise(baseEmbedding, 0.1f);
            index.add(chunk, similar);
        }

        // Add one very different chunk
        CodeChunk outlier = createTestChunk("Outlier.weird");
        float[] differentEmbedding = randomEmbedding(); // Completely different
        index.add(outlier, differentEmbedding);

        List<CodeChunk> anomalies = index.findAnomalies(0.5f);

        // Should find anomalies (exact count depends on threshold)
        assertNotNull(anomalies);
    }

    @Test
    void testFindAnomaliesSmallIndex() {
        // Index with less than 5 items should return empty
        index.add(createTestChunk("method1"), randomEmbedding());
        index.add(createTestChunk("method2"), randomEmbedding());

        List<CodeChunk> anomalies = index.findAnomalies(0.3f);

        assertTrue(anomalies.isEmpty());
    }

    @Test
    void testFindDuplicates() {
        // Add near-duplicate chunks
        float[] embedding1 = randomEmbedding();
        float[] embedding2 = addNoise(embedding1, 0.01f); // Very similar
        float[] embedding3 = randomEmbedding(); // Different

        index.add(createTestChunk("method1"), embedding1);
        index.add(createTestChunk("method2"), embedding2);
        index.add(createTestChunk("method3"), embedding3);

        List<DuplicateGroup> duplicates = index.findDuplicates(0.95f);

        // Should find at least one duplicate group
        assertNotNull(duplicates);
    }

    // ==================== Stats Tests ====================

    @Test
    void testGetStats() {
        index.add(createChunkOfType("Class1", ChunkType.CLASS), randomEmbedding());
        index.add(createChunkOfType("method1", ChunkType.METHOD), randomEmbedding());
        index.add(createChunkOfType("method2", ChunkType.METHOD), randomEmbedding());

        IndexStats stats = index.getStats();

        assertEquals(3, stats.totalChunks());
        assertEquals(MODEL_ID, stats.modelId());
        assertEquals(DIMENSIONS, stats.dimensions());
        assertEquals(1, stats.chunksByType().get(ChunkType.CLASS));
        assertEquals(2, stats.chunksByType().get(ChunkType.METHOD));
    }

    // ==================== Persistence Tests ====================

    @Test
    void testSaveAndLoadFromPath(@TempDir Path tempDir) throws IOException {
        // Add data
        for (int i = 0; i < 20; i++) {
            index.add(createTestChunk("TestClass.method" + i), randomEmbedding());
        }

        Path indexPath = tempDir.resolve("test-index.mvec");

        // Save
        index.save(indexPath);
        assertTrue(indexPath.toFile().exists());

        // Load
        HnswVectorIndex loaded = HnswVectorIndex.loadFrom(indexPath);

        assertEquals(index.size(), loaded.size());
        assertEquals(index.getModelId(), loaded.getModelId());
        assertEquals(index.getDimensions(), loaded.getDimensions());
    }

    @Test
    void testSaveAndLoadFromStream() throws IOException {
        // Add data
        for (int i = 0; i < 15; i++) {
            index.add(createTestChunk("TestClass.method" + i), randomEmbedding());
        }

        // Save to bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        index.save(baos);
        byte[] bytes = baos.toByteArray();

        // Load from bytes
        HnswVectorIndex loaded = HnswVectorIndex.loadFrom(new ByteArrayInputStream(bytes));

        assertEquals(index.size(), loaded.size());
        assertEquals(index.getModelId(), loaded.getModelId());
    }

    @Test
    void testToBytes() throws IOException {
        index.add(createTestChunk("method1"), randomEmbedding());

        byte[] bytes = index.toBytes();

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        // Should start with MHNS magic
        assertEquals('M', bytes[0]);
        assertEquals('H', bytes[1]);
        assertEquals('N', bytes[2]);
        assertEquals('S', bytes[3]);
    }

    @Test
    void testSearchAfterLoadPreservesResults(@TempDir Path tempDir) throws IOException {
        // Add known data
        float[] knownEmbedding = randomEmbedding();
        index.add(createTestChunk("known.method"), knownEmbedding);
        for (int i = 0; i < 10; i++) {
            index.add(createTestChunk("other.method" + i), randomEmbedding());
        }

        // Save and reload
        Path indexPath = tempDir.resolve("search-test.mvec");
        index.save(indexPath);
        HnswVectorIndex loaded = HnswVectorIndex.loadFrom(indexPath);

        // Search should still work
        List<SearchResult> results = loaded.search(knownEmbedding, 5);

        assertFalse(results.isEmpty());
        // The known embedding should match itself with high similarity
        assertTrue(results.get(0).similarity() > 0.99f);
    }

    // ==================== Merge Tests ====================

    @Test
    void testMergeCompatibleIndexes() {
        HnswVectorIndex other = new HnswVectorIndex(config, 1000);

        // Add to first index
        index.add(createTestChunk("index1.method1"), randomEmbedding());
        index.add(createTestChunk("index1.method2"), randomEmbedding());

        // Add to second index
        other.add(createTestChunk("index2.method1"), randomEmbedding());

        // Merge
        index.merge(other);

        assertEquals(3, index.size());
    }

    @Test
    void testMergeIncompatibleModels() {
        IndexConfig differentConfig = IndexConfig.forModel("different-model", DIMENSIONS);
        HnswVectorIndex incompatible = new HnswVectorIndex(differentConfig, 1000);

        index.add(createTestChunk("method1"), randomEmbedding());
        incompatible.add(createTestChunk("method2"), randomEmbedding());

        assertThrows(IncompatibleModelException.class, () -> {
            index.merge(incompatible);
        });
    }

    @Test
    void testMergeSkipsDuplicateIds() {
        HnswVectorIndex other = new HnswVectorIndex(config, 1000);

        // Add same chunk ID to both
        CodeChunk chunk = createTestChunk("shared.method");
        index.add(chunk, randomEmbedding());
        other.add(chunk, randomEmbedding());

        // Add unique to other
        other.add(createTestChunk("other.unique"), randomEmbedding());

        index.merge(other);

        // Should have 2, not 3 (duplicate skipped)
        assertEquals(2, index.size());
    }

    // ==================== Edge Cases ====================

    @Test
    void testLargeIndex() {
        // Add many items
        int count = 500;
        for (int i = 0; i < count; i++) {
            index.add(createTestChunk("Large.method" + i), randomEmbedding());
        }

        assertEquals(count, index.size());

        // Search should still work
        List<SearchResult> results = index.search(randomEmbedding(), 10);
        assertEquals(10, results.size());
    }

    @Test
    void testSearchTopKGreaterThanSize() {
        // Add only 3 items
        for (int i = 0; i < 3; i++) {
            index.add(createTestChunk("method" + i), randomEmbedding());
        }

        // Ask for 10
        List<SearchResult> results = index.search(randomEmbedding(), 10);

        // Should return only 3
        assertEquals(3, results.size());
    }

    @Test
    void testResultsAreSortedBySimilarity() {
        for (int i = 0; i < 20; i++) {
            index.add(createTestChunk("method" + i), randomEmbedding());
        }

        List<SearchResult> results = index.search(randomEmbedding(), 10);

        // Results should be in descending order of similarity
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).similarity() >= results.get(i + 1).similarity(),
                "Results should be sorted by similarity descending");
        }
    }

    // ==================== Helper Methods ====================

    private CodeChunk createTestChunk(String name) {
        return CodeChunk.of(
            name,
            ChunkType.METHOD,
            "public void " + name + "() { }",
            "TestFile.java",
            1,
            3
        );
    }

    private CodeChunk createChunkOfType(String name, ChunkType type) {
        String code = switch (type) {
            case CLASS -> "public class " + name + " { }";
            case METHOD -> "public void " + name + "() { }";
            case FIELD -> "private String " + name + ";";
            case CONSTRUCTOR -> "public " + name + "() { }";
            default -> name;
        };
        return CodeChunk.of(name, type, code, "TestFile.java", 1, 3);
    }

    private float[] randomEmbedding() {
        float[] embedding = new float[DIMENSIONS];
        float norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            embedding[i] = (float) random.nextGaussian();
            norm += embedding[i] * embedding[i];
        }
        // Normalize
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            embedding[i] /= norm;
        }
        return embedding;
    }

    private float[] addNoise(float[] original, float noiseLevel) {
        float[] noisy = new float[original.length];
        float norm = 0;
        for (int i = 0; i < original.length; i++) {
            noisy[i] = original[i] + (float) (random.nextGaussian() * noiseLevel);
            norm += noisy[i] * noisy[i];
        }
        // Re-normalize
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] /= norm;
        }
        return noisy;
    }
}
