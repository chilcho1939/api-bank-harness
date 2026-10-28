package mx.gilortiz.transactions.transaction;

import com.github.tomakehurst.wiremock.extension.ResponseTransformerV2;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extensión de WireMock que retiene cada respuesta del proveedor hasta que el test llama a {@link #open()}:
 * simula un proveedor lento sin demoras fijas y cuenta las peticiones que llegaron. Solo aplica a los stubs que
 * la declaran con {@code withTransformers(ProviderGate.NAME)}.
 */
final class ProviderGate implements ResponseTransformerV2 {

    static final String NAME = "provider-gate";
    // Tope de seguridad: ninguna petición queda retenida para siempre si un test falla antes de abrir.
    private static final Duration MAX_HOLD = Duration.ofSeconds(30);

    private final AtomicInteger arrivals = new AtomicInteger();
    private volatile CountDownLatch release = new CountDownLatch(1);

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    @Override
    public Response transform(Response response, ServeEvent serveEvent) {
        CountDownLatch current = release;
        arrivals.incrementAndGet();
        try {
            current.await(MAX_HOLD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return response;
    }

    /** Peticiones que llegaron al proveedor desde el último {@link #reset()}. */
    int arrivals() {
        return arrivals.get();
    }

    /** Libera las peticiones retenidas y deja pasar las siguientes hasta el próximo {@link #reset()}. */
    void open() {
        release.countDown();
    }

    /** Libera lo retenido, cierra la compuerta y pone el contador en cero. */
    void reset() {
        release.countDown();
        release = new CountDownLatch(1);
        arrivals.set(0);
    }
}
