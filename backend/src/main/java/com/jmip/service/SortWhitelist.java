package com.jmip.service;

import com.jmip.common.exception.InvalidRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

/**
 * Translates the sort fields a client may name into the entity paths behind them.
 *
 * <p>Two reasons this exists rather than passing the client's {@code Sort} straight
 * through. An unknown property reaches Spring Data as a {@code PropertyReferenceException}
 * deep inside query building, which surfaces as a 500 for what is really a bad request.
 * And an open sort parameter exposes the entity's field names, and therefore the schema,
 * as part of the public contract.
 */
public final class SortWhitelist {

    private final Map<String, String> allowedFields;

    private SortWhitelist(Map<String, String> allowedFields) {
        this.allowedFields = allowedFields;
    }

    public static SortWhitelist of(Map<String, String> allowedFields) {
        return new SortWhitelist(Map.copyOf(allowedFields));
    }

    /**
     * @return the same pageable with its sort rewritten to entity paths
     * @throws InvalidRequestException if the client named a field that is not sortable
     */
    public Pageable apply(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        Sort translated = Sort.by(pageable.getSort().stream()
                .map(order -> {
                    String path = allowedFields.get(order.getProperty());
                    if (path == null) {
                        throw new InvalidRequestException(
                                "Cannot sort by '" + order.getProperty() + "'. Sortable fields: "
                                        + String.join(", ", allowedFields.keySet().stream().sorted().toList()));
                    }
                    return new Sort.Order(order.getDirection(), path, order.getNullHandling());
                })
                .toList());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), translated);
    }

}
