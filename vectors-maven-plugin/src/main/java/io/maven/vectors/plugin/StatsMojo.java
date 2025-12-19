package io.maven.vectors.plugin;

import io.maven.vectors.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Displays statistics about the vector index.
 * 
 * <p>Usage: {@code mvn vectors:stats}</p>
 */
@Mojo(
    name = "stats",
    requiresProject = true
)
public class StatsMojo extends AbstractMojo {
    
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    
    /**
     * Path to the vector index directory.
     */
    @Parameter(property = "vectors.index", defaultValue = "${project.build.directory}/vectors")
    private File indexDirectory;
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Path indexPath = findIndexFile();
            if (indexPath == null) {
                throw new MojoExecutionException(
                    "Vector index not found. Run 'mvn vectors:generate' first.");
            }
            
            getLog().info("Loading index from: " + indexPath);
            
            VectorIndex index = VectorIndex.load(indexPath);
            IndexStats stats = index.getStats();
            
            getLog().info("");
            getLog().info("Vector Index Statistics");
            getLog().info("=".repeat(40));
            getLog().info("Model: " + stats.modelId());
            getLog().info("Dimensions: " + stats.dimensions());
            getLog().info("Total chunks: " + stats.totalChunks());
            getLog().info("Source files: " + stats.fileCount());
            getLog().info("Index size: " + formatSize(stats.sizeBytes()));
            getLog().info("");
            getLog().info("Chunks by type:");
            stats.chunksByType().forEach((type, count) -> 
                getLog().info("  " + type + ": " + count));
            
            index.close();
            
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read index stats", e);
        }
    }
    
    private Path findIndexFile() throws IOException {
        if (!indexDirectory.exists()) {
            return null;
        }
        
        try (var files = Files.list(indexDirectory.toPath())) {
            return files
                .filter(p -> p.toString().endsWith(".mvec"))
                .findFirst()
                .orElse(null);
        }
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
