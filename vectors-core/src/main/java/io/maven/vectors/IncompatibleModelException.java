package io.maven.vectors;

/**
 * Exception thrown when trying to merge indexes with incompatible embedding models.
 */
public class IncompatibleModelException extends RuntimeException {
    
    private final String expectedModel;
    private final String actualModel;
    
    public IncompatibleModelException(String expectedModel, String actualModel) {
        super(String.format(
            "Cannot merge indexes with different embedding models. Expected '%s' but found '%s'",
            expectedModel, actualModel
        ));
        this.expectedModel = expectedModel;
        this.actualModel = actualModel;
    }
    
    public String getExpectedModel() {
        return expectedModel;
    }
    
    public String getActualModel() {
        return actualModel;
    }
}
