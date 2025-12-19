package io.maven.vectors;

import java.util.Map;
import java.util.Objects;

/**
 * Represents a chunk of code that can be embedded.
 * 
 * <p>A chunk is typically a class, method, constructor, or other
 * meaningful unit of code that can be semantically searched.</p>
 */
public record CodeChunk(
    /** Unique identifier for this chunk */
    String id,
    
    /** Human-readable name (e.g., "UserService.findById(Long)") */
    String name,
    
    /** Type of code chunk */
    ChunkType type,
    
    /** Source code content */
    String code,
    
    /** Source file path */
    String file,
    
    /** Starting line number (1-indexed) */
    int lineStart,
    
    /** Ending line number (1-indexed) */
    int lineEnd,
    
    /** Parent class name (for methods, constructors, fields) */
    String parentClass,
    
    /** Additional metadata (annotations, modifiers, etc.) */
    Map<String, String> metadata
) {
    public CodeChunk {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(code, "code cannot be null");
        Objects.requireNonNull(file, "file cannot be null");
        if (lineStart < 1) throw new IllegalArgumentException("lineStart must be >= 1");
        if (lineEnd < lineStart) throw new IllegalArgumentException("lineEnd must be >= lineStart");
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }
    
    /**
     * Creates a new CodeChunk with minimal required fields.
     */
    public static CodeChunk of(String name, ChunkType type, String code, String file, int lineStart, int lineEnd) {
        String id = generateId(name, file, lineStart);
        return new CodeChunk(id, name, type, code, file, lineStart, lineEnd, null, Map.of());
    }
    
    /**
     * Creates a new CodeChunk with parent class information.
     */
    public static CodeChunk ofMethod(String name, String code, String file, int lineStart, int lineEnd, String parentClass) {
        String id = generateId(name, file, lineStart);
        return new CodeChunk(id, name, ChunkType.METHOD, code, file, lineStart, lineEnd, parentClass, Map.of());
    }
    
    private static String generateId(String name, String file, int lineStart) {
        return String.format("%s:%s:%d", file, name, lineStart);
    }
    
    /**
     * Returns a truncated version of the code for display purposes.
     */
    public String truncatedCode(int maxLength) {
        if (code.length() <= maxLength) {
            return code;
        }
        return code.substring(0, maxLength - 3) + "...";
    }
    
    /**
     * Returns the fully qualified name including parent class.
     */
    public String qualifiedName() {
        if (parentClass != null && !parentClass.isEmpty()) {
            return parentClass + "." + name;
        }
        return name;
    }
}
