package io.maven.vectors.plugin;

import io.maven.vectors.*;
import io.maven.vectors.embeddings.EmbeddingConfig;
import io.maven.vectors.embeddings.EmbeddingModel;
import io.maven.vectors.parser.JavaCodeChunker;
import io.maven.vectors.parser.JavaCodeChunker.ChunkerConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates vector embeddings for Java source code.
 * 
 * <p>Usage: {@code mvn vectors:generate}</p>
 */
@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.PACKAGE,
    requiresDependencyResolution = ResolutionScope.COMPILE
)
public class GenerateMojo extends AbstractMojo {
    
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    
    @Component
    private MavenProjectHelper projectHelper;
    
    /**
     * Embedding model to use.
     */
    @Parameter(property = "vectors.model", defaultValue = "jina-code")
    private String model;
    
    /**
     * Whether to include class-level chunks.
     */
    @Parameter(property = "vectors.include.classes", defaultValue = "true")
    private boolean includeClasses;
    
    /**
     * Whether to include method-level chunks.
     */
    @Parameter(property = "vectors.include.methods", defaultValue = "true")
    private boolean includeMethods;
    
    /**
     * Whether to include constructor chunks.
     */
    @Parameter(property = "vectors.include.constructors", defaultValue = "true")
    private boolean includeConstructors;
    
    /**
     * Whether to include field chunks.
     */
    @Parameter(property = "vectors.include.fields", defaultValue = "false")
    private boolean includeFields;

    /**
     * Whether to include Javadoc in chunks for better search.
     */
    @Parameter(property = "vectors.include.javadoc", defaultValue = "true")
    private boolean includeJavadoc;

    /**
     * Output directory for generated vectors.
     */
    @Parameter(property = "vectors.outputDirectory", defaultValue = "${project.build.directory}/vectors")
    private File outputDirectory;
    
    /**
     * Whether to attach vectors as a Maven artifact.
     */
    @Parameter(property = "vectors.attach", defaultValue = "true")
    private boolean attachArtifact;
    
    /**
     * Skip vector generation.
     */
    @Parameter(property = "vectors.skip", defaultValue = "false")
    private boolean skip;
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping vector generation");
            return;
        }
        
        getLog().info("Generating vectors for " + project.getArtifactId());
        getLog().info("Using model: " + model);
        
        try {
            // Get source directories
            List<String> sourceRoots = project.getCompileSourceRoots();
            if (sourceRoots.isEmpty()) {
                getLog().warn("No source directories found");
                return;
            }
            
            // Configure chunker
            ChunkerConfig chunkerConfig = new ChunkerConfig(
                includeClasses,
                includeMethods,
                includeConstructors,
                includeFields,
                includeJavadoc,
                50,     // minChunkSize
                3000,   // maxChunkSize
                List.of("test", "generated")
            );
            
            JavaCodeChunker chunker = new JavaCodeChunker(chunkerConfig);
            
            // Parse all source files
            List<CodeChunk> allChunks = new java.util.ArrayList<>();
            for (String sourceRoot : sourceRoots) {
                Path sourcePath = Path.of(sourceRoot);
                if (Files.exists(sourcePath)) {
                    getLog().info("Parsing sources in: " + sourcePath);
                    List<CodeChunk> chunks = chunker.parseDirectory(sourcePath);
                    allChunks.addAll(chunks);
                }
            }
            
            getLog().info("Found " + allChunks.size() + " code chunks");
            
            if (allChunks.isEmpty()) {
                getLog().warn("No code chunks to index");
                return;
            }
            
            // Initialize embedding model
            getLog().info("Loading embedding model...");
            EmbeddingConfig embeddingConfig = EmbeddingConfig.defaults();
            
            try (EmbeddingModel embeddingModel = EmbeddingModel.load(model, embeddingConfig)) {
                
                // Create index
                IndexConfig indexConfig = IndexConfig.forModel(model, embeddingModel.getDimensions());
                VectorIndex index = VectorIndex.create(indexConfig);
                
                // Generate embeddings
                getLog().info("Generating embeddings...");
                int progress = 0;
                for (CodeChunk chunk : allChunks) {
                    float[] embedding = embeddingModel.embed(chunk.code());
                    index.add(chunk, embedding);
                    
                    progress++;
                    if (progress % 100 == 0) {
                        getLog().info("Progress: " + progress + "/" + allChunks.size());
                    }
                }
                
                // Create output directory
                Files.createDirectories(outputDirectory.toPath());
                
                // Save index
                String fileName = project.getArtifactId() + "-" + project.getVersion() + "-vectors.mvec";
                Path outputPath = outputDirectory.toPath().resolve(fileName);
                index.save(outputPath);
                
                getLog().info("Saved vectors to: " + outputPath);
                
                // Print stats
                IndexStats stats = index.getStats();
                getLog().info("Index statistics:");
                getLog().info("  Total chunks: " + stats.totalChunks());
                getLog().info("  Dimensions: " + stats.dimensions());
                getLog().info("  Size: " + formatSize(stats.sizeBytes()));
                
                // Attach as artifact
                if (attachArtifact) {
                    projectHelper.attachArtifact(project, "mvec", "vectors", outputPath.toFile());
                    getLog().info("Attached vectors as Maven artifact");
                }
            }
            
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate vectors", e);
        }
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
