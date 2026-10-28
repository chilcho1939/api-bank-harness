package mx.gilortiz.transactions.provider.http;

import mx.gilortiz.transactions.transaction.TransactionProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProviderProperties.class)
class ProviderClientConfiguration {

    // Spring cierra el adaptador (y su HttpClient) al apagar el contexto: HttpTransactionProvider es AutoCloseable.
    @Bean
    TransactionProvider transactionProvider(RestClient.Builder restClientBuilder, ProviderProperties properties) {
        return HttpTransactionProvider.create(restClientBuilder, properties);
    }
}
