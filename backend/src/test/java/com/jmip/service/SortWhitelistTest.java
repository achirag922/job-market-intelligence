package com.jmip.service;

import com.jmip.common.exception.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortWhitelistTest {

    private final SortWhitelist whitelist = SortWhitelist.of(Map.of(
            "postedDate", "postedDate",
            "title", "title",
            "salary", "salaryMin"));

    @Test
    @DisplayName("passes an unsorted pageable through untouched")
    void unsortedPassesThrough() {
        Pageable pageable = PageRequest.of(1, 25);
        assertThat(whitelist.apply(pageable)).isSameAs(pageable);
    }

    @Test
    @DisplayName("keeps page number, size and direction")
    void keepsPagingAndDirection() {
        Pageable result = whitelist.apply(
                PageRequest.of(2, 15, Sort.by(Sort.Direction.DESC, "postedDate")));

        assertThat(result.getPageNumber()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(15);
        assertThat(result.getSort().getOrderFor("postedDate").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("translates an API field name to its entity path")
    void translatesToEntityPath() {
        Pageable result = whitelist.apply(PageRequest.of(0, 10, Sort.by("salary")));

        assertThat(result.getSort().getOrderFor("salaryMin")).isNotNull();
        assertThat(result.getSort().getOrderFor("salary")).isNull();
    }

    @Test
    @DisplayName("handles several sort orders at once")
    void handlesMultipleOrders() {
        Pageable result = whitelist.apply(
                PageRequest.of(0, 10, Sort.by("title").and(Sort.by(Sort.Direction.DESC, "postedDate"))));

        assertThat(result.getSort()).hasSize(2);
    }

    @Test
    @DisplayName("rejects a field that is not sortable, naming the ones that are")
    void rejectsUnknownField() {
        assertThatThrownBy(() -> whitelist.apply(PageRequest.of(0, 10, Sort.by("description"))))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Cannot sort by 'description'")
                .hasMessageContaining("postedDate");
    }

    @Test
    @DisplayName("rejects an entity path the client should not know about")
    void rejectsRawEntityPath() {
        // "salaryMin" is the internal path; clients sort by "salary".
        assertThatThrownBy(() -> whitelist.apply(PageRequest.of(0, 10, Sort.by("salaryMin"))))
                .isInstanceOf(InvalidRequestException.class);
    }
}
