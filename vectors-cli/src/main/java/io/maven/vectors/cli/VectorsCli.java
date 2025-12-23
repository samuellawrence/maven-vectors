package io.maven.vectors.cli;

import io.maven.vectors.*;
import io.maven.vectors.embeddings.EmbeddingConfig;
import io.maven.vectors.embeddings.EmbeddingModel;
import io.maven.vectors.embeddings.ModelDownloader;
import io.maven.vectors.parser.JavaCodeChunker;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command-line interface for Maven Vectors.
 */
@Command(
    name = "vectors",
    mixinStandardHelpOptions = true,
    version = "maven-vectors 1.0.0",
    description = "Generate and query code embeddings for Java projects",
    subcommands = {
        VectorsCli.IndexCommand.class,
        VectorsCli.QueryCommand.class,
        VectorsCli.StatsCommand.class,
        VectorsCli.AnomaliesCommand.class,
        VectorsCli.DuplicatesCommand.class,
        VectorsCli.DownloadCommand.class
    }
)
public class VectorsCli implements Callable<Integer> {
    
    public static void main(String[] args) {
        int exitCode = new CommandLine(new VectorsCli()).execute(args);
        System.exit(exitCode);
    }
    
    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }
    
    /**
     * Index a Java project.
     */
    @Command(
        name = "index",
        description = "Generate vector embeddings for a Java project"
    )
    static class IndexCommand implements Callable<Integer> {
        
        @Parameters(index = "0", description = "Path to Java project")
        private Path projectPath;
        
        @Option(names = {"-o", "--output"}, description = "Output file path", defaultValue = "vectors.mvec")
        private Path outputPath;
        
        @Option(names = {"-m", "--model"}, description = "Embedding model", defaultValue = "jina-code")
        private String model;
        
        @Option(names = {"-p", "--provider"}, description = "Embedding provider: onnx, voyage", defaultValue = "onnx")
        private String provider;
        
        @Option(names = {"--api-key"}, description = "API key for cloud providers (or set VOYAGE_API_KEY env var)")
        private String apiKey;
        
        @Option(names = {"--include-tests"}, description = "Include test files")
        private boolean includeTests;
        
        @Override
        public Integer call() throws Exception {
            System.out.println("Indexing project: " + projectPath);
            System.out.println("Using model: " + model);
            System.out.println("Provider: " + provider);
            
            // Parse source files
            JavaCodeChunker chunker = new JavaCodeChunker();
            List<CodeChunk> chunks = chunker.parseDirectory(projectPath);
            
            System.out.println("Found " + chunks.size() + " code chunks");
            
            if (chunks.isEmpty()) {
                System.out.println("No code chunks to index");
                return 1;
            }
            
            // Configure embedding provider
            EmbeddingConfig config = createConfig(provider, apiKey);
            try (EmbeddingModel embeddingModel = EmbeddingModel.load(model, config)) {
                
                IndexConfig indexConfig = IndexConfig.forModel(model, embeddingModel.getDimensions());
                VectorIndex index = VectorIndex.create(indexConfig);
                
                System.out.println("Generating embeddings...");
                
                // Use batch embedding to minimize API calls (important for rate-limited APIs)
                List<String> codes = chunks.stream().map(CodeChunk::code).toList();
                List<float[]> embeddings = embeddingModel.embedBatch(codes);
                
                for (int i = 0; i < chunks.size(); i++) {
                    index.add(chunks.get(i), embeddings.get(i));
                }
                System.out.printf("Embedded %d chunks%n", chunks.size());
                
                // Save index
                Files.createDirectories(outputPath.getParent() != null ? outputPath.getParent() : Path.of("."));
                index.save(outputPath);
                
                System.out.println("Saved index to: " + outputPath);
                
                IndexStats stats = index.getStats();
                System.out.printf("Index size: %.1f KB%n", stats.sizeBytes() / 1024.0);
            }
            
            return 0;
        }
    }
    
    /**
     * Query the vector index.
     */
    @Command(
        name = "query",
        description = "Search for code patterns"
    )
    static class QueryCommand implements Callable<Integer> {
        
        @Parameters(index = "0", description = "Path to vector index file")
        private Path indexPath;
        
        @Parameters(index = "1", description = "Search query")
        private String query;
        
        @Option(names = {"-n", "--top"}, description = "Number of results", defaultValue = "10")
        private int topK;
        
        @Option(names = {"-m", "--model"}, description = "Embedding model", defaultValue = "jina-code")
        private String model;
        
        @Option(names = {"-p", "--provider"}, description = "Embedding provider: onnx, voyage", defaultValue = "onnx")
        private String provider;
        
        @Option(names = {"--api-key"}, description = "API key for cloud providers (or set VOYAGE_API_KEY env var)")
        private String apiKey;
        
        @Option(names = {"--show-code"}, description = "Show code snippets", defaultValue = "true")
        private boolean showCode;
        
        @Override
        public Integer call() throws Exception {
            System.out.println("Searching for: " + query);
            
            VectorIndex index = VectorIndex.load(indexPath);
            
            EmbeddingConfig config = createConfig(provider, apiKey);
            try (EmbeddingModel embeddingModel = EmbeddingModel.load(model, config)) {
                
                if (index instanceof InMemoryVectorIndex memIndex) {
                    memIndex.setEmbeddingProvider(embeddingModel::embed);
                }
                
                List<SearchResult> results = index.search(query, topK);
                
                System.out.println();
                System.out.println("Found " + results.size() + " results:");
                System.out.println("=".repeat(60));
                
                int rank = 1;
                for (SearchResult result : results) {
                    printResult(rank++, result, showCode);
                }
            }
            
            index.close();
            return 0;
        }
        
        private void printResult(int rank, SearchResult result, boolean showCode) {
            CodeChunk chunk = result.chunk();
            
            System.out.println();
            System.out.printf("#%d [%.1f%%] %s %s%n",
                rank,
                result.similarity() * 100,
                chunk.type(),
                chunk.qualifiedName());
            
            System.out.println("    File: " + chunk.file() + ":" + chunk.lineStart());
            
            if (showCode) {
                System.out.println("    " + "-".repeat(50));
                String preview = chunk.truncatedCode(200);
                for (String line : preview.split("\n")) {
                    System.out.println("    " + line);
                }
            }
        }
    }
    
    /**
     * Show index statistics.
     */
    @Command(
        name = "stats",
        description = "Display index statistics"
    )
    static class StatsCommand implements Callable<Integer> {
        
        @Parameters(index = "0", description = "Path to vector index file")
        private Path indexPath;
        
        @Override
        public Integer call() throws Exception {
            VectorIndex index = VectorIndex.load(indexPath);
            IndexStats stats = index.getStats();
            
            System.out.println();
            System.out.println("Vector Index Statistics");
            System.out.println("=".repeat(40));
            System.out.println("Model: " + stats.modelId());
            System.out.println("Dimensions: " + stats.dimensions());
            System.out.println("Total chunks: " + stats.totalChunks());
            System.out.println("Source files: " + stats.fileCount());
            System.out.printf("Index size: %.1f KB%n", stats.sizeBytes() / 1024.0);
            System.out.println();
            System.out.println("Chunks by type:");
            stats.chunksByType().forEach((type, count) -> 
                System.out.println("  " + type + ": " + count));
            
            index.close();
            return 0;
        }
    }
    
    /**
     * Find anomalous code patterns.
     */
    @Command(
        name = "anomalies",
        description = "Find code patterns that don't fit common patterns"
    )
    static class AnomaliesCommand implements Callable<Integer> {
        
        @Parameters(index = "0", description = "Path to vector index file")
        private Path indexPath;
        
        @Option(names = {"-t", "--threshold"}, description = "Anomaly threshold", defaultValue = "0.3")
        private float threshold;
        
        @Override
        public Integer call() throws Exception {
            VectorIndex index = VectorIndex.load(indexPath);
            List<CodeChunk> anomalies = index.findAnomalies(threshold);
            
            System.out.println();
            System.out.println("Found " + anomalies.size() + " anomalies:");
            System.out.println("=".repeat(60));
            
            for (CodeChunk chunk : anomalies) {
                System.out.println();
                System.out.printf("[%s] %s%n", chunk.type(), chunk.qualifiedName());
                System.out.println("  File: " + chunk.file() + ":" + chunk.lineStart());
            }
            
            index.close();
            return 0;
        }
    }
    
    /**
     * Find duplicate code patterns.
     */
    @Command(
        name = "duplicates",
        description = "Find near-duplicate code"
    )
    static class DuplicatesCommand implements Callable<Integer> {
        
        @Parameters(index = "0", description = "Path to vector index file")
        private Path indexPath;
        
        @Option(names = {"-t", "--threshold"}, description = "Similarity threshold", defaultValue = "0.95")
        private float threshold;
        
        @Override
        public Integer call() throws Exception {
            VectorIndex index = VectorIndex.load(indexPath);
            List<DuplicateGroup> groups = index.findDuplicates(threshold);
            
            System.out.println();
            System.out.println("Found " + groups.size() + " duplicate groups:");
            System.out.println("=".repeat(60));
            
            for (DuplicateGroup group : groups) {
                System.out.println();
                System.out.printf("[%d similar items]%n", group.count());
                for (CodeChunk chunk : group.chunks()) {
                    System.out.printf("  - %s (%s:%d)%n", 
                        chunk.qualifiedName(), chunk.file(), chunk.lineStart());
                }
            }
            
            index.close();
            return 0;
        }
    }
    
    /**
     * Download an embedding model.
     */
    @Command(
        name = "download",
        description = "Download an embedding model from HuggingFace"
    )
    static class DownloadCommand implements Callable<Integer> {
        
        @Option(names = {"-m", "--model"}, description = "Model to download", defaultValue = "jina-code")
        private String model;
        
        @Option(names = {"-d", "--dir"}, description = "Cache directory")
        private Path cacheDir;
        
        @Override
        public Integer call() throws Exception {
            Path targetDir = cacheDir;
            if (targetDir == null) {
                targetDir = Path.of(System.getProperty("user.home"), ".maven-vectors", "models");
            }
            
            System.out.println("Downloading model: " + model);
            System.out.println("Cache directory: " + targetDir);
            System.out.println();
            
            ModelDownloader downloader = new ModelDownloader(targetDir);
            
            if (downloader.isCached(model)) {
                System.out.println("Model already cached!");
                return 0;
            }
            
            try {
                Path modelDir = downloader.downloadModel(model);
                System.out.println();
                System.out.println("Model downloaded successfully to: " + modelDir);
                System.out.println();
                System.out.println("You can now use semantic embeddings with:");
                System.out.println("  vectors index <path> -m " + model + " -o output.mvec");
                return 0;
            } catch (Exception e) {
                System.err.println("Failed to download model: " + e.getMessage());
                return 1;
            }
        }
    }
    
    /**
     * Create embedding config for the specified provider.
     */
    private static EmbeddingConfig createConfig(String provider, String apiKey) {
        return switch (provider.toLowerCase()) {
            case "voyage", "voyage-ai", "voyageai" -> {
                EmbeddingConfig config = EmbeddingConfig.voyage();
                if (apiKey != null && !apiKey.isBlank()) {
                    yield config.withApiKey(apiKey);
                }
                yield config;
            }
            case "onnx", "local" -> EmbeddingConfig.onnx();
            case "simple", "hash" -> EmbeddingConfig.defaults();
            default -> throw new IllegalArgumentException("Unknown provider: " + provider + 
                ". Use: onnx, voyage");
        };
    }
}
