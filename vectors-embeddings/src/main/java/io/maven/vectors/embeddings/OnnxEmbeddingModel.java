package io.maven.vectors.embeddings;

import ai.onnxruntime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ONNX Runtime based embedding model.
 * 
 * <p>Loads and runs transformer models exported to ONNX format for generating
 * code embeddings locally without external API calls.</p>
 */
public class OnnxEmbeddingModel implements EmbeddingModel {
    
    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingModel.class);
    
    private final String modelId;
    private final EmbeddingConfig config;
    private final int dimensions;
    
    private OrtEnvironment env;
    private OrtSession session;
    private SimpleTokenizer tokenizer;
    
    public OnnxEmbeddingModel(String modelId, EmbeddingConfig config) {
        this.modelId = modelId;
        this.config = config;
        this.dimensions = getDimensionsForModel(modelId);
        
        try {
            initialize();
        } catch (OrtException e) {
            throw new RuntimeException("Failed to initialize ONNX model: " + modelId, e);
        }
    }
    
    private void initialize() throws OrtException {
        log.info("Initializing ONNX embedding model: {}", modelId);
        
        this.env = OrtEnvironment.getEnvironment();
        this.tokenizer = new SimpleTokenizer(config.maxSequenceLength());
        
        // Try to load model from cache or download
        Path modelPath = getModelPath();
        
        if (Files.exists(modelPath)) {
            log.info("Loading model from cache: {}", modelPath);
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            this.session = env.createSession(modelPath.toString(), options);
        } else {
            log.warn("ONNX model not found at: {}", modelPath);
            log.warn("Falling back to SimpleEmbeddingModel behavior (hash-based embeddings)");
            log.warn("To use real embeddings, download the ONNX model manually or use: mvn vectors:download-model");
            this.session = null;
        }
    }
    
    @Override
    public float[] embed(String code) {
        if (session == null) {
            // Fallback to hash-based embedding
            return hashBasedEmbedding(code);
        }
        
        try {
            // Tokenize
            long[] inputIds = tokenizer.tokenize(code);
            long[] attentionMask = new long[inputIds.length];
            Arrays.fill(attentionMask, 1L);
            
            // Create tensors - pass 2D arrays, ONNX infers shape automatically
            long[][] inputIds2D = new long[][]{inputIds};
            long[][] attentionMask2D = new long[][]{attentionMask};
            
            OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, inputIds2D);
            OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask2D);
            
            // Run inference
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);
            
            OrtSession.Result result = session.run(inputs);
            
            // Extract embedding - handle both 2D [batch, hidden] and 3D [batch, seq, hidden] outputs
            Object outputValue = result.get(0).getValue();
            float[] embedding;
            
            if (outputValue instanceof float[][]) {
                // 2D output: [batch, hidden] - direct embedding
                float[][] output2D = (float[][]) outputValue;
                embedding = output2D[0];
            } else if (outputValue instanceof float[][][]) {
                // 3D output: [batch, seq, hidden] - use CLS token (first token)
                float[][][] output3D = (float[][][]) outputValue;
                embedding = output3D[0][0];
            } else {
                throw new OrtException("Unexpected output tensor shape");
            }
            
            // Normalize if configured
            if (config.normalizeOutput()) {
                normalize(embedding);
            }
            
            // Cleanup
            inputIdsTensor.close();
            attentionMaskTensor.close();
            result.close();
            
            return embedding;
            
        } catch (OrtException e) {
            log.error("ONNX inference failed, falling back to hash-based embedding", e);
            return hashBasedEmbedding(code);
        }
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
        try {
            if (session != null) {
                session.close();
            }
        } catch (OrtException e) {
            log.warn("Error closing ONNX session", e);
        }
    }
    
    private Path getModelPath() {
        String safeName = modelId.replace("/", "_").replace("\\", "_");
        return config.cacheDir().resolve(safeName).resolve("model.onnx");
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
    
    private float[] hashBasedEmbedding(String code) {
        // Fallback hash-based embedding when model not available
        Random random = new Random(code.hashCode());
        float[] embedding = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            embedding[i] = (float) random.nextGaussian();
        }
        if (config.normalizeOutput()) {
            normalize(embedding);
        }
        return embedding;
    }
    
    private int getDimensionsForModel(String modelId) {
        if (modelId.contains("unixcoder") || modelId.contains("codebert") || modelId.contains("codet5")) {
            return 768;
        } else if (modelId.contains("MiniLM")) {
            return 384;
        }
        return 768;
    }
    
    /**
     * Simple tokenizer that converts text to token IDs.
     * 
     * <p>This is a simplified tokenizer for demonstration. 
     * In production, use HuggingFace tokenizers or similar.</p>
     */
    private static class SimpleTokenizer {
        private final int maxLength;
        
        SimpleTokenizer(int maxLength) {
            this.maxLength = maxLength;
        }
        
        long[] tokenize(String text) {
            // Simple character-level tokenization
            // In production, load actual tokenizer vocabulary
            String[] words = text.split("\\s+");
            List<Long> tokens = new ArrayList<>();
            
            // Add [CLS] token
            tokens.add(101L);
            
            for (String word : words) {
                if (tokens.size() >= maxLength - 1) break;
                // Simple hash-based token ID
                tokens.add((long) (Math.abs(word.hashCode()) % 30000) + 1000);
            }
            
            // Add [SEP] token
            tokens.add(102L);
            
            // Pad to fixed length
            while (tokens.size() < maxLength) {
                tokens.add(0L);
            }
            
            return tokens.stream().limit(maxLength).mapToLong(Long::longValue).toArray();
        }
    }
}
