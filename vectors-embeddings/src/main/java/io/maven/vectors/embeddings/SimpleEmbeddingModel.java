package io.maven.vectors.embeddings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Simple hash-based embedding model for testing and development.
 * 
 * <p>This model generates deterministic pseudo-embeddings based on text hashes.
 * It's useful for testing the pipeline without requiring ONNX models.</p>
 * 
 * <p><b>Warning:</b> These embeddings have no semantic meaning. Use only for testing.</p>
 */
public class SimpleEmbeddingModel implements EmbeddingModel {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleEmbeddingModel.class);
    
    private final String modelId;
    private final int dimensions;
    private final boolean normalizeOutput;
    
    public SimpleEmbeddingModel(String modelId, EmbeddingConfig config) {
        this.modelId = modelId;
        this.dimensions = getDimensionsForModel(modelId);
        this.normalizeOutput = config.normalizeOutput();
        
        log.info("Initialized SimpleEmbeddingModel: {} ({}d)", modelId, dimensions);
        log.warn("SimpleEmbeddingModel produces hash-based embeddings with no semantic meaning. Use for testing only.");
    }
    
    @Override
    public float[] embed(String code) {
        // Generate deterministic embedding from text hash
        long seed = hashCode(code);
        Random random = new Random(seed);
        
        float[] embedding = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            embedding[i] = (float) random.nextGaussian();
        }
        
        if (normalizeOutput) {
            normalize(embedding);
        }
        
        return embedding;
    }
    
    @Override
    public List<float[]> embedBatch(List<String> codes) {
        List<float[]> embeddings = new ArrayList<>(codes.size());
        for (String code : codes) {
            embeddings.add(embed(code));
        }
        return embeddings;
    }
    
    @Override
    public String getModelId() {
        return modelId;
    }
    
    @Override
    public int getDimensions() {
        return dimensions;
    }
    
    @Override
    public long getModelHash() {
        return modelId.hashCode();
    }
    
    @Override
    public void close() {
        // Nothing to close
    }
    
    private long hashCode(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            
            // Convert first 8 bytes to long
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (hash[i] & 0xFF);
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            return text.hashCode();
        }
    }
    
    private void normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }
    
    private int getDimensionsForModel(String modelId) {
        // Return standard dimensions for known models
        if (modelId.contains("unixcoder") || modelId.contains("codebert") || modelId.contains("codet5")) {
            return 768;
        } else if (modelId.contains("MiniLM")) {
            return 384;
        }
        return 768; // Default
    }
}
