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
│  │  OnnxEmbedding    │ │ VoyageEmbedding │ │ SimpleEmbedding│         │
│  │  ✅ jina-code     │ │ ✅ voyage-code-3│ │ (testing)      │         │
│  │  ✅ unixcoder     │ │ ✅ voyage-3.5   │ │                │         │
│  │  ✅ all-MiniLM    │ │ (200M free)     │ │                │         │
│  └───────────────────┘ └─────────────────┘ └────────────────┘         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Code Preprocessing

The `CodePreprocessor` improves embedding quality by splitting identifiers:

| Input | Output |
|-------|--------|
| `cosineSimilarity` | `cosine Similarity` |
| `parse_file` | `parse file` |
| `XMLParser` | `XML Parser` |
| `log2` | `log 2` |

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
    static EmbeddingModel load(String modelId, EmbeddingConfig config);
}
```

#### Available Providers

| Provider | Models | Dimensions | Use Case |
|----------|--------|------------|----------|
| **ONNX (default)** | jina-code, bge-small-en, bge-base-en, all-MiniLM, nomic-embed-text, unixcoder | 384-768 | Local, offline, free |
| **Voyage AI** | voyage-code-3, voyage-3.5 | 1024 | Best quality, 200M tokens free |
| **Simple** | hash-based | configurable | Testing only |

#### ONNX Implementation (default)

```java
public class OnnxEmbeddingModel implements EmbeddingModel {
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final CodePreprocessor preprocessor;
    
    public OnnxEmbeddingModel(String modelId, EmbeddingConfig config) {
        // Auto-download from HuggingFace or use local cache
        Path modelDir = ModelDownloader.download(modelId);
        this.session = loadModel(modelDir);
        this.tokenizer = loadTokenizer(modelDir);
        this.preprocessor = config.preprocessCode() ? CodePreprocessor.defaults() : null;
    }
    
    @Override
    public float[] embed(String code) {
        String processed = preprocessor != null ? preprocessor.preprocess(code) : code;
        Encoding encoding = tokenizer.encode(processed);
        // Run ONNX inference...
        return extractMeanPooledEmbedding(result);
    }
}
```

#### Voyage AI Implementation (cloud)

```java
public class VoyageEmbeddingModel implements EmbeddingModel {
    private static final String API_URL = "https://api.voyageai.com/v1/embeddings";
    
    public VoyageEmbeddingModel(String modelId, EmbeddingConfig config) {
        this.apiKey = resolveApiKey(config); // env var or config
        this.modelId = modelId; // voyage-code-3, voyage-3.5, etc.
    }
    
    @Override
    public List<float[]> embedBatch(List<String> codes) {
        // Batch up to 128 texts per request
        // Uses input_type="document" for indexing, "query" for search
        return callVoyageAPI(codes, "document");
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
    
    @Parameter(property = "vectors.model", defaultValue = "jina-code")
    private String model;
    
    @Parameter(property = "vectors.provider", defaultValue = "onnx")
    private String provider;
    
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

1. **Offline by Default** — ONNX provider runs entirely offline (jina-code, unixcoder)
2. **Code Never Leaves Machine** — Local embeddings computed on your hardware
3. **Optional Cloud** — Voyage AI provider available when higher accuracy needed
4. **Artifact Signing** — Vectors can be signed like any Maven artifact
5. **Checksum Verification** — SHA-256 checksums for integrity

## Implementation Status

### Completed ✅

| Feature | Status | Notes |
|---------|--------|-------|
| ONNX Embeddings | ✅ | jina-code (default), bge-small-en, bge-base-en, all-MiniLM, nomic-embed-text |
| Voyage AI Provider | ✅ | voyage-code-3, 200M tokens free |
| Code Preprocessing | ✅ | CamelCase/snake_case splitting |
| CLI Tool | ✅ | index, query, stats, duplicates, anomalies, download |
| Maven Plugin | ✅ | generate, query, stats goals |
| Binary Format (.mvec) | ✅ | Brute-force format with MVEC magic |
| HNSW Index | ✅ | O(log n) approximate search, MHNS magic |
| Javadoc Extraction | ✅ | Prepends Javadoc to chunks for better search |
| searchByType() | ✅ | Filter by CLASS/METHOD/FIELD/etc |
| Duplicate Detection | ✅ | Find near-duplicate code patterns |
| Anomaly Detection | ✅ | Find outlier code patterns |

### Planned 🔮

| Feature | Priority | Notes |
|---------|----------|-------|
| Gradle Plugin | Medium | For Gradle-based projects |
| Incremental Updates | High | Only re-embed changed files |
| GPU Acceleration | Low | CUDA support for faster generation |
| Cross-Language | Low | Kotlin, Scala, Groovy support |
| IDE Plugins | Medium | IntelliJ, VS Code integration |

## Model Comparison

Benchmark results on code search queries:

| Model | Provider | Dimensions | Best For | Speed |
|-------|----------|------------|----------|-------|
| **jina-code** | ONNX | 768 | Code search (8K context) | Medium |
| **bge-small-en** | ONNX | 384 | Fast general-purpose | Very Fast |
| **bge-base-en** | ONNX | 768 | Higher quality general | Fast |
| **all-MiniLM-L6-v2** | ONNX | 384 | Smallest, fastest | Very Fast |
| **nomic-embed-text** | ONNX | 768 | Long context (8K) | Medium |
| **voyage-code-3** | API | 1024 | Best accuracy | ~500ms |

**Recommendation**:
- **Code search**: Use `jina-code` (default) - trained on 30+ programming languages
- **General purpose**: Use `bge-small-en` for speed or `bge-base-en` for quality
- **Maximum accuracy**: Use Voyage AI when quality is critical
