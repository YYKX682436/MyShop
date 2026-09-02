package com.myshop.service;

import com.myshop.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductListSortTest {

    @Test
    void resolvesEveryAllowedPublicAndAdminSortWithStableTiebreakers() {
        assertSort("sort_weight", "desc", "sortWeight", Sort.Direction.DESC,
                "createdAt", Sort.Direction.DESC, "id", Sort.Direction.ASC);
        assertSort("created_at", "asc", "createdAt", Sort.Direction.ASC,
                "id", Sort.Direction.ASC);
        assertSort("product_no", "asc", "productNo", Sort.Direction.ASC,
                "createdAt", Sort.Direction.DESC, "id", Sort.Direction.ASC);
        assertSort("retail_price", "desc", "retailPrice", Sort.Direction.DESC,
                "createdAt", Sort.Direction.DESC, "id", Sort.Direction.ASC);
        assertSort("cost_price", "asc", "costPrice", Sort.Direction.ASC,
                "createdAt", Sort.Direction.DESC, "id", Sort.Direction.ASC);
    }

    @Test
    void missingSortUsesWeightDescendingAndInvalidValuesFailClosed() {
        Sort defaultSort = ProductListSort.resolve(null, null);
        assertEquals(Sort.Direction.DESC, defaultSort.getOrderFor("sortWeight").getDirection());
        assertEquals(Sort.Direction.DESC, defaultSort.getOrderFor("createdAt").getDirection());
        assertEquals(Sort.Direction.ASC, defaultSort.getOrderFor("id").getDirection());

        assertThrows(BusinessException.class, () -> ProductListSort.resolve("title", "desc"));
        assertThrows(BusinessException.class, () -> ProductListSort.resolve("sort_weight", "random"));
    }

    private void assertSort(String sortBy, String direction, Object... expected) {
        Sort sort = ProductListSort.resolve(sortBy, direction);
        for (int index = 0; index < expected.length; index += 2) {
            String property = (String) expected[index];
            Sort.Direction expectedDirection = (Sort.Direction) expected[index + 1];
            Sort.Order order = sort.getOrderFor(property);
            assertEquals(expectedDirection, order.getDirection(), property);
        }
        assertEquals(expected.length / 2, sort.stream().count());
    }
}
