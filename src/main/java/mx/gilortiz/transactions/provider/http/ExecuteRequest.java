package mx.gilortiz.transactions.provider.http;

import java.math.BigDecimal;
import mx.gilortiz.transactions.transaction.ProviderRequest;

/**
 * Cuerpo literal de {@code POST /provider/v1/execute}.
 */
record ExecuteRequest(String accountId, String type, BigDecimal amount, String currency) {

    static ExecuteRequest from(ProviderRequest request) {
        return new ExecuteRequest(request.accountId(), request.type().name(), request.amount(), request.currency());
    }
}
