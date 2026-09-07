package io.github.lambdatest.jsonpath;

/** Raised when a query is not well-formed or not valid per RFC 9535. */
public class JsonPathException extends RuntimeException {
    private final int position;

    public JsonPathException(String message, int position) {
        super(message + " (at position " + position + ")");
        this.position = position;
    }

    public int position() {
        return position;
    }
}
