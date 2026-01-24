package io.maven.vectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Utility for merging multiple VectorIndex instances into a single unified index.
 * Handles cross-format merging (InMemory + HNSW), deduplication, and provenance tracking.
 */
public class IndexMerger {

    private static final Logger log = LoggerFactory.getLogger(IndexMerger.class);

    /**
     * Output format for the merged index.
     */
    public enum OutputFormat {
        IN_MEMORY,
        HNSW
    }

    private final String targetModelId;
    private final int dimensions;
    private final OutputFormat outputFormat;
    private final int hnswMaxItems;

    private final Set<String> seenIds = new HashSet<>();
    private final List<VectorEntry> pendingEntries = new ArrayList<>();
    private final List<String> skippedArtifacts = new ArrayList<>();

    /**
     * Creates a new IndexMerger.
     *
     * @param targetModelId The embedding model ID that all merged indexes must match
     * @param dimensions The vector dimensions
     * @param format Output format (IN_MEMORY or HNSW)
     * @param hnswMaxItems Maximum items for HNSW output (ignored for IN_MEMORY)
     */
    public IndexMerger(String targetModelId, int dimensions, OutputFormat format, int hnswMaxItems) {
        this.targetModelId = targetModelId;
        this.dimensions = dimensions;
        this.outputFormat = format;
        this.hnswMaxItems = hnswMaxItems;
    }

    /**
     * Adds all entries from an index, stamping them with artifact provenance.
     * Skips indexes with incompatible models.
     *
     * @param index The index to add entries from
     * @param artifactCoords Maven coordinates (groupId:artifactId:version)
     * @return true if the index was compatible and entries were added, false if skipped
     */
    public boolean addIndex(VectorIndex index, String artifactCoords) {
        if (!Objects.equals(targetModelId, index.getModelId())) {
            log.warn("Skipping incompatible index from {}: model '{}' != target '{}'",
                artifactCoords, index.getModelId(), targetModelId);
            skippedArtifacts.add(artifactCoords);
            return false;
        }

        List<VectorEntry> entries = index.entries();
        int added = 0;
        for (VectorEntry entry : entries) {
            if (!seenIds.contains(entry.chunk().id())) {
                CodeChunk stamped = entry.chunk().withArtifact(artifactCoords);
                pendingEntries.add(new VectorEntry(stamped, entry.embedding()));
                seenIds.add(entry.chunk().id());
                added++;
            }
        }
        log.info("Added {} entries from {} (skipped {} duplicates)",
            added, artifactCoords, entries.size() - added);
        return true;
    }

    /**
     * Builds the final merged index from all added entries.
     *
     * @return The merged VectorIndex
     */
    public VectorIndex build() {
        IndexConfig config = IndexConfig.forModel(targetModelId, dimensions);
        VectorIndex target;

        if (outputFormat == OutputFormat.HNSW) {
            int maxItems = Math.max(pendingEntries.size() * 2, hnswMaxItems);
            target = VectorIndex.createHnsw(config, maxItems);
        } else {
            target = VectorIndex.create(config);
        }

        target.addAll(pendingEntries);
        return target;
    }

    /**
     * Returns artifacts that were skipped due to model incompatibility.
     */
    public List<String> getSkippedArtifacts() {
        return Collections.unmodifiableList(skippedArtifacts);
    }

    /**
     * Returns the number of entries pending to be merged.
     */
    public int getPendingCount() {
        return pendingEntries.size();
    }
}
