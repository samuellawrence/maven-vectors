package io.maven.vectors.embeddings;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * ONNX Runtime based embedding model with HuggingFace tokenization.
 * 
 * <p>Loads and runs transformer models exported to ONNX format for generating
 * semantic code embeddings locally without external API calls.</p>
 */
public class OnnxEmbeddingModel implements EmbeddingModel {
    
    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingModel.class);
    private static final int MAX_SEQUENCE_LENGTH = 256;
    
    private final String modelId;
    private final EmbeddingConfig config;
    private final int dimensions;
    
    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private boolean initialized = false;
    
    public OnnxEmbeddingModel(String modelId, EmbeddingConfig config) {
        this.modelId = modelId;
        this.config = config;
        this.dimensions = ModelDownloader.getDimensions(modelId);
        
        try {
            initialize();
        } catch (Exception e) {
            log.warn("Failed to initialize ONNX model: {}. Will use fallback.", e.getMessage());
        }
    }
    
    private void initialize() throws OrtException, IOException, InterruptedException {
        log.info("Initializing ONNX embedding model: {}", modelId);
        
        this.env = OrtEnvironment.getEnvironment();
        
        // Check if model is cached, if not download it
        ModelDownloader downloader = new ModelDownloader(config.cacheDir());
        Path modelDir;
        
        if (downloader.isCached(modelId)) {
            String safeName = modelId.replace("/", "_").replace("\\", "_");
            modelDir = config.cacheDir().resolve(safeName);
            log.info("Using cached model: {}", modelDir);
        } else {
            log.info("Model not found in cache. Downloading...");
            try {
                modelDir = downloader.downloadModel(modelId);
            } catch (Exception e) {
                log.warn("Failed to download model: {}. Using fallback embeddings.", e.getMessage());
                return;
            }
        }
        
        Path modelPath = modelDir.resolve("model.onnx");
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        
        if (!Files.exists(modelPath) || !Files.exists(tokenizerPath)) {
            log.warn("Model files not found at: {}", modelDir);
            return;
        }
        
        // Load ONNX model
        log.info("Loading ONNX model from: {}", modelPath);
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        this.session = env.createSession(modelPath.toString(), options);
        
        // Load tokenizer
        log.info("Loading tokenizer from: {}", tokenizerPath);
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
        
        this.initialized = true;
        log.info("ONNX model initialized successfully. Dimensions: {}", dimensions);
    }
    
    @Override
    public float[] embed(String code) {
        if (!initialized) {
            return hashBasedEmbedding(code);
        }
        
        try {
            // Tokenize
            Encoding encoding = tokenizer.encode(code);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            
            // Pad/truncate to fixed length
            inputIds = padOrTruncate(inputIds, MAX_SEQUENCE_LENGTH);
            attentionMask = padOrTruncate(attentionMask, MAX_SEQUENCE_LENGTH);
            
            // Create tensors with shape [1, seq_length]
            long[][] inputIds2D = new long[][]{inputIds};
            long[][] attentionMask2D = new long[][]{attentionMask};
            
            OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, inputIds2D);
            OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask2D);
            
            // Prepare inputs
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);
            
            // Some models also need token_type_ids
            long[][] tokenTypeIds2D = new long[1][MAX_SEQUENCE_LENGTH];
            OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds2D);
            inputs.put("token_type_ids", tokenTypeIdsTensor);
            
            try (OrtSession.Result result = session.run(inputs)) {
                // Get output - shape is [1, seq_length, hidden_size]
                Object outputValue = result.get(0).getValue();
                float[] embedding;
                
                if (outputValue instanceof float[][][]) {
                    // 3D output: apply mean pooling
                    float[][][] output3D = (float[][][]) outputValue;
                    embedding = meanPooling(output3D[0], attentionMask);
                } else if (outputValue instanceof float[][]) {
                    // 2D output: already pooled
                    float[][] output2D = (float[][]) outputValue;
                    embedding = output2D[0];
                } else {
                    log.warn("Unexpected output type: {}", outputValue.getClass());
                    return hashBasedEmbedding(code);
                }
                
                // L2 normalize
                if (config.normalizeOutput()) {
                    normalize(embedding);
                }
                
                return embedding;
                
            } finally {
                inputIdsTensor.close();
                attentionMaskTensor.close();
                tokenTypeIdsTensor.close();
            }
            
        } catch (OrtException e) {
            log.error("ONNX inference failed: {}", e.getMessage());
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
            if (tokenizer != null) {
                tokenizer.close();
            }
        } catch (Exception e) {
            log.warn("Error closing ONNX resources", e);
        }
    }
    
    /**
     * Mean pooling over token embeddings, weighted by attention mask.
     */
    private float[] meanPooling(float[][] tokenEmbeddings, long[] attentionMask) {
        int seqLength = tokenEmbeddings.length;
        int hiddenSize = tokenEmbeddings[0].length;
        float[] pooled = new float[hiddenSize];
        float maskSum = 0;
        
        for (int i = 0; i < seqLength; i++) {
            if (attentionMask[i] == 1) {
                maskSum++;
                for (int j = 0; j < hiddenSize; j++) {
                    pooled[j] += tokenEmbeddings[i][j];
                }
            }
        }
        
        if (maskSum > 0) {
            for (int j = 0; j < hiddenSize; j++) {
                pooled[j] /= maskSum;
            }
        }
        
        return pooled;
    }
    
    /**
     * L2 normalization.
     */
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
    
    /**
     * Pad or truncate array to target length.
     */
    private long[] padOrTruncate(long[] array, int targetLength) {
        if (array.length == targetLength) {
            return array;
        }
        
        long[] result = new long[targetLength];
        int copyLength = Math.min(array.length, targetLength);
        System.arraycopy(array, 0, result, 0, copyLength);
        // Remaining elements are already 0 (padding)
        return result;
    }
    
    /**
     * Fallback hash-based embedding when model not available.
     */
    private float[] hashBasedEmbedding(String code) {
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
}
