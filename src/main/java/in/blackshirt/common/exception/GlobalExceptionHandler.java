package in.blackshirt.common.exception;

import in.blackshirt.common.config.BlackshirtProperties;
import in.blackshirt.common.logging.TraceContext;
import in.blackshirt.common.response.ApiErrorResponse;
import in.blackshirt.common.response.StandardErrorCode;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Global fallback exception handler provided by the common library.
 *
 * <p>
 * Runs at {@link Ordered#LOWEST_PRECEDENCE} so that client services can define
 * their own {@link RestControllerAdvice} handlers at a higher priority
 * (e.g., {@code @Order(Ordered.HIGHEST_PRECEDENCE)}) to intercept
 * domain-specific
 * exceptions <em>before</em> this handler.
 *
 * <h2>Extension Pattern</h2>
 * 
 * <pre>{@code
 * @RestControllerAdvice
 * @Order(Ordered.HIGHEST_PRECEDENCE)
 * public class OrderExceptionHandler {
 *
 *     @ExceptionHandler(PaymentFailedException.class)
 *     public ResponseEntity<ApiErrorResponse<?>> handle(PaymentFailedException ex) {
 *         // custom handling — this runs BEFORE GlobalExceptionHandler
 *     }
 * }
 * }</pre>
 *
 * <p>
 * Any exception not caught by a higher-priority handler falls through to
 * {@link #handleBaseException} or {@link #handleUnexpected} here.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Tracer tracer;
    private final HttpServletRequest request;
    private final BlackshirtProperties properties;

    public GlobalExceptionHandler(Tracer tracer, HttpServletRequest request, BlackshirtProperties properties) {
        this.tracer = tracer;
        this.request = request;
        this.properties = properties;
    }

    private static String extractStackTrace(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String extractLocation(Throwable ex) {
        StackTraceElement[] stackTrace = ex.getStackTrace();
        if (stackTrace.length > 0) {
            StackTraceElement element = stackTrace[0];
            return String.format("%s::%s:%d",
                element.getClassName(),
                element.getMethodName(),
                element.getLineNumber());
        }
        return "unknown";
    }

    private static Map<String, String> extractHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name ->
            headers.put(name, request.getHeader(name))
        );
        return headers;
    }

    private static Map<String, String> extractQueryParams(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((key, values) -> {
            if (values.length > 0) {
                params.put(key, String.join(",", values));
            }
        });
        return params;
    }

    @ExceptionHandler(BaseException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleBaseException(BaseException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        details.putAll(ex.getDetails());
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                ex.getErrorCode(),
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.error("BaseException handled: code={}, traceId={}, path={}",
                error.errorCode(), error.traceId(), error.path().orElse("?"));
        return ResponseEntity.status(ex.getErrorCode().getHttpStatus()).body(error);
    }

    @ExceptionHandler(Exception.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleUnexpected(Exception ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.INTERNAL_SERVER_ERROR,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.error("Unexpected error", ex);
        return ResponseEntity.status(500).body(error);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.METHOD_NOT_ALLOWED,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("HttpRequestMethodNotSupportedException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(405).body(error);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.UNSUPPORTED_MEDIA_TYPE,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("HttpMediaTypeNotSupportedException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(415).body(error);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.NOT_ACCEPTABLE,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("HttpMediaTypeNotAcceptableException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(406).body(error);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleMissingParameter(MissingServletRequestParameterException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.BAD_REQUEST,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("MissingServletRequestParameterException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleMissingPart(MissingServletRequestPartException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.BAD_REQUEST,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("MissingServletRequestPartException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleRequestBinding(ServletRequestBindingException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.BAD_REQUEST,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("ServletRequestBindingException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(ConversionNotSupportedException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleConversionNotSupported(ConversionNotSupportedException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.BAD_REQUEST,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("ConversionNotSupportedException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.BAD_REQUEST,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("MethodArgumentTypeMismatchException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.BAD_REQUEST,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("HttpMessageNotReadableException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(HttpMessageNotWritableException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleMessageNotWritable(HttpMessageNotWritableException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.INTERNAL_SERVER_ERROR,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.error("HttpMessageNotWritableException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(500).body(error);
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleMissingPathVariable(MissingPathVariableException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.BAD_REQUEST,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("MissingPathVariableException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(400).body(error);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleNoHandlerFound(NoHandlerFoundException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.RESOURCE_NOT_FOUND,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("NoHandlerFoundException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(404).body(error);
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public <T> ResponseEntity<ApiErrorResponse<T>> handleAsyncRequestTimeout(AsyncRequestTimeoutException ex) {
        var traceIds = TraceContext.current(tracer);
        Map<String, Object> details = buildErrorDetails(ex);
        ApiErrorResponse<T> error = ApiErrorResponse.error(
                properties.getLog().getServiceName(),
                StandardErrorCode.REQUEST_TIMEOUT,
                ex.getMessage(),
                details,
                traceIds.traceId(),
                traceIds.spanId(),
                request.getRequestURI()
        );
        log.warn("AsyncRequestTimeoutException handled: traceId={}, path={}", traceIds.traceId(), request.getRequestURI());
        return ResponseEntity.status(408).body(error);
    }

    private Map<String, Object> buildErrorDetails(Exception ex) {
        Map<String, Object> details = new HashMap<>();
        details.put(ErrorDetailKeys.UI_MESSAGE, ex.getMessage());
        details.put(ErrorDetailKeys.STACKTRACE, extractStackTrace(ex));
        details.put(ErrorDetailKeys.LOCATION, extractLocation(ex));
        details.put(ErrorDetailKeys.EXCEPTION_TYPE, ex.getClass().getSimpleName());
        details.put(ErrorDetailKeys.REQUEST_HEADERS, extractHeaders(request));
        details.put(ErrorDetailKeys.REQUEST_METHOD, request.getMethod());
        details.put(ErrorDetailKeys.REQUEST_URI, request.getRequestURI());
        details.put(ErrorDetailKeys.QUERY_PARAMS, extractQueryParams(request));
        details.put(ErrorDetailKeys.TIMESTAMP, Instant.now().toString());
        return details;
    }
}
