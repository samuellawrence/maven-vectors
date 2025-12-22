package io.maven.vectors.embeddings;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodePreprocessorTest {
    
    @Test
    void testCamelCaseSplitting() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        assertEquals("cosine Similarity", preprocessor.preprocess("cosineSimilarity"));
        assertEquals("parse JSON Data", preprocessor.preprocess("parseJSONData"));
        assertEquals("get HTTP Response", preprocessor.preprocess("getHTTPResponse"));
    }
    
    @Test
    void testSnakeCaseSplitting() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        assertEquals("parse file", preprocessor.preprocess("parse_file"));
        assertEquals("get user id", preprocessor.preprocess("get_user_id"));
    }
    
    @Test
    void testPascalCaseSplitting() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        assertEquals("Vector Index", preprocessor.preprocess("VectorIndex"));
        assertEquals("XML Parser", preprocessor.preprocess("XMLParser"));
        assertEquals("HTTP Request Handler", preprocessor.preprocess("HTTPRequestHandler"));
    }
    
    @Test
    void testNumericSplitting() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        assertEquals("log 2", preprocessor.preprocess("log2"));
        assertEquals("v 2 Parser", preprocessor.preprocess("v2Parser"));
        assertEquals("base 64 Encode", preprocessor.preprocess("base64Encode"));
    }
    
    @Test
    void testCodeSnippetPreprocessing() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        String code = "public float cosineSimilarity(float[] a, float[] b)";
        String expected = "public float cosine Similarity(float[] a, float[] b)";
        
        assertEquals(expected, preprocessor.preprocess(code));
    }
    
    @Test
    void testJavaMethodPreprocessing() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        String code = """
            public void saveToFile(Path outputPath) {
                Files.write(outputPath, data);
            }
            """;
        
        String result = preprocessor.preprocess(code);
        
        // Should split identifiers
        assertTrue(result.contains("save To File"));
        assertTrue(result.contains("output Path"));
    }
    
    @Test
    void testExtractTokens() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        String code = "public void calculateCosineSimilarity()";
        List<String> tokens = preprocessor.extractTokens(code);
        
        assertTrue(tokens.contains("public"));
        assertTrue(tokens.contains("void"));
        assertTrue(tokens.contains("calculate"));
        assertTrue(tokens.contains("Cosine"));
        assertTrue(tokens.contains("Similarity"));
    }
    
    @Test
    void testJavadocCleaning() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        String code = "public void save()";
        String javadoc = """
            /**
             * Saves the data to disk.
             * @param path the file path
             * @return true if successful
             */
            """;
        
        String enhanced = preprocessor.enhanceForEmbedding(code, javadoc);
        
        assertTrue(enhanced.contains("Saves the data to disk"));
        assertTrue(enhanced.contains("parameter:"));
        assertTrue(enhanced.contains("returns:"));
        assertTrue(enhanced.contains("public void save()"));
    }
    
    @Test
    void testDisabledPreprocessing() {
        CodePreprocessor preprocessor = CodePreprocessor.builder()
            .splitCamelCase(false)
            .splitSnakeCase(false)
            .splitNumeric(false)
            .build();
        
        assertEquals("cosineSimilarity", preprocessor.preprocess("cosineSimilarity"));
        assertEquals("parse_file", preprocessor.preprocess("parse_file"));
    }
    
    @Test
    void testLowercaseOption() {
        CodePreprocessor preprocessor = CodePreprocessor.builder()
            .lowercase(true)
            .build();
        
        String result = preprocessor.preprocess("CosineSimilarity");
        assertEquals("cosine similarity", result);
    }
    
    @Test
    void testPreserveNonIdentifiers() {
        CodePreprocessor preprocessor = CodePreprocessor.defaults();
        
        String code = "if (x > 0) { return true; }";
        String result = preprocessor.preprocess(code);
        
        // Operators and punctuation should be preserved
        assertTrue(result.contains(">"));
        assertTrue(result.contains("{"));
        assertTrue(result.contains("}"));
        assertTrue(result.contains("("));
        assertTrue(result.contains(")"));
    }
}
