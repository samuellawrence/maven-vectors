# Maven Vectors

**Distributable code embeddings for the Java ecosystem**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.maven-vectors/vectors-core.svg)](https://search.maven.org/search?q=g:io.github.maven-vectors)

---

## 🎯 What is Maven Vectors?

Maven Vectors brings **pre-computed code embeddings** to the Java ecosystem — just like `sources.jar` and `javadoc.jar`, but for semantic code search.

```
dependency.jar           → compiled bytecode
dependency-sources.jar   → source code
dependency-javadoc.jar   → documentation
dependency-vectors.jar   → code embeddings (NEW!)
```

### The Problem

Today, if you want AI-powered code search or RAG over your Java projects:
- You must generate embeddings at runtime (slow, requires GPU/API)
- Embeddings don't include your dependencies
- Every developer regenerates the same embeddings
- Enterprise environments can't call external embedding APIs

### The Solution

Maven Vectors generates embeddings **once at build time** and distributes them as Maven artifacts:

```xml
<!-- Your dependency automatically includes vectors -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-core</artifactId>
    <version>6.1.0</version>
</dependency>
```

```bash
# Query across your entire dependency tree
mvn vectors:query "how does Spring handle dependency injection"
```

---

## ✨ Features

- **🔌 Maven Integration** — Generate vectors with `mvn vectors:generate`
- **📦 Artifact Distribution** — Publish to Maven Central, Nexus, or Artifactory
- **🔗 Transitive Resolution** — Automatically merge vectors from all dependencies
- **🔍 Semantic Search** — Find code by meaning, not just keywords
- **🏠 Fully Offline** — No external API calls after vectors are generated
- **☕ Pure Java** — Query vectors with zero Python dependencies
- **🎯 Java-Optimized** — Uses code-specific embedding models (UniXcoder, CodeT5)

---

## 🚀 Quick Start

### 1. Add the Plugin

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.maven-vectors</groupId>
            <artifactId>vectors-maven-plugin</artifactId>
            <version>1.0.0</version>
        </plugin>
    </plugins>
</build>
```

### 2. Generate Vectors

```bash
# Generate vectors for your project
mvn vectors:generate

# Output: target/vectors/my-project-vectors.jar
```

### 3. Query Your Codebase

```bash
# Search across your project + all dependencies
mvn vectors:query "exception handling pattern"

# Or use the CLI
vectors-cli query ./my-project "singleton pattern"
```

### 4. Publish Vectors (Optional)

```bash
# Deploy vectors alongside your JAR
mvn vectors:deploy
```

---

## 📖 Usage

### Maven Goals

| Goal | Description |
|------|-------------|
| `vectors:generate` | Generate embeddings for current project |
| `vectors:merge` | Merge vectors from all dependencies |
| `vectors:query` | Search the merged vector index |
| `vectors:deploy` | Publish vectors to repository |
| `vectors:download` | Download vectors for dependencies |

### Configuration

```xml
<plugin>
    <groupId>io.github.maven-vectors</groupId>
    <artifactId>vectors-maven-plugin</artifactId>
    <version>1.0.0</version>
    <configuration>
        <!-- Embedding model (default: unixcoder-base) -->
        <model>microsoft/unixcoder-base</model>
        
        <!-- What to embed -->
        <includeClasses>true</includeClasses>
        <includeMethods>true</includeMethods>
        <includeJavadoc>true</includeJavadoc>
        
        <!-- Exclude patterns -->
        <excludes>
            <exclude>**/test/**</exclude>
            <exclude>**/generated/**</exclude>
        </excludes>
        
        <!-- Vector storage format -->
        <format>BINARY</format> <!-- BINARY | JSON -->
        
        <!-- Include dependency vectors in merge -->
        <includeDependencies>true</includeDependencies>
        <dependencyScopes>compile,runtime</dependencyScopes>
    </configuration>
</plugin>
```

### Programmatic API

```java
import io.maven.vectors.VectorIndex;
import io.maven.vectors.SearchResult;

// Load merged vectors
VectorIndex index = VectorIndex.load("target/vectors/merged-vectors.mvec");

// Semantic search
List<SearchResult> results = index.search("database connection pooling", 10);

for (SearchResult result : results) {
    System.out.println(result.getName());        // e.g., "HikariDataSource.getConnection()"
    System.out.println(result.getFile());        // e.g., "com/zaxxer/hikari/HikariDataSource.java"
    System.out.println(result.getSimilarity());  // e.g., 0.89
    System.out.println(result.getCode());        // Source code snippet
}

// Find anomalies
List<CodeChunk> anomalies = index.findAnomalies(0.3);

// Find duplicates
List<DuplicateGroup> duplicates = index.findDuplicates(0.95);
```

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        BUILD TIME                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │  Java Source │───▶│  AST Parser  │───▶│   Chunker    │      │
│  │    Files     │    │  (JavaParser)│    │              │      │
│  └──────────────┘    └──────────────┘    └──────┬───────┘      │
│                                                  │               │
│                                                  ▼               │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │   vectors    │◀───│  Embedding   │◀───│ Code Chunks  │      │
│  │     .jar     │    │    Model     │    │              │      │
│  └──────────────┘    │ (ONNX/Local) │    └──────────────┘      │
│         │            └──────────────┘                           │
│         ▼                                                        │
│  ┌──────────────┐                                               │
│  │ Maven Repo   │  (Central, Nexus, Artifactory)               │
│  └──────────────┘                                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        QUERY TIME                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │ Dependency   │───▶│   Resolve    │───▶│   Download   │      │
│  │   Tree       │    │   Vectors    │    │  -vectors.jar│      │
│  └──────────────┘    └──────────────┘    └──────┬───────┘      │
│                                                  │               │
│                                                  ▼               │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐      │
│  │   Search     │◀───│    Merged    │◀───│    Merge     │      │
│  │   Results    │    │    Index     │    │   Vectors    │      │
│  └──────────────┘    └──────────────┘    └──────────────┘      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Components

| Component | Description |
|-----------|-------------|
| `vectors-core` | Core library: vector format, indexing, search |
| `vectors-maven-plugin` | Maven plugin for generation and querying |
| `vectors-cli` | Standalone command-line tool |
| `vectors-gradle` | Gradle plugin (planned) |
| `vectors-embeddings` | Embedding model wrappers (ONNX, local) |

---

## 📄 Vector Artifact Format

Vector artifacts follow the `.mvec` format (Maven Vectors):

```
my-library-1.0.0-vectors.jar
├── META-INF/
│   ├── MANIFEST.MF
│   └── vectors/
│       ├── metadata.json      # Model info, version, stats
│       └── index.mvec         # Binary vector index
└── vectors/
    └── chunks.json            # Code chunks with metadata
```

### metadata.json

```json
{
    "format_version": "1.0",
    "model": "microsoft/unixcoder-base",
    "model_version": "1.0.0",
    "dimensions": 768,
    "generated_at": "2024-01-15T10:30:00Z",
    "source_artifact": {
        "groupId": "org.example",
        "artifactId": "my-library",
        "version": "1.0.0"
    },
    "stats": {
        "total_chunks": 1542,
        "classes": 89,
        "methods": 1203,
        "constructors": 156,
        "fields": 94
    }
}
```

### Embedding Model Compatibility

Vectors are **model-specific**. The format includes model metadata to ensure compatibility:

```java
// This will fail if models don't match
VectorIndex spring = VectorIndex.load("spring-vectors.jar");  // unixcoder-base
VectorIndex myProject = VectorIndex.load("my-vectors.jar");   // codet5-base

spring.merge(myProject); // ❌ IncompatibleModelException

// Use the same model for all vectors
VectorIndex compatible = VectorIndex.load("other-vectors.jar"); // unixcoder-base
spring.merge(compatible); // ✅ Works
```

---

## 🔧 Embedding Models

Maven Vectors supports multiple embedding models:

| Model | Size | Dimensions | Java Quality | Speed |
|-------|------|------------|--------------|-------|
| `microsoft/unixcoder-base` | 125M | 768 | ⭐⭐⭐⭐⭐ | Fast |
| `Salesforce/codet5-base` | 220M | 768 | ⭐⭐⭐⭐ | Medium |
| `microsoft/graphcodebert-base` | 125M | 768 | ⭐⭐⭐⭐ | Fast |
| `sentence-transformers/all-MiniLM-L6-v2` | 22M | 384 | ⭐⭐⭐ | Very Fast |

### Local Model Execution

Models run locally via ONNX Runtime — **no external API calls required**:

```xml
<configuration>
    <!-- Use local ONNX model -->
    <model>microsoft/unixcoder-base</model>
    <modelBackend>ONNX</modelBackend>
    
    <!-- Or use external API (optional) -->
    <modelBackend>OPENAI</modelBackend>
    <apiKey>${env.OPENAI_API_KEY}</apiKey>
</configuration>
```

---

## 🌐 Ecosystem Integration

### IDE Plugins (Planned)

- **IntelliJ IDEA** — Semantic search in Project view
- **VS Code** — Extension with search panel
- **Eclipse** — Plugin for code navigation

### CI/CD Integration

```yaml
# GitHub Actions example
- name: Generate Vectors
  run: mvn vectors:generate

- name: Publish Vectors
  run: mvn vectors:deploy
  env:
    MAVEN_USERNAME: ${{ secrets.MAVEN_USERNAME }}
    MAVEN_PASSWORD: ${{ secrets.MAVEN_PASSWORD }}
```

### RAG Integration

```java
// Use with LangChain4j
VectorIndex index = VectorIndex.load("merged-vectors.mvec");

// Convert to LangChain4j compatible store
EmbeddingStore<TextSegment> store = index.toLangChain4jStore();

// Use in RAG pipeline
ContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(store)
    .embeddingModel(embeddingModel)
    .maxResults(5)
    .build();
```

---

## 🗺️ Roadmap

### v1.0 (MVP)
- [x] Core vector format specification
- [ ] Maven plugin (generate, query)
- [ ] UniXcoder embedding support
- [ ] Binary vector index format
- [ ] Basic CLI tool

### v1.1
- [ ] Transitive dependency resolution
- [ ] Vector merging
- [ ] Javadoc embedding
- [ ] Anomaly detection

### v1.2
- [ ] Gradle plugin
- [ ] Multiple model support
- [ ] Incremental generation
- [ ] IDE plugin (IntelliJ)

### v2.0
- [ ] Distributed vector registry
- [ ] Pre-built vectors for popular libraries
- [ ] Cross-language support (Kotlin, Scala)
- [ ] Vector diffing between versions

---

## 🤝 Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

### Development Setup

```bash
# Clone the repository
git clone https://github.com/maven-vectors/maven-vectors.git
cd maven-vectors

# Build all modules
mvn clean install

# Run tests
mvn test

# Generate sample vectors
cd examples/sample-project
mvn vectors:generate
```

### Project Structure

```
maven-vectors/
├── vectors-core/           # Core library
│   ├── src/main/java/
│   │   └── io/maven/vectors/
│   │       ├── VectorIndex.java
│   │       ├── CodeChunk.java
│   │       ├── SearchResult.java
│   │       └── format/
│   └── pom.xml
├── vectors-maven-plugin/   # Maven plugin
│   ├── src/main/java/
│   │   └── io/maven/vectors/plugin/
│   │       ├── GenerateMojo.java
│   │       ├── QueryMojo.java
│   │       └── MergeMojo.java
│   └── pom.xml
├── vectors-embeddings/     # Embedding model support
│   ├── src/main/java/
│   │   └── io/maven/vectors/embeddings/
│   │       ├── EmbeddingModel.java
│   │       ├── OnnxEmbedding.java
│   │       └── models/
│   └── pom.xml
├── vectors-cli/            # Command-line tool
├── examples/               # Example projects
└── pom.xml                 # Parent POM
```

---

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

## 🙏 Acknowledgments

- [UniXcoder](https://github.com/microsoft/CodeBERT) — Microsoft's code embedding model
- [ONNX Runtime](https://onnxruntime.ai/) — Local model execution
- [JVector](https://github.com/jbellis/jvector) — High-performance vector search
- [JavaParser](https://javaparser.org/) — Java AST parsing

---

## 📬 Contact

- **GitHub Issues** — Bug reports and feature requests
- **Discussions** — Questions and ideas
- **Twitter** — [@mavenvectors](https://twitter.com/mavenvectors)

---

<p align="center">
  <b>Stop regenerating embeddings. Start shipping code.</b>
</p>
