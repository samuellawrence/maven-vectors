package io.maven.vectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IndexMerger - cross-format merging, deduplication, and provenance tracking.
 */
class IndexMergerTest {

    private static final int DIMENSIONS = 128;
    private static final String MODEL_ID = "test-model";

    private IndexConfig config;
    private Random random;

    @BeforeEach
    void setUp() {
        config = IndexConfig.forModel(MODEL_ID, DIMENSIONS);
        random = new Random(42);
    }

    // ==================== Basic Merge Tests ====================

    @Test
    void testMergeTwoInMemoryIndexes() {
        InMemoryVectorIndex index1 = new InMemoryVectorIndex(config);
        index1.add(createChunk("method1"), randomEmbedding());
        index1.add(createChunk("method2"), randomEmbedding());

        InMemoryVectorIndex index2 = new InMemoryVectorIndex(config);
        index2.add(createChunk("method3"), randomEmbedding());
        index2.add(createChunk("method4"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        assertTrue(merger.addIndex(index1, "group:lib1:1.0"));
        assertTrue(merger.addIndex(index2, "group:lib2:1.0"));

        VectorIndex merged = merger.build();

        assertEquals(4, merged.size());
        assertInstanceOf(InMemoryVectorIndex.class, merged);
    }

    @Test
    void testMergeTwoHnswIndexes() {
        HnswVectorIndex index1 = new HnswVectorIndex(config, 100);
        index1.add(createChunk("method1"), randomEmbedding());
        index1.add(createChunk("method2"), randomEmbedding());

        HnswVectorIndex index2 = new HnswVectorIndex(config, 100);
        index2.add(createChunk("method3"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.HNSW, 1000);

        assertTrue(merger.addIndex(index1, "group:lib1:1.0"));
        assertTrue(merger.addIndex(index2, "group:lib2:1.0"));

        VectorIndex merged = merger.build();

        assertEquals(3, merged.size());
        assertInstanceOf(HnswVectorIndex.class, merged);
    }

    // ==================== Cross-Format Merge Tests ====================

    @Test
    void testCrossFormatMergeHnswAndInMemory() {
        HnswVectorIndex hnswIndex = new HnswVectorIndex(config, 100);
        hnswIndex.add(createChunk("hnsw.method1"), randomEmbedding());
        hnswIndex.add(createChunk("hnsw.method2"), randomEmbedding());

        InMemoryVectorIndex inMemIndex = new InMemoryVectorIndex(config);
        inMemIndex.add(createChunk("inmem.method1"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        assertTrue(merger.addIndex(hnswIndex, "group:hnsw-lib:1.0"));
        assertTrue(merger.addIndex(inMemIndex, "group:inmem-lib:1.0"));

        VectorIndex merged = merger.build();

        assertEquals(3, merged.size());
        assertInstanceOf(InMemoryVectorIndex.class, merged);
    }

    @Test
    void testCrossFormatMergeToHnswOutput() {
        InMemoryVectorIndex inMemIndex = new InMemoryVectorIndex(config);
        inMemIndex.add(createChunk("inmem.method1"), randomEmbedding());
        inMemIndex.add(createChunk("inmem.method2"), randomEmbedding());

        HnswVectorIndex hnswIndex = new HnswVectorIndex(config, 100);
        hnswIndex.add(createChunk("hnsw.method1"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.HNSW, 1000);

        assertTrue(merger.addIndex(inMemIndex, "group:inmem-lib:1.0"));
        assertTrue(merger.addIndex(hnswIndex, "group:hnsw-lib:1.0"));

        VectorIndex merged = merger.build();

        assertEquals(3, merged.size());
        assertInstanceOf(HnswVectorIndex.class, merged);
    }

    // ==================== Deduplication Tests ====================

    @Test
    void testDeduplicationByChunkId() {
        InMemoryVectorIndex index1 = new InMemoryVectorIndex(config);
        CodeChunk shared = createChunk("shared.method");
        index1.add(shared, randomEmbedding());
        index1.add(createChunk("unique1"), randomEmbedding());

        InMemoryVectorIndex index2 = new InMemoryVectorIndex(config);
        index2.add(shared, randomEmbedding()); // Same chunk ID
        index2.add(createChunk("unique2"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        merger.addIndex(index1, "group:lib1:1.0");
        merger.addIndex(index2, "group:lib2:1.0");

        VectorIndex merged = merger.build();

        // shared.method should only appear once
        assertEquals(3, merged.size());
    }

    // ==================== Model Compatibility Tests ====================

    @Test
    void testIncompatibleModelRejected() {
        InMemoryVectorIndex compatibleIndex = new InMemoryVectorIndex(config);
        compatibleIndex.add(createChunk("method1"), randomEmbedding());

        IndexConfig otherConfig = IndexConfig.forModel("different-model", DIMENSIONS);
        InMemoryVectorIndex incompatibleIndex = new InMemoryVectorIndex(otherConfig);
        incompatibleIndex.add(createChunk("method2"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        assertTrue(merger.addIndex(compatibleIndex, "group:compatible:1.0"));
        assertFalse(merger.addIndex(incompatibleIndex, "group:incompatible:1.0"));

        VectorIndex merged = merger.build();

        assertEquals(1, merged.size());
        assertEquals(List.of("group:incompatible:1.0"), merger.getSkippedArtifacts());
    }

    // ==================== Provenance Tests ====================

    @Test
    void testProvenanceMetadataStamped() {
        InMemoryVectorIndex index = new InMemoryVectorIndex(config);
        index.add(createChunk("method1"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        merger.addIndex(index, "com.example:my-lib:2.0.0");

        VectorIndex merged = merger.build();

        // Search for the entry and check provenance
        List<VectorEntry> entries = merged.entries();
        assertEquals(1, entries.size());
        assertEquals("com.example:my-lib:2.0.0", entries.get(0).chunk().getArtifact());
    }

    @Test
    void testMultipleArtifactProvenance() {
        InMemoryVectorIndex index1 = new InMemoryVectorIndex(config);
        index1.add(createChunk("lib1.method"), randomEmbedding());

        InMemoryVectorIndex index2 = new InMemoryVectorIndex(config);
        index2.add(createChunk("lib2.method"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        merger.addIndex(index1, "com.example:lib1:1.0");
        merger.addIndex(index2, "com.example:lib2:2.0");

        VectorIndex merged = merger.build();
        List<VectorEntry> entries = merged.entries();

        assertEquals(2, entries.size());
        // Both entries should have different artifact provenance
        assertTrue(entries.stream().anyMatch(e -> "com.example:lib1:1.0".equals(e.chunk().getArtifact())));
        assertTrue(entries.stream().anyMatch(e -> "com.example:lib2:2.0".equals(e.chunk().getArtifact())));
    }

    @Test
    void testSearchResultIncludesArtifactId() {
        InMemoryVectorIndex index = new InMemoryVectorIndex(config);
        float[] embedding = randomEmbedding();
        index.add(createChunk("method1"), embedding);

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        merger.addIndex(index, "com.example:my-lib:1.0");

        VectorIndex merged = merger.build();
        List<SearchResult> results = merged.search(embedding, 1);

        assertFalse(results.isEmpty());
        assertEquals("com.example:my-lib:1.0", results.get(0).artifactId());
    }

    // ==================== Edge Cases ====================

    @Test
    void testEmptyBuild() {
        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        VectorIndex merged = merger.build();

        assertEquals(0, merged.size());
    }

    @Test
    void testMergeEmptyIndex() {
        InMemoryVectorIndex emptyIndex = new InMemoryVectorIndex(config);
        InMemoryVectorIndex nonEmpty = new InMemoryVectorIndex(config);
        nonEmpty.add(createChunk("method1"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        merger.addIndex(emptyIndex, "group:empty:1.0");
        merger.addIndex(nonEmpty, "group:full:1.0");

        VectorIndex merged = merger.build();

        assertEquals(1, merged.size());
    }

    @Test
    void testGetPendingCount() {
        InMemoryVectorIndex index = new InMemoryVectorIndex(config);
        index.add(createChunk("method1"), randomEmbedding());
        index.add(createChunk("method2"), randomEmbedding());

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        assertEquals(0, merger.getPendingCount());
        merger.addIndex(index, "group:lib:1.0");
        assertEquals(2, merger.getPendingCount());
    }

    @Test
    void testLargeMerge() {
        InMemoryVectorIndex index1 = new InMemoryVectorIndex(config);
        for (int i = 0; i < 100; i++) {
            index1.add(createChunk("lib1.method" + i), randomEmbedding());
        }

        InMemoryVectorIndex index2 = new InMemoryVectorIndex(config);
        for (int i = 0; i < 150; i++) {
            index2.add(createChunk("lib2.method" + i), randomEmbedding());
        }

        IndexMerger merger = new IndexMerger(MODEL_ID, DIMENSIONS,
            IndexMerger.OutputFormat.IN_MEMORY, 1000);

        merger.addIndex(index1, "group:lib1:1.0");
        merger.addIndex(index2, "group:lib2:1.0");

        VectorIndex merged = merger.build();

        assertEquals(250, merged.size());
    }

    // ==================== Entries Method Tests ====================

    @Test
    void testInMemoryEntries() {
        InMemoryVectorIndex index = new InMemoryVectorIndex(config);
        float[] emb1 = randomEmbedding();
        float[] emb2 = randomEmbedding();
        index.add(createChunk("method1"), emb1);
        index.add(createChunk("method2"), emb2);

        List<VectorEntry> entries = index.entries();

        assertEquals(2, entries.size());
        assertEquals("method1", entries.get(0).chunk().name());
        assertEquals("method2", entries.get(1).chunk().name());
        // Verify vectors are cloned (not same reference)
        assertNotSame(emb1, entries.get(0).embedding());
        assertArrayEquals(emb1, entries.get(0).embedding(), 0.001f);
    }

    @Test
    void testHnswEntries() {
        HnswVectorIndex index = new HnswVectorIndex(config, 100);
        float[] emb1 = randomEmbedding();
        float[] emb2 = randomEmbedding();
        index.add(createChunk("method1"), emb1);
        index.add(createChunk("method2"), emb2);

        List<VectorEntry> entries = index.entries();

        assertEquals(2, entries.size());
        // Verify content matches (order may vary)
        assertTrue(entries.stream().anyMatch(e -> "method1".equals(e.chunk().name())));
        assertTrue(entries.stream().anyMatch(e -> "method2".equals(e.chunk().name())));
    }

    // ==================== CodeChunk Provenance Tests ====================

    @Test
    void testCodeChunkWithArtifact() {
        CodeChunk original = createChunk("method1");
        assertNull(original.getArtifact());

        CodeChunk stamped = original.withArtifact("com.example:lib:1.0");
        assertEquals("com.example:lib:1.0", stamped.getArtifact());

        // Original should be unchanged
        assertNull(original.getArtifact());

        // Other fields preserved
        assertEquals(original.id(), stamped.id());
        assertEquals(original.name(), stamped.name());
        assertEquals(original.type(), stamped.type());
        assertEquals(original.code(), stamped.code());
    }

    @Test
    void testCodeChunkWithArtifactPreservesMetadata() {
        CodeChunk original = new CodeChunk(
            "test:id:1", "method1", ChunkType.METHOD,
            "public void method1() {}", "Test.java", 1, 3,
            "TestClass", java.util.Map.of("key", "value")
        );

        CodeChunk stamped = original.withArtifact("group:art:1.0");

        assertEquals("value", stamped.metadata().get("key"));
        assertEquals("group:art:1.0", stamped.getArtifact());
    }

    // ==================== Helper Methods ====================

    private CodeChunk createChunk(String name) {
        return CodeChunk.of(
            name,
            ChunkType.METHOD,
            "public void " + name + "() { }",
            "TestFile.java",
            1,
            3
        );
    }

    private float[] randomEmbedding() {
        float[] embedding = new float[DIMENSIONS];
        float norm = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            embedding[i] = (float) random.nextGaussian();
            norm += embedding[i] * embedding[i];
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < DIMENSIONS; i++) {
            embedding[i] /= norm;
        }
        return embedding;
    }
}
