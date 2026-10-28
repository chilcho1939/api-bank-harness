package mx.gilortiz.transactions.transaction;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

/**
 * Cuerpos literales de la spec para el proveedor (WireMock) y para {@code POST /transactions}.
 */
final class ProviderStubs {

    static final String EXECUTE_PATH = "/provider/v1/execute";
    static final String APPROVED_BODY = """
            { "transactionId": "txn-789", "status": "APPROVED", "balance": 5500.00, "executedAt": "2025-03-15T10:30:00Z" }
            """;
    static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    static final String INSUFFICIENT_FUNDS_MESSAGE =
            "The account does not have enough balance to complete the transaction";
    static final String REJECTED_BODY = """
            { "status": "REJECTED", "code": "%s", "message": "%s" }
            """.formatted(INSUFFICIENT_FUNDS, INSUFFICIENT_FUNDS_MESSAGE);
    static final String SPEC_REQUEST = request("acc-123456", "CREDIT", "1500.00", "MXN");

    private ProviderStubs() {
    }

    static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    static String request(String accountId, String type, String amount, String currency) {
        return """
                { "accountId": "%s", "type": "%s", "amount": %s, "currency": "%s", "description": "Transferencia recibida" }
                """.formatted(accountId, type, amount, currency);
    }
}
