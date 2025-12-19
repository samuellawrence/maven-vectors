package io.maven.vectors.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import io.maven.vectors.ChunkType;
import io.maven.vectors.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Parses Java source files and extracts code chunks for embedding.
 * 
 * <p>Uses JavaParser for accurate AST-based extraction of classes, methods,
 * constructors, and other code elements.</p>
 */
public class JavaCodeChunker {
    
    private static final Logger log = LoggerFactory.getLogger(JavaCodeChunker.class);
    
    private final JavaParser parser;
    private final ChunkerConfig config;
    
    public JavaCodeChunker() {
        this(ChunkerConfig.defaults());
    }
    
    public JavaCodeChunker(ChunkerConfig config) {
        this.config = config;
        this.parser = new JavaParser();
    }
    
    /**
     * Parses a single Java file and extracts code chunks.
     */
    public List<CodeChunk> parseFile(Path path) throws IOException {
        String content = Files.readString(path);
        return parseSource(content, path.toString());
    }
    
    /**
     * Parses Java source code and extracts code chunks.
     */
    public List<CodeChunk> parseSource(String source, String fileName) {
        List<CodeChunk> chunks = new ArrayList<>();
        
        ParseResult<CompilationUnit> parseResult = parser.parse(source);
        
        if (!parseResult.isSuccessful()) {
            log.warn("Failed to parse {}: {}", fileName, parseResult.getProblems());
            return chunks;
        }
        
        CompilationUnit cu = parseResult.getResult().orElse(null);
        if (cu == null) return chunks;
        
        String[] lines = source.split("\n");
        
        // Visit and extract chunks
        cu.accept(new ChunkExtractor(chunks, fileName, lines, config), null);
        
        log.debug("Extracted {} chunks from {}", chunks.size(), fileName);
        return chunks;
    }
    
    /**
     * Parses all Java files in a directory (recursively).
     */
    public List<CodeChunk> parseDirectory(Path directory) throws IOException {
        List<CodeChunk> allChunks = new ArrayList<>();
        
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> !isExcluded(p))
                 .forEach(path -> {
                     try {
                         allChunks.addAll(parseFile(path));
                     } catch (IOException e) {
                         log.warn("Failed to parse {}: {}", path, e.getMessage());
                     }
                 });
        }
        
        return allChunks;
    }
    
    private boolean isExcluded(Path path) {
        String pathStr = path.toString().toLowerCase();
        return config.excludePatterns().stream()
            .anyMatch(pattern -> pathStr.contains(pattern.toLowerCase()));
    }
    
    /**
     * AST visitor that extracts code chunks.
     */
    private static class ChunkExtractor extends VoidVisitorAdapter<Void> {
        
        private final List<CodeChunk> chunks;
        private final String fileName;
        private final String[] lines;
        private final ChunkerConfig config;
        private String currentClass = null;
        
        ChunkExtractor(List<CodeChunk> chunks, String fileName, String[] lines, ChunkerConfig config) {
            this.chunks = chunks;
            this.fileName = fileName;
            this.lines = lines;
            this.config = config;
        }
        
        @Override
        public void visit(ClassOrInterfaceDeclaration node, Void arg) {
            String previousClass = currentClass;
            currentClass = node.getNameAsString();
            
            if (config.includeClasses() && node.isTopLevelType()) {
                addChunk(node, node.isInterface() ? ChunkType.INTERFACE : ChunkType.CLASS);
            }
            
            super.visit(node, arg);
            currentClass = previousClass;
        }
        
        @Override
        public void visit(EnumDeclaration node, Void arg) {
            String previousClass = currentClass;
            currentClass = node.getNameAsString();
            
            if (config.includeClasses()) {
                addChunk(node, ChunkType.ENUM);
            }
            
            super.visit(node, arg);
            currentClass = previousClass;
        }
        
        @Override
        public void visit(RecordDeclaration node, Void arg) {
            String previousClass = currentClass;
            currentClass = node.getNameAsString();
            
            if (config.includeClasses()) {
                addChunk(node, ChunkType.RECORD);
            }
            
            super.visit(node, arg);
            currentClass = previousClass;
        }
        
        @Override
        public void visit(MethodDeclaration node, Void arg) {
            if (config.includeMethods()) {
                String signature = buildMethodSignature(node);
                int lineStart = node.getBegin().map(p -> p.line).orElse(1);
                int lineEnd = node.getEnd().map(p -> p.line).orElse(lineStart);
                
                // Include annotations
                if (!node.getAnnotations().isEmpty()) {
                    int annotationStart = node.getAnnotations().get(0)
                        .getBegin().map(p -> p.line).orElse(lineStart);
                    lineStart = Math.min(lineStart, annotationStart);
                }
                
                String code = extractLines(lineStart, lineEnd);
                
                if (code.length() >= config.minChunkSize()) {
                    CodeChunk chunk = CodeChunk.ofMethod(
                        signature, 
                        truncateCode(code), 
                        fileName, 
                        lineStart, 
                        lineEnd, 
                        currentClass
                    );
                    chunks.add(chunk);
                }
            }
            
            super.visit(node, arg);
        }
        
        @Override
        public void visit(ConstructorDeclaration node, Void arg) {
            if (config.includeConstructors()) {
                String signature = buildConstructorSignature(node);
                addChunk(node, ChunkType.CONSTRUCTOR, signature);
            }
            
            super.visit(node, arg);
        }
        
        @Override
        public void visit(FieldDeclaration node, Void arg) {
            if (config.includeFields()) {
                for (VariableDeclarator var : node.getVariables()) {
                    String name = var.getNameAsString();
                    int lineStart = node.getBegin().map(p -> p.line).orElse(1);
                    int lineEnd = node.getEnd().map(p -> p.line).orElse(lineStart);
                    String code = extractLines(lineStart, lineEnd);
                    
                    CodeChunk chunk = new CodeChunk(
                        fileName + ":" + name + ":" + lineStart,
                        name,
                        ChunkType.FIELD,
                        code,
                        fileName,
                        lineStart,
                        lineEnd,
                        currentClass,
                        Map.of()
                    );
                    chunks.add(chunk);
                }
            }
            
            super.visit(node, arg);
        }
        
        private void addChunk(BodyDeclaration<?> node, ChunkType type) {
            String name = getName(node);
            addChunk(node, type, name);
        }
        
        private void addChunk(BodyDeclaration<?> node, ChunkType type, String name) {
            int lineStart = node.getBegin().map(p -> p.line).orElse(1);
            int lineEnd = node.getEnd().map(p -> p.line).orElse(lineStart);
            String code = extractLines(lineStart, lineEnd);
            
            if (code.length() < config.minChunkSize()) {
                return;
            }
            
            CodeChunk chunk = new CodeChunk(
                fileName + ":" + name + ":" + lineStart,
                name,
                type,
                truncateCode(code),
                fileName,
                lineStart,
                lineEnd,
                type == ChunkType.METHOD || type == ChunkType.CONSTRUCTOR ? currentClass : null,
                Map.of()
            );
            chunks.add(chunk);
        }
        
        private String getName(BodyDeclaration<?> node) {
            if (node instanceof ClassOrInterfaceDeclaration c) return c.getNameAsString();
            if (node instanceof EnumDeclaration e) return e.getNameAsString();
            if (node instanceof RecordDeclaration r) return r.getNameAsString();
            if (node instanceof MethodDeclaration m) return m.getNameAsString();
            if (node instanceof ConstructorDeclaration c) return c.getNameAsString();
            return "unknown";
        }
        
        private String buildMethodSignature(MethodDeclaration method) {
            StringBuilder sb = new StringBuilder();
            sb.append(method.getNameAsString()).append("(");
            
            method.getParameters().forEach(p -> {
                if (sb.charAt(sb.length() - 1) != '(') {
                    sb.append(", ");
                }
                sb.append(p.getType().asString());
            });
            
            sb.append(")");
            return sb.toString();
        }
        
        private String buildConstructorSignature(ConstructorDeclaration constructor) {
            StringBuilder sb = new StringBuilder();
            sb.append(constructor.getNameAsString()).append("(");
            
            constructor.getParameters().forEach(p -> {
                if (sb.charAt(sb.length() - 1) != '(') {
                    sb.append(", ");
                }
                sb.append(p.getType().asString());
            });
            
            sb.append(")");
            return sb.toString();
        }
        
        private String extractLines(int start, int end) {
            StringBuilder sb = new StringBuilder();
            for (int i = start - 1; i < Math.min(end, lines.length); i++) {
                if (i >= 0 && i < lines.length) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(lines[i]);
                }
            }
            return sb.toString();
        }
        
        private String truncateCode(String code) {
            if (code.length() <= config.maxChunkSize()) {
                return code;
            }
            return code.substring(0, config.maxChunkSize() - 3) + "...";
        }
    }
    
    /**
     * Configuration for the code chunker.
     */
    public record ChunkerConfig(
        boolean includeClasses,
        boolean includeMethods,
        boolean includeConstructors,
        boolean includeFields,
        int minChunkSize,
        int maxChunkSize,
        List<String> excludePatterns
    ) {
        public static ChunkerConfig defaults() {
            return new ChunkerConfig(
                true,   // includeClasses
                true,   // includeMethods
                true,   // includeConstructors
                false,  // includeFields
                50,     // minChunkSize
                3000,   // maxChunkSize
                List.of("test", "generated", "target")
            );
        }
    }
}
