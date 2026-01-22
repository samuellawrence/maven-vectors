# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Maven Vectors distributes pre-computed code embeddings (semantic vectors) for Java projects as Maven artifacts (`.mvec` files). It enables AI-powered semantic code search without runtime embedding generation or external API calls.

## Build Commands

```bash
# Build all modules
mvn clean install

# Run all tests
mvn test

# Run a single test class
mvn test -pl vectors-core -Dtest=VectorIndexTest

# Run a single test method
mvn test -pl vectors-core -Dtest=VectorIndexTest#testSearch

# Build without tests
mvn clean install -DskipTests

# Generate vectors for a project (Maven plugin)
mvn vectors:generate

# Query vectors (Maven plugin)
mvn vectors:query -Dvectors.query="search term"

# Run CLI (after building)
java -jar vectors-cli/target/vectors-cli-1.0.0-SNAPSHOT.jar <command>

# CLI commands:
#   index <path> -o output.mvec     Generate embeddings for a project
#   query <index.mvec> "query"      Search for code patterns
#   stats <index.mvec>              Display index statistics
#   anomalies <index.mvec>          Find outlier code patterns
#   duplicates <index.mvec>         Find near-duplicate code
#   download -m <model>             Download embedding model
```

## Architecture

```
┌─────────────────────────────────────────────────────┐
│          USER INTERFACES                            │
│  Maven Plugin | CLI | Java API                      │
└────────────────┬────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────┐
│          vectors-core                               │
│  Parsing (JavaParser) │ Chunking │ Indexing │ Search│
└────────────────┬────────────────────────────────────┘
                 │
┌────────────────▼────────────────────────────────────┐
│        vectors-embeddings                           │
│  EmbeddingModel interface                           │
│  ├─ OnnxEmbeddingModel (jina-code, local ONNX)     │
│  ├─ VoyageEmbeddingModel (voyage-code-3, API)      │
│  └─ SimpleEmbeddingModel (hash-based fallback)     │
└─────────────────────────────────────────────────────┘
```

**Modules:**
- `vectors-core` - Core library: vector format, indexing (HNSW/brute-force), search, Java parsing
- `vectors-embeddings` - Embedding providers: ONNX (local), Voyage AI (cloud), simple (hash fallback)
- `vectors-maven-plugin` - Maven plugin with goals: `generate`, `query`, `stats`
- `vectors-cli` - Standalone CLI tool using PicoCLI

## Key Abstractions

**VectorIndex** (`vectors-core`): Main interface for creating, loading, searching, and persisting vector indexes.
- `VectorIndex.create()` - Brute-force index (exact search, good for <10K vectors)
- `VectorIndex.createHnsw()` - HNSW index (approximate search, recommended for >10K vectors)
- `VectorIndex.load()` - Auto-detects format via magic bytes (`MVEC` vs `MHNS`)

**EmbeddingModel** (`vectors-embeddings`): Interface for generating embeddings. Load via `EmbeddingModel.load(modelId, config)`. Backend selection via `EmbeddingConfig.backend()`: ONNX, VOYAGE, or SIMPLE.

**CodeChunk** (record): Represents a unit of code with metadata. Extracted by `JavaCodeChunker` using JavaParser AST traversal.

**ChunkType** (enum): CLASS, INTERFACE, ENUM, RECORD, METHOD, CONSTRUCTOR, FIELD, ANNOTATION

## Embedding Providers

| Provider | Backend | Models | Notes |
|----------|---------|--------|-------|
| ONNX | Local | jina-code (default) | No API key, runs offline |
| Voyage AI | Cloud | voyage-code-3, voyage-3.5 | Set `VOYAGE_API_KEY` env var |
| Simple | Local | hash-based | Fallback, no semantic meaning |

## Code Patterns

- **Records** (Java 17): Immutable data models (`CodeChunk`, `SearchResult`, `VectorEntry`, `IndexConfig`)
- **Factory methods**: `VectorIndex.create()`, `VectorIndex.createHnsw()`, `EmbeddingModel.load()`
- **Visitor pattern**: `JavaCodeChunker` extends `VoidVisitorAdapter` for AST traversal
- **Graceful degradation**: Falls back to hash-based embeddings when ONNX unavailable

## .mvec File Format

Binary format with magic bytes for format detection:
- `MVEC` - Brute-force index: Header → Metadata (gzipped JSON) → Chunks (msgpack) → Vectors (float32)
- `MHNS` - HNSW index: Header → Metadata → Chunks → Vectors → HNSW graph structure

## Testing


- JUnit 5 (Jupiter)
- Tests run on Java 17 and 21 in CI
- Module-specific tests: `mvn test -pl <module-name>`
