package mx.gilortiz.transactions.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mx.gilortiz.transactions.transaction.BusinessRuleException;
import mx.gilortiz.transactions.transaction.IdempotencyKeyConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Contrato único de errores en RFC 9457 ({@link ProblemDetail}) con la propiedad {@code code} estable.
 * <ul>
 *   <li>400 formato inválido: {@code VALIDATION_ERROR} o {@code MALFORMED_REQUEST}, con la lista {@code errors}.</li>
 *   <li>409 {@code IDEMPOTENCY_KEY_CONFLICT}; 422 con el código de la regla de negocio violada.</li>
 *   <li>500 {@code INTERNAL_ERROR} sin detalles internos (el detalle va al log).</li>
 *   <li>El resto de errores de Spring MVC (405, 406, 415…) usa como {@code code} el nombre del estado HTTP.</li>
 * </ul>
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE_PROPERTY = "code";
    static final String ERRORS_PROPERTY = "errors";
    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final String VALIDATION_DETAIL = "La solicitud tiene datos con formato inválido";
    private static final String INVALID_VALUE = "tiene un valor inválido";
    private static final Comparator<InvalidField> BY_FIELD_AND_MESSAGE =
            Comparator.comparing(InvalidField::field).thenComparing(InvalidField::message);

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail handleBusinessRule(BusinessRuleException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage(), e.code());
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ProblemDetail handleIdempotencyKeyConflict(IdempotencyKeyConflictException e) {
        return problem(HttpStatus.CONFLICT, e.getMessage(), e.code());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Error inesperado atendiendo {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error interno", INTERNAL_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return handleExceptionInternal(ex, validationProblem(fieldErrors(ex.getBindingResult())), headers, status,
                request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                errors.addAll(fieldErrors(parameterErrors));
            } else {
                String name = parameterName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(new InvalidField(name, error.getDefaultMessage()));
                }
            }
        }
        return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? mismatch.getName()
                : ex.getPropertyName();
        List<InvalidField> errors = List.of(new InvalidField(name, INVALID_VALUE));
        return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
    }

    // El mensaje de Jackson menciona clases internas: al cliente solo se le indica el campo, si se conoce.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<InvalidField> errors = ex.getCause() instanceof JacksonException jackson && !jsonPath(jackson).isEmpty()
                ? List.of(new InvalidField(jsonPath(jackson), INVALID_VALUE))
                : List.of();
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud no es un JSON válido",
                MALFORMED_REQUEST);
        body.setProperty(ERRORS_PROPERTY, errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            addDefaultProperties(problem, statusCode);
        }
        return response;
    }

    private static void addDefaultProperties(ProblemDetail problem, HttpStatusCode statusCode) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey(CODE_PROPERTY)) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            problem.setProperty(CODE_PROPERTY, status != null ? status.name() : String.valueOf(statusCode.value()));
        }
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value() && !problem.getProperties().containsKey(
                ERRORS_PROPERTY)) {
            problem.setProperty(ERRORS_PROPERTY, List.of());
        }
    }

    private static ProblemDetail validationProblem(List<InvalidField> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_DETAIL, VALIDATION_ERROR);
        problem.setProperty(ERRORS_PROPERTY, errors.stream().sorted(BY_FIELD_AND_MESSAGE).toList());
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty(CODE_PROPERTY, code);
        return problem;
    }

    private static List<InvalidField> fieldErrors(Errors errors) {
        List<InvalidField> result = new ArrayList<>();
        for (FieldError error : errors.getFieldErrors()) {
            result.add(new InvalidField(error.getField(), error.getDefaultMessage()));
        }
        errors.getGlobalErrors().forEach(error ->
                result.add(new InvalidField(error.getObjectName(), error.getDefaultMessage())));
        return result;
    }

    // El cliente conoce el header o el query param por su nombre HTTP, no por el del parámetro Java.
    private static String parameterName(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && !param.name().isEmpty()) {
            return param.name();
        }
        return parameter.getParameterName();
    }

    private static String jsonPath(JacksonException exception) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : exception.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
