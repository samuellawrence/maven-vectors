package io.maven.vectors.plugin;

import io.maven.vectors.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Resolves vector artifacts (.mvec) from transitive dependencies
 * and merges them into a unified searchable index.
 *
 * <p>Usage: {@code mvn vectors:resolve}</p>
 *
 * <p>This goal traverses the project's dependency tree, resolves any published
 * vector artifacts (classifier "vectors", type "mvec"), and merges them into
 * a single unified index that can be searched across all dependencies.</p>
 */
@Mojo(
    name = "resolve",
    defaultPhase = LifecyclePhase.PREPARE_PACKAGE,
    requiresDependencyResolution = ResolutionScope.COMPILE
)
public class ResolveMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * Expected embedding model for compatibility checking.
     */
    @Parameter(property = "vectors.model", defaultValue = "jina-code")
    private String model;

    /**
     * Output format: inmemory or hnsw.
     */
    @Parameter(property = "vectors.format", defaultValue = "inmemory")
    private String outputFormat;

    /**
     * Whether to include the current project's vectors in the merged index.
     */
    @Parameter(property = "vectors.includeSelf", defaultValue = "true")
    private boolean includeSelf;

    /**
     * Output directory for the merged vector index.
     */
    @Parameter(property = "vectors.outputDirectory", defaultValue = "${project.build.directory}/vectors")
    private File outputDirectory;

    /**
     * Comma-separated dependency scopes to include.
     */
    @Parameter(property = "vectors.scopes", defaultValue = "compile,provided")
    private String scopes;

    /**
     * Skip dependency vector resolution.
     */
    @Parameter(property = "vectors.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping vector resolution");
            return;
        }

        getLog().info("Resolving dependency vectors for " + project.getArtifactId());

        Set<String> allowedScopes = new HashSet<>();
        for (String s : scopes.split(",")) {
            allowedScopes.add(s.trim());
        }

        // Determine output format
        IndexMerger.OutputFormat format = "hnsw".equalsIgnoreCase(outputFormat)
            ? IndexMerger.OutputFormat.HNSW
            : IndexMerger.OutputFormat.IN_MEMORY;

        int dimensions = -1;
        String resolvedModelId = model;

        // Step 1: Load self vectors if requested
        VectorIndex selfIndex = null;
        if (includeSelf) {
            Path selfVectorsPath = findSelfVectors();
            if (selfVectorsPath != null) {
                try {
                    selfIndex = VectorIndex.load(selfVectorsPath);
                    dimensions = selfIndex.getDimensions();
                    resolvedModelId = selfIndex.getModelId();
                    getLog().info("Loaded self vectors: " + selfVectorsPath
                        + " (" + selfIndex.size() + " chunks)");
                } catch (IOException e) {
                    getLog().warn("Failed to load self vectors: " + e.getMessage());
                }
            }
        }

        // Step 2: Resolve dependency vectors
        List<ResolvedDependency> resolved = new ArrayList<>();
        Set<org.apache.maven.artifact.Artifact> artifacts = project.getArtifacts();

        getLog().info("Checking " + artifacts.size() + " dependencies for vector artifacts...");

        for (org.apache.maven.artifact.Artifact artifact : artifacts) {
            if (!allowedScopes.contains(artifact.getScope())) {
                continue;
            }

            String coords = artifact.getGroupId() + ":" + artifact.getArtifactId()
                          + ":" + artifact.getVersion();

            try {
                File vectorsFile = resolveVectorsArtifact(artifact);
                if (vectorsFile != null && vectorsFile.exists()) {
                    VectorIndex depIndex = VectorIndex.load(vectorsFile.toPath());
                    resolved.add(new ResolvedDependency(coords, depIndex));

                    if (dimensions == -1) {
                        dimensions = depIndex.getDimensions();
                        resolvedModelId = depIndex.getModelId();
                    }
                    getLog().info("  Resolved: " + coords + " (" + depIndex.size() + " chunks)");
                }
            } catch (ArtifactResolutionException e) {
                getLog().debug("No vectors artifact for: " + coords);
            } catch (IOException e) {
                getLog().warn("Failed to load vectors for " + coords + ": " + e.getMessage());
            }
        }

        if (selfIndex == null && resolved.isEmpty()) {
            getLog().warn("No vector indexes found to merge");
            return;
        }

        // Use defaults if no index loaded yet
        if (dimensions == -1) {
            getLog().warn("Could not determine dimensions from any index");
            return;
        }

        // Step 3: Merge all indexes
        int totalSources = resolved.size() + (selfIndex != null ? 1 : 0);
        getLog().info("Merging " + totalSources + " index(es)...");

        IndexMerger merger = new IndexMerger(resolvedModelId, dimensions, format, 100_000);

        if (selfIndex != null) {
            String selfCoords = project.getGroupId() + ":" + project.getArtifactId()
                              + ":" + project.getVersion();
            merger.addIndex(selfIndex, selfCoords);
        }

        for (ResolvedDependency dep : resolved) {
            merger.addIndex(dep.index, dep.coords);
        }

        // Step 4: Build and save merged index
        try {
            VectorIndex mergedIndex = merger.build();

            Files.createDirectories(outputDirectory.toPath());
            String fileName = project.getArtifactId() + "-" + project.getVersion()
                            + "-vectors-resolved.mvec";
            Path outputPath = outputDirectory.toPath().resolve(fileName);
            mergedIndex.save(outputPath);

            getLog().info("Saved merged index: " + outputPath);
            getLog().info("  Total chunks: " + mergedIndex.size());
            getLog().info("  From " + totalSources + " source(s)");

            if (!merger.getSkippedArtifacts().isEmpty()) {
                getLog().warn("Skipped incompatible artifacts: " + merger.getSkippedArtifacts());
            }

            // Cleanup
            mergedIndex.close();
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to save merged index", e);
        } finally {
            if (selfIndex != null) {
                selfIndex.close();
            }
            for (ResolvedDependency dep : resolved) {
                dep.index.close();
            }
        }
    }

    private File resolveVectorsArtifact(org.apache.maven.artifact.Artifact dep)
            throws ArtifactResolutionException {
        org.eclipse.aether.artifact.Artifact aetherArtifact = new DefaultArtifact(
            dep.getGroupId(), dep.getArtifactId(), "vectors", "mvec", dep.getVersion()
        );

        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(aetherArtifact);
        request.setRepositories(remoteRepos);

        ArtifactResult result = repoSystem.resolveArtifact(repoSession, request);
        return result.getArtifact().getFile();
    }

    private Path findSelfVectors() {
        Path vectorsDir = outputDirectory.toPath();
        if (!Files.exists(vectorsDir)) {
            return null;
        }

        try (var files = Files.list(vectorsDir)) {
            return files
                .filter(p -> p.toString().endsWith(".mvec"))
                .filter(p -> !p.getFileName().toString().contains("-resolved"))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static class ResolvedDependency {
        final String coords;
        final VectorIndex index;

        ResolvedDependency(String coords, VectorIndex index) {
            this.coords = coords;
            this.index = index;
        }
    }
}
