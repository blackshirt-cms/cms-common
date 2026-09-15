package in.blackshirt.common.exception;

/**
 * Constants for error response detail keys.
 * Used to provide consistent key names across all error responses.
 */
public final class ErrorDetailKeys {
    
    private ErrorDetailKeys() {}
    
    // User-facing information
    /** User-friendly message suitable for UI display */
    public static final String UI_MESSAGE = "uiMessage";
    
    // Debugging information
    /** Full stack trace of the exception */
    public static final String STACKTRACE = "stacktrace";
    
    /** Location where exception occurred: ClassName::methodName:lineNumber */
    public static final String LOCATION = "location";
    
    // Request information
    /** Request body content (requires ContentCachingRequestWrapper) */
    public static final String REQUEST_BODY = "requestBody";
    
    /** HTTP request headers */
    public static final String REQUEST_HEADERS = "requestHeaders";
    
    /** HTTP method (GET, POST, etc.) */
    public static final String REQUEST_METHOD = "requestMethod";
    
    /** Request URI path */
    public static final String REQUEST_URI = "requestUri";
    
    /** Query parameters from the request */
    public static final String QUERY_PARAMS = "queryParams";
    
    // Additional context
    /** Simple class name of the exception type */
    public static final String EXCEPTION_TYPE = "exceptionType";
    
    /** Timestamp of when the error occurred */
    public static final String TIMESTAMP = "timestamp";
}
