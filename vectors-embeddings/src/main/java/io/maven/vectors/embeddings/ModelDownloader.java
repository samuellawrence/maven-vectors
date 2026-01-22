package io.maven.vectors.embeddings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

/**
 * Downloads embedding models from HuggingFace.
 */
public class ModelDownloader {
    
    private static final Logger log = LoggerFactory.getLogger(ModelDownloader.class);
    
    private static final String HF_BASE_URL = "https://huggingface.co";
    
    /**
     * Known model configurations.
     */
    private static final Map<String, ModelInfo> KNOWN_MODELS = Map.ofEntries(
        // ==================== General Purpose Models ====================

        // General purpose sentence embedding (smaller, faster)
        Map.entry("all-MiniLM-L6-v2", new ModelInfo(
            "Xenova/all-MiniLM-L6-v2",
            "onnx/model.onnx",
            "tokenizer.json",
            384
        )),

        // BGE Small - excellent quality/speed tradeoff (MTEB top performer)
        Map.entry("bge-small-en", new ModelInfo(
            "Xenova/bge-small-en-v1.5",
            "onnx/model.onnx",
            "tokenizer.json",
            384
        )),

        // BGE Base - higher quality, larger model
        Map.entry("bge-base-en", new ModelInfo(
            "Xenova/bge-base-en-v1.5",
            "onnx/model.onnx",
            "tokenizer.json",
            768
        )),

        // Nomic Embed Text - good general purpose with long context (8K)
        Map.entry("nomic-embed-text", new ModelInfo(
            "Xenova/nomic-embed-text-v1",
            "onnx/model.onnx",
            "tokenizer.json",
            768
        )),

        // ==================== Code-Specific Models ====================

        // Jina Code - best for code search (8K context, 30+ languages)
        Map.entry("jina-code", new ModelInfo(
            "maven-vectors/jina-code-onnx",
            "model.onnx",
            "tokenizer.json",
            768
        )),
        // Alias for Jina Code
        Map.entry("jinaai/jina-embeddings-v2-base-code", new ModelInfo(
            "maven-vectors/jina-code-onnx",
            "model.onnx",
            "tokenizer.json",
            768
        )),

        // UniXcoder - Microsoft's code understanding model
        Map.entry("unixcoder", new ModelInfo(
            "maven-vectors/unixcoder-base-onnx",
            "model.onnx",
            "tokenizer.json",
            768
        )),
        // Alias for UniXcoder
        Map.entry("microsoft/unixcoder-base", new ModelInfo(
            "maven-vectors/unixcoder-base-onnx",
            "model.onnx",
            "tokenizer.json",
            768
        ))
    );
    
    /** Default model for code search */
    public static final String DEFAULT_MODEL = "jina-code";
    
    private final HttpClient httpClient;
    private final Path cacheDir;
    
    public ModelDownloader(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }
    
    /**
     * Downloads a model if not already cached.
     * 
     * @param modelId Model identifier (e.g., "all-MiniLM-L6-v2")
     * @return Path to the model directory
     */
    public Path downloadModel(String modelId) throws IOException, InterruptedException {
        ModelInfo info = KNOWN_MODELS.get(modelId);
        if (info == null) {
            // Try to use modelId directly as HuggingFace repo
            info = new ModelInfo(modelId, "onnx/model.onnx", "tokenizer.json", 768);
        }
        
        String safeName = modelId.replace("/", "_").replace("\\", "_");
        Path modelDir = cacheDir.resolve(safeName);
        
        Path modelPath = modelDir.resolve("model.onnx");
        Path tokenizerPath = modelDir.resolve("tokenizer.json");
        
        // Check if already downloaded
        if (Files.exists(modelPath) && Files.exists(tokenizerPath)) {
            log.info("Model already cached: {}", modelDir);
            return modelDir;
        }
        
        Files.createDirectories(modelDir);
        
        // Download model.onnx
        if (!Files.exists(modelPath)) {
            String modelUrl = String.format("%s/%s/resolve/main/%s", 
                HF_BASE_URL, info.repoId, info.modelFile);
            log.info("Downloading model from: {}", modelUrl);
            downloadFile(modelUrl, modelPath);
        }
        
        // Download tokenizer.json
        if (!Files.exists(tokenizerPath)) {
            String tokenizerUrl = String.format("%s/%s/resolve/main/%s", 
                HF_BASE_URL, info.repoId, info.tokenizerFile);
            log.info("Downloading tokenizer from: {}", tokenizerUrl);
            downloadFile(tokenizerUrl, tokenizerPath);
        }
        
        log.info("Model downloaded to: {}", modelDir);
        return modelDir;
    }
    
    private void downloadFile(String url, Path destination) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build();
        
        HttpResponse<InputStream> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofInputStream());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to download " + url + ": HTTP " + response.statusCode());
        }
        
        try (InputStream in = response.body()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        
        log.info("Downloaded: {} ({} bytes)", destination.getFileName(), Files.size(destination));
    }
    
    /**
     * Returns the dimensions for a known model.
     */
    public static int getDimensions(String modelId) {
        ModelInfo info = KNOWN_MODELS.get(modelId);
        return info != null ? info.dimensions : 768;
    }
    
    /**
     * Checks if a model is cached.
     */
    public boolean isCached(String modelId) {
        String safeName = modelId.replace("/", "_").replace("\\", "_");
        Path modelDir = cacheDir.resolve(safeName);
        return Files.exists(modelDir.resolve("model.onnx")) 
            && Files.exists(modelDir.resolve("tokenizer.json"));
    }
    
    private record ModelInfo(
        String repoId,
        String modelFile,
        String tokenizerFile,
        int dimensions
    ) {}
}
