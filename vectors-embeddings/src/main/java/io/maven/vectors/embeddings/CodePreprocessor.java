package io.maven.vectors.embeddings;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preprocesses code text to improve embedding quality.
 * 
 * <p>Key transformations:
 * <ul>
 *   <li>Split camelCase identifiers: cosineSimilarity → cosine Similarity</li>
 *   <li>Split snake_case identifiers: parse_file → parse file</li>
 *   <li>Split PascalCase identifiers: VectorIndex → Vector Index</li>
 *   <li>Preserve common programming keywords</li>
 * </ul>
 */
public class CodePreprocessor {
    
    // Pattern to match camelCase boundaries: lowercase followed by uppercase
    private static final Pattern CAMEL_CASE = Pattern.compile("([a-z])([A-Z])");
    
    // Pattern to match PascalCase sequences: uppercase followed by uppercase+lowercase
    private static final Pattern PASCAL_CASE = Pattern.compile("([A-Z]+)([A-Z][a-z])");
    
    // Pattern to match underscores in identifiers
    private static final Pattern SNAKE_CASE = Pattern.compile("_+");
    
    // Pattern to match numeric boundaries: letters next to numbers (using lookahead/lookbehind)
    private static final Pattern NUMERIC_BOUNDARY = Pattern.compile("(?<=[a-zA-Z])(?=\\d)|(?<=\\d)(?=[a-zA-Z])");
    
    // Pattern for Java identifiers
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*");
    
    private final boolean splitCamelCase;
    private final boolean splitSnakeCase;
    private final boolean splitNumeric;
    private final boolean lowercase;
    
    private CodePreprocessor(Builder builder) {
        this.splitCamelCase = builder.splitCamelCase;
        this.splitSnakeCase = builder.splitSnakeCase;
        this.splitNumeric = builder.splitNumeric;
        this.lowercase = builder.lowercase;
    }
    
    /**
     * Creates a preprocessor with default settings (all transformations enabled).
     */
    public static CodePreprocessor defaults() {
        return new Builder().build();
    }
    
    /**
     * Creates a builder for custom configuration.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Preprocesses code text for embedding.
     * 
     * @param text the code text to preprocess
     * @return preprocessed text with split identifiers
     */
    public String preprocess(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        StringBuilder result = new StringBuilder();
        Matcher matcher = IDENTIFIER.matcher(text);
        int lastEnd = 0;
        
        while (matcher.find()) {
            // Append non-identifier text as-is
            result.append(text, lastEnd, matcher.start());
            
            // Process the identifier
            String identifier = matcher.group();
            String processed = processIdentifier(identifier);
            result.append(processed);
            
            lastEnd = matcher.end();
        }
        
        // Append remaining text
        result.append(text.substring(lastEnd));
        
        String processed = result.toString();
        
        // Normalize whitespace
        processed = processed.replaceAll("\\s+", " ");
        
        if (lowercase) {
            processed = processed.toLowerCase();
        }
        
        return processed.trim();
    }
    
    /**
     * Processes a single identifier, splitting as configured.
     */
    private String processIdentifier(String identifier) {
        String result = identifier;
        
        if (splitCamelCase) {
            // Split camelCase: cosineSimilarity → cosine Similarity
            result = CAMEL_CASE.matcher(result).replaceAll("$1 $2");
            
            // Split PascalCase: XMLParser → XML Parser, HTTPRequest → HTTP Request
            result = PASCAL_CASE.matcher(result).replaceAll("$1 $2");
        }
        
        if (splitSnakeCase) {
            // Split snake_case: parse_file → parse file
            result = SNAKE_CASE.matcher(result).replaceAll(" ");
        }
        
        if (splitNumeric) {
            // Split numeric boundaries: log2 → log 2, v2Parser → v 2 Parser
            result = NUMERIC_BOUNDARY.matcher(result).replaceAll(" ");
        }
        
        return result;
    }
    
    /**
     * Extracts just the identifiers from code, split and joined.
     * Useful for creating a "bag of words" from code.
     * 
     * @param code the code to extract identifiers from
     * @return space-separated split identifiers
     */
    public List<String> extractTokens(String code) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(code);
        
        while (matcher.find()) {
            String identifier = matcher.group();
            String processed = processIdentifier(identifier);
            
            // Split on spaces and add each part
            for (String part : processed.split("\\s+")) {
                if (!part.isEmpty() && part.length() > 1) { // Skip single chars
                    tokens.add(lowercase ? part.toLowerCase() : part);
                }
            }
        }
        
        return tokens;
    }
    
    /**
     * Creates an enhanced text representation for embedding.
     * Combines original code with preprocessed version for better semantic matching.
     * 
     * @param code the original code
     * @param javadoc optional javadoc comment (may be null)
     * @return enhanced text for embedding
     */
    public String enhanceForEmbedding(String code, String javadoc) {
        StringBuilder enhanced = new StringBuilder();
        
        // Add javadoc first if present (natural language description)
        if (javadoc != null && !javadoc.isEmpty()) {
            String cleanJavadoc = cleanJavadoc(javadoc);
            if (!cleanJavadoc.isEmpty()) {
                enhanced.append(cleanJavadoc).append(" ");
            }
        }
        
        // Add preprocessed code
        String preprocessed = preprocess(code);
        enhanced.append(preprocessed);
        
        return enhanced.toString().trim();
    }
    
    /**
     * Cleans javadoc by removing tags and formatting.
     */
    private String cleanJavadoc(String javadoc) {
        return javadoc
            // Remove /** and */
            .replaceAll("/\\*\\*|\\*/", "")
            // Remove leading * on each line
            .replaceAll("(?m)^\\s*\\*\\s?", "")
            // Remove @param, @return, @throws tags but keep description
            .replaceAll("@param\\s+\\w+\\s+", "parameter: ")
            .replaceAll("@return\\s+", "returns: ")
            .replaceAll("@throws\\s+\\w+\\s+", "throws: ")
            // Remove other tags
            .replaceAll("@\\w+", "")
            // Remove {@code ...} and {@link ...} but keep content
            .replaceAll("\\{@\\w+\\s+([^}]+)}", "$1")
            // Normalize whitespace
            .replaceAll("\\s+", " ")
            .trim();
    }
    
    public static class Builder {
        private boolean splitCamelCase = true;
        private boolean splitSnakeCase = true;
        private boolean splitNumeric = true;
        private boolean lowercase = false;
        
        public Builder splitCamelCase(boolean enabled) {
            this.splitCamelCase = enabled;
            return this;
        }
        
        public Builder splitSnakeCase(boolean enabled) {
            this.splitSnakeCase = enabled;
            return this;
        }
        
        public Builder splitNumeric(boolean enabled) {
            this.splitNumeric = enabled;
            return this;
        }
        
        public Builder lowercase(boolean enabled) {
            this.lowercase = enabled;
            return this;
        }
        
        public CodePreprocessor build() {
            return new CodePreprocessor(this);
        }
    }
}
