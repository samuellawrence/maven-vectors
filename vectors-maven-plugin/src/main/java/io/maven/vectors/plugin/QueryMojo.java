package io.maven.vectors.plugin;

import io.maven.vectors.*;
import io.maven.vectors.embeddings.EmbeddingConfig;
import io.maven.vectors.embeddings.EmbeddingModel;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Searches for code patterns in the vector index.
 * 
 * <p>Usage: {@code mvn vectors:query -Dvectors.query="dependency injection"}</p>
 */
@Mojo(
    name = "query",
    requiresProject = true
)
public class QueryMojo extends AbstractMojo {
    
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    
    /**
     * The search query.
     */
    @Parameter(property = "vectors.query", required = true)
    private String query;
    
    /**
     * Number of results to return.
     */
    @Parameter(property = "vectors.top", defaultValue = "10")
    private int topK;
    
    /**
     * Filter by chunk type (CLASS, METHOD, CONSTRUCTOR, etc.).
     */
    @Parameter(property = "vectors.type")
    private String chunkType;
    
    /**
     * Embedding model (must match the one used for generation).
     */
    @Parameter(property = "vectors.model", defaultValue = "jina-code")
    private String model;
    
    /**
     * Path to the vector index file.
     */
    @Parameter(property = "vectors.index", defaultValue = "${project.build.directory}/vectors")
    private File indexDirectory;
    
    /**
     * Whether to show code snippets in results.
     */
    @Parameter(property = "vectors.showCode", defaultValue = "true")
    private boolean showCode;
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Searching for: " + query);
        
        try {
            // Find index file
            Path indexPath = findIndexFile();
            if (indexPath == null) {
                throw new MojoExecutionException(
                    "Vector index not found. Run 'mvn vectors:generate' first.");
            }
            
            getLog().info("Loading index from: " + indexPath);
            
            // Load index
            VectorIndex index = VectorIndex.load(indexPath);
            
            // Load embedding model for query embedding
            EmbeddingConfig embeddingConfig = EmbeddingConfig.defaults();
            try (EmbeddingModel embeddingModel = EmbeddingModel.load(model, embeddingConfig)) {
                
                // Set up embedding provider for text queries
                if (index instanceof InMemoryVectorIndex memIndex) {
                    memIndex.setEmbeddingProvider(embeddingModel::embed);
                }
                
                // Execute search
                List<SearchResult> results;
                if (chunkType != null && !chunkType.isEmpty()) {
                    ChunkType type = ChunkType.valueOf(chunkType.toUpperCase());
                    results = index.searchByType(query, type, topK);
                } else {
                    results = index.search(query, topK);
                }
                
                // Display results
                getLog().info("");
                getLog().info("Found " + results.size() + " results:");
                getLog().info("=".repeat(60));
                
                int rank = 1;
                for (SearchResult result : results) {
                    printResult(rank++, result);
                }
            }
            
            index.close();
            
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to search vectors", e);
        }
    }
    
    private Path findIndexFile() throws IOException {
        if (!indexDirectory.exists()) {
            return null;
        }
        
        // Look for .mvec files
        try (var files = Files.list(indexDirectory.toPath())) {
            return files
                .filter(p -> p.toString().endsWith(".mvec"))
                .findFirst()
                .orElse(null);
        }
    }
    
    private void printResult(int rank, SearchResult result) {
        CodeChunk chunk = result.chunk();
        
        getLog().info("");
        getLog().info(String.format("#%d [%.1f%%] %s %s",
            rank,
            result.similarity() * 100,
            chunk.type(),
            chunk.qualifiedName()));
        
        getLog().info("    File: " + chunk.file() + ":" + chunk.lineStart());
        
        if (showCode) {
            getLog().info("    " + "-".repeat(50));
            String preview = chunk.truncatedCode(200);
            for (String line : preview.split("\n")) {
                getLog().info("    " + line);
            }
        }
    }
}
