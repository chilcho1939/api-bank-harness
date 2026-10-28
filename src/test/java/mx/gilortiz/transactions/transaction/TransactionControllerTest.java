package mx.gilortiz.transactions.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contrato HTTP de {@code /transactions} con el servicio y las reglas reales; solo el puerto del proveedor y el
 * repositorio son mocks.
 */
@WebMvcTest(TransactionController.class)
@Import({TransactionService.class, TransactionRules.class})
class TransactionControllerTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final UUID ID = UUID.fromString("01928c6e-7f00-7000-8000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2025-03-15T10:30:00Z");
    private static final List<String> RESPONSE_FIELDS = List.of("id", "accountId", "type", "amount", "currency",
            "description", "status", "providerTransactionId", "balanceAfter", "createdAt");

    private final MockMvc mvc;

    @MockitoBean
    private TransactionProvider provider;

    @MockitoBean
    private TransactionRepository repository;

    @Autowired
    TransactionControllerTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @BeforeEach
    void persistWithGeneratedIdAndApprove() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> withIdAndCreatedAt(invocation.getArgument(0)));
        when(repository.completePending(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(provider.execute(any()))
                .thenReturn(new ProviderResult.Approved("txn-789", new BigDecimal("5500.00"), CREATED_AT));
    }

    // ---------- POST 201 / 200 ----------

    @Test
    @DisplayName("POST aprobada responde 201 con exactamente los campos del response de la spec")
    void createRespondsCreatedWithSpecFields() throws Exception {
        String body = mvc.perform(postTransaction(json("CREDIT", "1500.00", "MXN")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.accountId").value("acc-123456"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(1500.00))
                .andExpect(jsonPath("$.currency").value("MXN"))
                .andExpect(jsonPath("$.description").value("Transferencia recibida"))
                .andExpect(jsonPath("$.status").value("EXECUTED"))
                .andExpect(jsonPath("$.providerTransactionId").value("txn-789"))
                .andExpect(jsonPath("$.balanceAfter").value(5500.00))
                .andExpect(jsonPath("$.createdAt").value("2025-03-15T10:30:00Z"))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = JsonMapper.builder().build().readTree(body);
        assertThat(response.propertyNames()).containsExactlyInAnyOrderElementsOf(RESPONSE_FIELDS);
        assertThat(response.get("amount").decimalValue()).isEqualByComparingTo("1500.00");
    }

    @Test
    @DisplayName("POST rechazada por el proveedor responde 201: la transacción se creó con estado REJECTED")
    void providerRejectionStillRespondsCreated() throws Exception {
        when(provider.execute(any())).thenReturn(new ProviderResult.Rejected("INSUFFICIENT_FUNDS",
                "The account does not have enough balance to complete the transaction"));

        mvc.perform(postTransaction(json("DEBIT", "1500.00", "MXN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.providerTransactionId").isEmpty())
                .andExpect(jsonPath("$.balanceAfter").isEmpty());
    }

    @Test
    @DisplayName("la Idempotency-Key del header llega al servicio y se guarda con la transacción")
    void idempotencyKeyHeaderReachesService() throws Exception {
        mvc.perform(postTransaction(json("CREDIT", "1500.00", "MXN")).header("Idempotency-Key", "key-1"))
                .andExpect(status().isCreated());

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    @DisplayName("una Idempotency-Key de 100 caracteres se acepta")
    void idempotencyKeyOfMaxLengthIsAccepted() throws Exception {
        mvc.perform(postTransaction(json("CREDIT", "1500.00", "MXN")).header("Idempotency-Key", "k".repeat(100)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("replay de una Idempotency-Key ya registrada responde 200 con la transacción existente")
    void replayRespondsOk() throws Exception {
        Transaction existing = withIdAndCreatedAt(Transaction.pending(command("key-1")));
        existing.markExecuted("txn-789", new BigDecimal("5500.00"));
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        mvc.perform(postTransaction(json("CREDIT", "1500.00", "MXN")).header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.status").value("EXECUTED"));
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("replay mientras la original sigue en vuelo responde 200 con la transacción PENDING")
    void replayOfInFlightTransactionRespondsOkWithPending() throws Exception {
        Transaction inFlight = withIdAndCreatedAt(Transaction.pending(command("key-1")));
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(inFlight));

        mvc.perform(postTransaction(json("CREDIT", "1500.00", "MXN")).header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        verifyNoInteractions(provider);
    }

    // ---------- POST 422: reglas de negocio con las reglas reales ----------

    @ParameterizedTest(name = "{0} {1} {2} → {3}")
    @CsvSource({
            "CREDIT, 1.00,     MXN, AMOUNT_TOO_LOW",
            "DEBIT,  1.00,     MXN, AMOUNT_TOO_LOW",
            "CREDIT, -5.00,    MXN, AMOUNT_TOO_LOW",
            "DEBIT,  10000.01, MXN, DEBIT_AMOUNT_EXCEEDS_LIMIT",
            "CREDIT, 1500.00,  USD, UNSUPPORTED_CURRENCY",
            "CREDIT, 1500.00,  mxn, UNSUPPORTED_CURRENCY"})
    @DisplayName("RN01–RN03: una regla violada responde 422 con su code y el proveedor no recibe la llamada")
    void businessRuleViolationRespondsUnprocessable(String type, String amount, String currency, String code)
            throws Exception {
        mvc.perform(postTransaction(json(type, amount, currency)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail").value(BusinessRuleViolation.valueOf(code).message()));

        verifyNoInteractions(provider);
        verify(repository, never()).saveAndFlush(any());
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "CREDIT, 1.01",
            "DEBIT,  1.01",
            "DEBIT,  10000.00",
            "CREDIT, 10000.01",
            "CREDIT, 999999999.99"})
    @DisplayName("RN01–RN02: los montos en el borde permitido responden 201 y llegan al proveedor")
    void amountsWithinLimitsAreCreated(String type, String amount) throws Exception {
        mvc.perform(postTransaction(json(type, amount, "MXN")))
                .andExpect(status().isCreated());

        verify(provider).execute(new ProviderRequest("acc-123456", TransactionType.valueOf(type),
                new BigDecimal(amount), "MXN"));
    }

    // ---------- POST 409 ----------

    @Test
    @DisplayName("misma Idempotency-Key con otro payload responde 409 IDEMPOTENCY_KEY_CONFLICT")
    void idempotencyKeyConflictRespondsConflict() throws Exception {
        Transaction existing = withIdAndCreatedAt(Transaction.pending(command("key-1")));
        when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        mvc.perform(postTransaction(json("CREDIT", "1600.00", "MXN")).header("Idempotency-Key", "key-1"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.detail").value("La Idempotency-Key ya se usó con un payload distinto"));
        verifyNoInteractions(provider);
    }

    // ---------- POST 400 ----------

    @Test
    @DisplayName("un body sin campos obligatorios responde 400 con un error por campo y sin llamar al proveedor")
    void missingFieldsRespondBadRequest() throws Exception {
        mvc.perform(postTransaction("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(containsInAnyOrder("accountId", "type", "amount", "currency")))
                .andExpect(jsonPath("$.errors[0].message").value("es obligatorio"));

        verifyNoInteractions(provider);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(strings = {"1500.005", "100000000000000000.00"})
    @DisplayName("un monto con más de 2 decimales o más de 17 enteros responde 400 en amount, no 500")
    void amountOutOfColumnFormatRespondsBadRequest(String amount) throws Exception {
        mvc.perform(postTransaction(json("CREDIT", amount, "MXN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("admite como máximo 17 enteros y 2 decimales"));

        verifyNoInteractions(provider);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("accountId de más de 100 o description de más de 255 caracteres responde 400 por campo")
    void tooLongTextsRespondBadRequest() throws Exception {
        String body = """
                {"accountId":"%s","type":"CREDIT","amount":1500.00,"currency":"MXN","description":"%s"}
                """.formatted("a".repeat(101), "d".repeat(256));

        mvc.perform(postTransaction(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("accountId", "description")));
    }

    @Test
    @DisplayName("una Idempotency-Key de 101 caracteres responde 400 con el header como campo")
    void tooLongIdempotencyKeyRespondsBadRequest() throws Exception {
        mvc.perform(postTransaction(json("CREDIT", "1500.00", "MXN")).header("Idempotency-Key", "k".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.errors[0].message").value("admite como máximo 100 caracteres"));

        verifyNoInteractions(provider);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("un type fuera de CREDIT/DEBIT responde 400 MALFORMED_REQUEST señalando el campo")
    void unknownTypeRespondsBadRequest() throws Exception {
        mvc.perform(postTransaction(json("TRANSFER", "1500.00", "MXN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value("type"))
                .andExpect(jsonPath("$.detail").value(not(containsString("tools.jackson"))));

        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("un JSON mal formado responde 400 MALFORMED_REQUEST con lista de errores")
    void malformedJsonRespondsBadRequest() throws Exception {
        mvc.perform(postTransaction("{\"accountId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.detail").value("El cuerpo de la solicitud no es un JSON válido"));

        verifyNoInteractions(provider);
    }

    // ---------- POST 500 ----------

    @Test
    @DisplayName("una falla inesperada de BD responde 500 INTERNAL_ERROR sin detalles internos")
    void unexpectedFailureRespondsInternalErrorWithoutDetails() throws Exception {
        doThrow(new DataAccessResourceFailureException("jdbc:postgresql://db-secreta:5432 caída"))
                .when(repository).saveAndFlush(any());

        mvc.perform(postTransaction(json("CREDIT", "1500.00", "MXN")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("Ocurrió un error interno"))
                .andExpect(content().string(not(containsString("db-secreta"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    @DisplayName("si el estado final no se puede guardar (0 filas) responde 500 genérico, sin el id interno")
    void unrecordedOutcomeRespondsInternalError() throws Exception {
        when(repository.completePending(any(), any(), any(), any(), any(), any())).thenReturn(0);

        mvc.perform(postTransaction(json("CREDIT", "1500.00", "MXN")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("PENDING"))))
                .andExpect(content().string(not(containsString(ID.toString()))));
    }

    @Test
    @DisplayName("un método HTTP no soportado responde 405 ProblemDetail con code METHOD_NOT_ALLOWED")
    void unsupportedMethodRespondsProblemDetail() throws Exception {
        mvc.perform(delete("/transactions"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // ---------- GET ----------

    @Test
    @DisplayName("RN07: GET responde 200 con content, page, limit, totalElements y totalPages")
    void searchRespondsPage() throws Exception {
        Transaction executed = withIdAndCreatedAt(Transaction.pending(command(null)));
        executed.markExecuted("txn-789", new BigDecimal("5500.00"));
        Page<Transaction> page = new PageImpl<>(List.of(executed), PageRequest.of(1, 1), 3);
        when(repository.findAll(ArgumentMatchers.<Specification<Transaction>>any(), any(Pageable.class)))
                .thenReturn(page);

        String body = mvc.perform(get("/transactions")
                        .param("accountId", "acc-123456").param("status", "EXECUTED").param("type", "CREDIT")
                        .param("page", "1").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.content[0].status").value("EXECUTED"))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(5500.00))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andReturn().getResponse().getContentAsString();

        JsonNode response = JsonMapper.builder().build().readTree(body);
        assertThat(response.propertyNames())
                .containsExactlyInAnyOrder("content", "page", "limit", "totalElements", "totalPages");
        assertThat(response.get("content").get(0).propertyNames()).containsExactlyInAnyOrderElementsOf(RESPONSE_FIELDS);
        Pageable pageable = capturedPageable();
        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(1);
        assertThat(pageable.getSort()).containsExactly(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        verifyNoInteractions(provider);
    }

    @Test
    @DisplayName("RN07: sin page ni limit se usa la página 0 con 20 elementos")
    void searchUsesDefaults() throws Exception {
        when(repository.findAll(ArgumentMatchers.<Specification<Transaction>>any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        mvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.totalElements").value(0));

        assertThat(capturedPageable().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("RN07: limit 100 se acepta")
    void searchAcceptsMaxLimit() throws Exception {
        when(repository.findAll(ArgumentMatchers.<Specification<Transaction>>any(), any(Pageable.class)))
                .thenAnswer(invocation -> new PageImpl<>(List.of(), invocation.getArgument(1), 0));

        mvc.perform(get("/transactions").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(100));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "limit, 101, debe ser menor o igual a 100",
            "limit, 0,   debe ser mayor o igual a 1",
            "page,  -1,  debe ser mayor o igual a 0"})
    @DisplayName("RN07: limit fuera de 1..100 o page negativa responde 400 con el parámetro como campo")
    void searchRejectsOutOfRangePagination(String parameter, String value, String message) throws Exception {
        mvc.perform(get("/transactions").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value(parameter))
                .andExpect(jsonPath("$.errors[0].message").value(message));

        verifyNoInteractions(repository);
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({"status, DONE", "type, TRANSFER", "limit, diez"})
    @DisplayName("RN07: un filtro o límite con valor inválido responde 400 con el parámetro como campo")
    void searchRejectsInvalidValues(String parameter, String value) throws Exception {
        mvc.perform(get("/transactions").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value(parameter))
                .andExpect(jsonPath("$.errors[0].message").value("tiene un valor inválido"));

        verifyNoInteractions(repository);
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(ArgumentMatchers.<Specification<Transaction>>any(), pageable.capture());
        return pageable.getValue();
    }

    private static MockHttpServletRequestBuilder postTransaction(String body) {
        return post("/transactions").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String json(String type, String amount, String currency) {
        return """
                {"accountId":"acc-123456","type":"%s","amount":%s,"currency":"%s",\
                "description":"Transferencia recibida"}
                """.formatted(type, amount, currency);
    }

    private static ExecuteTransactionCommand command(String idempotencyKey) {
        return new ExecuteTransactionCommand("acc-123456", TransactionType.CREDIT, new BigDecimal("1500.00"), "MXN",
                "Transferencia recibida", idempotencyKey);
    }

    // El id y la fecha los asigna la BD/Hibernate al persistir; en este test no hay BD.
    private static Transaction withIdAndCreatedAt(Transaction transaction) {
        ReflectionTestUtils.setField(transaction, "id", ID);
        ReflectionTestUtils.setField(transaction, "createdAt", CREATED_AT);
        return transaction;
    }
}
