package io.maven.vectors;

/**
 * Exception thrown when trying to load an unsupported format version.
 */
public class UnsupportedFormatException extends RuntimeException {
    
    private final int version;
    
    public UnsupportedFormatException(int version) {
        super(String.format("Unsupported vector format version: %d", version));
        this.version = version;
    }
    
    public int getVersion() {
        return version;
    }
}
