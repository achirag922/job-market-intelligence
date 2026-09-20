package com.jmip.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * The paging envelope every list endpoint returns.
 *
 * <p>Spring's own {@code Page} is deliberately not serialised directly: its JSON shape is
 * an implementation detail that Spring itself warns is unstable, and it leaks internals
 * such as {@code pageable} and {@code sort} that no client should depend on.
 *
 * @param content       the rows on this page
 * @param page          zero based page number
 * @param size          requested page size
 * @param totalElements total matching rows across all pages
 * @param totalPages    total number of pages
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /** Maps the page's rows on the way out, so services never build a {@code Page} of DTOs. */
    public static <S, T> PagedResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return new PagedResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    public static <T> PagedResponse<T> of(List<T> content, Page<?> page) {
        return new PagedResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
