package mx.gilortiz.transactions;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OpenApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final MockMvc mvc;

    @Autowired
    OpenApiIT(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    @DisplayName("/v3/api-docs publica POST y GET /transactions con el header Idempotency-Key y los filtros de RN07")
    void apiDocsDescribeTransactionsEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/transactions'].post").exists())
                .andExpect(jsonPath("$.paths['/transactions'].get").exists())
                .andExpect(jsonPath("$.paths['/transactions'].post.parameters[?(@.name == 'Idempotency-Key')].in")
                        .value("header"))
                .andExpect(jsonPath("$.paths['/transactions'].get.parameters[*].name")
                        .value(containsInAnyOrder("accountId", "status", "type", "page", "limit")))
                .andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.providerTransactionId")
                        .exists());
    }

    @Test
    @DisplayName("/v3/api-docs documenta las respuestas de POST: 201 y 200 con TransactionResponse; errores como "
            + "problem+json")
    void apiDocsDescribePostResponses() throws Exception {
        String post = "$.paths['/transactions'].post.responses";
        String search = "$.paths['/transactions'].get.responses";
        String transactionSchema = "#/components/schemas/TransactionResponse";
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(post + ".keys()")
                        .value(containsInAnyOrder("200", "201", "400", "409", "422", "500")))
                .andExpect(jsonPath(post + "['201'].content['application/json'].schema.$ref")
                        .value(transactionSchema))
                .andExpect(jsonPath(post + "['200'].content['application/json'].schema.$ref")
                        .value(transactionSchema))
                .andExpect(jsonPath(post + "['400'].content['application/problem+json']").exists())
                .andExpect(jsonPath(post + "['409'].content['application/problem+json']").exists())
                .andExpect(jsonPath(post + "['422'].content['application/problem+json']").exists())
                .andExpect(jsonPath(post + "['500'].content['application/problem+json']").exists())
                .andExpect(jsonPath(post + ".*.content['*/*']").isEmpty())
                .andExpect(jsonPath(search + "['400'].content['application/problem+json']").exists())
                .andExpect(jsonPath(search + "['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/PageResponseTransactionResponse"))
                .andExpect(jsonPath(search + ".*.content['*/*']").isEmpty());
    }

    @Test
    @DisplayName("/swagger-ui.html redirige a la UI de Swagger y la UI responde 200")
    void swaggerUiIsAvailable() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/swagger-ui/index.html")));

        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Swagger UI")));
    }
}
