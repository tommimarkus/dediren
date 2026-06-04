package dev.dediren.archimate;

public final class ArchimateJunctionValidationException extends Exception {
    private final String code;
    private final String path;

    ArchimateJunctionValidationException(String code, String path, String message) {
        super(message);
        this.code = code;
        this.path = path;
    }

    public String code() {
        return code;
    }

    public String path() {
        return path;
    }

    public String message() {
        return getMessage();
    }
}
