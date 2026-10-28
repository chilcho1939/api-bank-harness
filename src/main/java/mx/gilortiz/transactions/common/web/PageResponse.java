package mx.gilortiz.transactions.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * Página de resultados con un contrato propio, independiente de la serialización de {@link Page}.
 *
 * @param page número de página, base 0
 */
public record PageResponse<T>(List<T> content, int page, int limit, long totalElements, int totalPages) {

    public static <S, T> PageResponse<T> from(Page<S> source, Function<? super S, ? extends T> mapper) {
        List<T> content = source.getContent().stream().<T>map(mapper).toList();
        return new PageResponse<>(content, source.getNumber(), source.getSize(), source.getTotalElements(),
                source.getTotalPages());
    }
}
