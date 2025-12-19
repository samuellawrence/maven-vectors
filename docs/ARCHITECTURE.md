# Maven Vectors Architecture

## Overview

Maven Vectors is designed as a modular system with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           USER INTERFACES                                │
├─────────────────┬─────────────────┬─────────────────┬───────────────────┤
│  Maven Plugin   │   Gradle Plugin │      CLI        │   Java API        │
│  (vectors:*)    │   (planned)     │   vectors-cli   │   VectorIndex     │
└────────┬────────┴────────┬────────┴────────┬────────┴─────────┬─────────┘
         │                 │                 │                   │
         └─────────────────┴────────┬────────┴───────────────────┘
                                    │
┌───────────────────────────────────┴─────────────────────────────────────┐
│                           VECTORS-CORE                                   │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │   Parsing   │  │  Chunking   │  │  Indexing   │  │  Searching  │    │
│  │             │  │             │  │             │  │             │    │
│  │ JavaParser  │  │ CodeChunker │  │ VectorIndex │  │ Similarity  │    │
│  │ AST Walker  │  │ Strategies  │  │ Storage     │  │ Ranking     │    │
│  └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘    │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │
┌───────────────────────────────────┴─────────────────────────────────────┐
│                        VECTORS-EMBEDDINGS                                │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │                    EmbeddingModel Interface                      │   │
│  └───────────┬───────────────────┬───────────────────┬─────────────┘   │
│              │                   │                   │                  │
│  ┌───────────▼───────┐ ┌────────▼────────┐ ┌───────▼────────┐         │
│  │  OnnxEmbedding    │ │ OpenAIEmbedding │ │ LocalEmbedding │         │
│  │  (UniXcoder,      │ │ (API-based)     │ │ (Custom)       │         │
│  │   CodeT5, etc.)   │ │                 │ │                │         │
│  └───────────────────┘ └─────────────────┘ └────────────────┘         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Module Breakdown

### 1. vectors-core

The foundation library containing:

#### 1.1 Data Models

```java
// Core data structures
public record CodeChunk(
    String id,
    String name,
    ChunkType type,          // CLASS, METHOD, CONSTRUCTOR, FIELD, INTERFACE
    String code,
    String file,
    int lineStart,
    int lineEnd,
    String parentClass,
    Map<String, String> metadata
) {}

public record VectorEntry(
    CodeChunk chunk,
    float[] embedding
) {}

public record SearchResult(
    CodeChunk chunk,
    float similarity,
    String artifactId       // Which dependency this came from
) {}
```

#### 1.2 Vector Index

```java
public interface VectorIndex {
    // Creation
    static VectorIndex create(IndexConfig config);
    static VectorIndex load(Path path);
    static VectorIndex load(InputStream is);
    
    // Modification
    void add(CodeChunk chunk, float[] embedding);
    void addAll(List<VectorEntry> entries);
    void merge(VectorIndex other);
    
    // Search
    List<SearchResult> search(String query, int topK);
    List<SearchResult> search(float[] queryVector, int topK);
    List<SearchResult> searchByType(String query, ChunkType type, int topK);
    
    // Analysis
    List<CodeChunk> findAnomalies(float threshold);
    List<DuplicateGroup> findDuplicates(float threshold);
    IndexStats getStats();
    
    // Persistence
    void save(Path path);
    void save(OutputStream os);
    byte[] toBytes();
}
```

#### 1.3 Storage Formats

**Binary Format (.mvec)** — Optimized for size and speed:

```
┌─────────────────────────────────────────┐
│ Header (32 bytes)                       │
├─────────────────────────────────────────┤
│ Magic Number    │ 4 bytes │ "MVEC"      │
│ Version         │ 2 bytes │ 1.0         │
│ Dimensions      │ 2 bytes │ 768         │
│ Entry Count     │ 4 bytes │ N           │
│ Model Hash      │ 8 bytes │ xxhash      │
│ Flags           │ 4 bytes │ options     │
│ Reserved        │ 8 bytes │             │
├─────────────────────────────────────────┤
│ Metadata Section (variable)             │
├─────────────────────────────────────────┤
│ Length          │ 4 bytes │             │
│ JSON Metadata   │ N bytes │ gzipped     │
├─────────────────────────────────────────┤
│ Chunks Section (variable)               │
├─────────────────────────────────────────┤
│ Chunk Count     │ 4 bytes │             │
│ Chunk 1 Length  │ 4 bytes │             │
│ Chunk 1 Data    │ N bytes │ msgpack     │
│ ...             │         │             │
├─────────────────────────────────────────┤
│ Vectors Section (fixed per entry)       │
├─────────────────────────────────────────┤
│ Vector 1        │ D×4 bytes │ float32   │
│ Vector 2        │ D×4 bytes │ float32   │
│ ...             │           │           │
├─────────────────────────────────────────┤
│ Index Section (HNSW graph)              │
├─────────────────────────────────────────┤
│ HNSW Parameters │ 16 bytes  │           │
│ Graph Data      │ N bytes   │           │
└─────────────────────────────────────────┘
```

### 2. vectors-embeddings

Pluggable embedding backends:

```java
public interface EmbeddingModel extends AutoCloseable {
    // Core embedding
    float[] embed(String code);
    List<float[]> embedBatch(List<String> codes);
    
    // Metadata
    String getModelId();
    int getDimensions();
    long getModelHash();
    
    // Factory
    static EmbeddingModel load(String modelId, ModelConfig config);
}

// ONNX implementation (default)
public class OnnxEmbeddingModel implements EmbeddingModel {
    private final OrtSession session;
    private final Tokenizer tokenizer;
    
    public OnnxEmbeddingModel(String modelId) {
        // Load ONNX model from HuggingFace or local cache
        this.session = loadModel(modelId);
        this.tokenizer = loadTokenizer(modelId);
    }
    
    @Override
    public float[] embed(String code) {
        long[] tokens = tokenizer.encode(code);
        OnnxTensor input = OnnxTensor.createTensor(env, tokens);
        OrtSession.Result result = session.run(Map.of("input_ids", input));
        return extractEmbedding(result);
    }
}
```

### 3. vectors-maven-plugin

Maven lifecycle integration:

```java
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PACKAGE)
public class GenerateMojo extends AbstractMojo {
    
    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;
    
    @Parameter(property = "vectors.model", defaultValue = "microsoft/unixcoder-base")
    private String model;
    
    @Parameter(property = "vectors.include.classes", defaultValue = "true")
    private boolean includeClasses;
    
    @Parameter(property = "vectors.include.methods", defaultValue = "true")
    private boolean includeMethods;
    
    @Override
    public void execute() throws MojoExecutionException {
        // 1. Parse source files
        List<CodeChunk> chunks = parseSourceFiles();
        
        // 2. Generate embeddings
        EmbeddingModel embedder = EmbeddingModel.load(model);
        VectorIndex index = VectorIndex.create(config);
        
        for (CodeChunk chunk : chunks) {
            float[] embedding = embedder.embed(chunk.code());
            index.add(chunk, embedding);
        }
        
        // 3. Save to target directory
        Path outputPath = getOutputPath();
        index.save(outputPath);
        
        // 4. Attach as artifact
        projectHelper.attachArtifact(project, "jar", "vectors", outputPath.toFile());
    }
}

@Mojo(name = "merge", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class MergeMojo extends AbstractMojo {
    
    @Override
    public void execute() throws MojoExecutionException {
        VectorIndex merged = VectorIndex.create(config);
        
        // 1. Load project vectors
        merged.merge(VectorIndex.load(projectVectorsPath));
        
        // 2. Resolve and merge dependency vectors
        for (Artifact artifact : project.getArtifacts()) {
            Artifact vectorArtifact = resolveVectorArtifact(artifact);
            if (vectorArtifact != null) {
                merged.merge(VectorIndex.load(vectorArtifact.getFile()));
            }
        }
        
        // 3. Save merged index
        merged.save(mergedOutputPath);
    }
}

@Mojo(name = "query")
public class QueryMojo extends AbstractMojo {
    
    @Parameter(property = "vectors.query", required = true)
    private String query;
    
    @Parameter(property = "vectors.top", defaultValue = "10")
    private int topK;
    
    @Override
    public void execute() throws MojoExecutionException {
        VectorIndex index = VectorIndex.load(mergedIndexPath);
        List<SearchResult> results = index.search(query, topK);
        
        for (SearchResult result : results) {
            getLog().info(formatResult(result));
        }
    }
}
```

## Data Flow

### Generation Flow

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│    Source    │     │     AST      │     │    Code      │
│    Files     │────▶│    Parser    │────▶│   Chunks     │
│   (*.java)   │     │ (JavaParser) │     │              │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │
                     ┌──────────────┐             │
                     │  Embedding   │◀────────────┘
                     │    Model     │
                     │   (ONNX)     │
                     └──────┬───────┘
                            │
                     ┌──────▼───────┐     ┌──────────────┐
                     │   Vector     │     │   vectors    │
                     │    Index     │────▶│     .jar     │
                     │              │     │              │
                     └──────────────┘     └──────────────┘
```

### Query Flow

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│    Query     │     │  Embedding   │     │    Query     │
│    Text      │────▶│    Model     │────▶│   Vector     │
│              │     │              │     │              │
└──────────────┘     └──────────────┘     └──────┬───────┘
                                                  │
┌──────────────┐     ┌──────────────┐             │
│   Search     │     │    HNSW      │◀────────────┘
│   Results    │◀────│    Search    │
│              │     │              │
└──────────────┘     └──────────────┘
```

### Merge Flow

```
┌──────────────┐
│   Project    │
│   Vectors    │────┐
└──────────────┘    │
                    │     ┌──────────────┐     ┌──────────────┐
┌──────────────┐    │     │              │     │    Merged    │
│  Dep A       │────┼────▶│    Merge     │────▶│    Index     │
│  Vectors     │    │     │   Algorithm  │     │              │
└──────────────┘    │     │              │     └──────────────┘
                    │     └──────────────┘
┌──────────────┐    │
│  Dep B       │────┘
│  Vectors     │
└──────────────┘
```

## Search Algorithm

Maven Vectors uses **HNSW (Hierarchical Navigable Small Worlds)** for efficient approximate nearest neighbor search:

```java
public class HnswIndex implements VectorIndex {
    
    private final int M = 16;              // Max connections per node
    private final int efConstruction = 200; // Build-time quality
    private final int efSearch = 50;        // Query-time quality
    
    // Multi-layer graph structure
    private final List<Map<Integer, List<Integer>>> layers;
    private final float[][] vectors;
    
    @Override
    public List<SearchResult> search(float[] query, int topK) {
        // Start from top layer, navigate down
        int entryPoint = findEntryPoint();
        
        for (int layer = layers.size() - 1; layer > 0; layer--) {
            entryPoint = searchLayer(query, entryPoint, 1, layer).get(0);
        }
        
        // Detailed search in bottom layer
        List<Integer> candidates = searchLayer(query, entryPoint, efSearch, 0);
        
        // Return top-K results
        return candidates.stream()
            .limit(topK)
            .map(id -> new SearchResult(chunks.get(id), similarity(query, vectors[id])))
            .collect(toList());
    }
}
```

## Versioning & Compatibility

### Model Compatibility

Vectors are only compatible when generated with the same model:

```java
public class ModelCompatibility {
    
    public static void ensureCompatible(VectorIndex a, VectorIndex b) {
        if (a.getModelHash() != b.getModelHash()) {
            throw new IncompatibleModelException(
                "Cannot merge indexes with different models: " +
                a.getModelId() + " vs " + b.getModelId()
            );
        }
    }
}
```

### Format Versioning

The `.mvec` format includes version information for forward compatibility:

```java
public class FormatVersion {
    public static final int CURRENT = 1;
    
    public static VectorIndex load(Path path) {
        int version = readVersion(path);
        
        return switch (version) {
            case 1 -> loadV1(path);
            // Future versions
            default -> throw new UnsupportedFormatException(version);
        };
    }
}
```

## Performance Considerations

### Memory Usage

| Component | Memory (per 1000 chunks) |
|-----------|--------------------------|
| Vectors (768d, float32) | ~3 MB |
| HNSW Graph | ~1 MB |
| Chunk Metadata | ~2 MB |
| **Total** | **~6 MB** |

### Build Time

| Project Size | Chunks | Generation Time* |
|--------------|--------|------------------|
| Small (10 classes) | ~100 | ~5s |
| Medium (100 classes) | ~1,000 | ~30s |
| Large (1000 classes) | ~10,000 | ~5min |

*Using ONNX on CPU. GPU acceleration available.

### Query Time

| Index Size | Query Time (avg) |
|------------|------------------|
| 1,000 vectors | <1ms |
| 10,000 vectors | ~2ms |
| 100,000 vectors | ~10ms |
| 1,000,000 vectors | ~50ms |

## Security Considerations

1. **No External API Calls** — Default configuration runs entirely offline
2. **Code Never Leaves Machine** — Embeddings computed locally
3. **Artifact Signing** — Vectors can be signed like any Maven artifact
4. **Checksum Verification** — SHA-256 checksums for integrity

## Future Extensions

### Planned Features

1. **Incremental Updates** — Only re-embed changed files
2. **GPU Acceleration** — CUDA support for faster generation
3. **Compression** — Product quantization for smaller artifacts
4. **Streaming** — Process large codebases without loading all into memory
5. **Cross-Language** — Kotlin, Scala, Groovy support
6. **Vector Diffing** — Compare embeddings between versions
