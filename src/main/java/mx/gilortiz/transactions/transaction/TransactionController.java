package mx.gilortiz.transactions.transaction;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import mx.gilortiz.transactions.common.web.PageResponse;
import mx.gilortiz.transactions.transaction.dto.CreateTransactionRequest;
import mx.gilortiz.transactions.transaction.dto.TransactionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de transacciones. Sin {@code @Transactional}: el servicio que invoca llama al proveedor externo.
 */
@RestController
@RequestMapping("/transactions")
class TransactionController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;
    static final int MAX_LIMIT = 100;
    // Evita que page × limit desborde el offset entero de la consulta.
    static final int MAX_PAGE = Integer.MAX_VALUE / MAX_LIMIT;

    private final TransactionService service;

    TransactionController(TransactionService service) {
        this.service = service;
    }

    /**
     * 201 si esta solicitud creó la transacción (aunque el proveedor la rechace: el estado lo comunica);
     * 200 si es la repetición de una {@code Idempotency-Key} ya registrada.
     */
    @PostMapping
    @ApiResponse(responseCode = "201",
            description = "Transacción creada; el status indica el resultado del proveedor (también si la rechazó)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TransactionResponse.class)))
    @ApiResponse(responseCode = "200",
            description = "Repetición de una Idempotency-Key ya registrada: la transacción existente, que puede "
                    + "seguir PENDING si la original aún espera al proveedor",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TransactionResponse.class)))
    @ApiResponse(responseCode = "400", description = "Formato inválido (VALIDATION_ERROR o MALFORMED_REQUEST)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "Idempotency-Key ya usada con otro payload (IDEMPOTENCY_KEY_CONFLICT)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "Regla de negocio violada (AMOUNT_TOO_LOW, DEBIT_AMOUNT_EXCEEDS_LIMIT, UNSUPPORTED_CURRENCY)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Error inesperado (INTERNAL_ERROR), sin detalles internos",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<TransactionResponse> create(
            @Valid @RequestBody CreateTransactionRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
            @Size(max = IDEMPOTENCY_KEY_MAX_LENGTH, message = "admite como máximo {max} caracteres")
            String idempotencyKey) {
        TransactionExecution execution = service.execute(request.toCommand(idempotencyKey));
        HttpStatus status = execution.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(TransactionResponse.from(execution.transaction()));
    }

    /**
     * RN07: filtros opcionales y paginación; {@code page} es base 0.
     */
    // produces fija application/json en el contrato del 200 (sin él springdoc publica */*); los ProblemDetail de
    // error no se ven afectados porque Spring ignora los tipos producibles del handler al resolver excepciones.
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200", description = "Página de transacciones, de la más reciente a la más antigua",
            useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400", description = "Filtro o paginación inválidos (VALIDATION_ERROR)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "Error inesperado (INTERNAL_ERROR), sin detalles internos",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    PageResponse<TransactionResponse> search(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "debe ser mayor o igual a {value}")
            @Max(value = MAX_PAGE, message = "debe ser menor o igual a {value}")
            int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "debe ser mayor o igual a {value}")
            @Max(value = MAX_LIMIT, message = "debe ser menor o igual a {value}")
            int limit) {
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(accountId, status, type);
        return PageResponse.from(service.search(criteria, page, limit), TransactionResponse::from);
    }
}
