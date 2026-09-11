package com.myshop.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.myshop.common.ApiResponse;
import com.myshop.model.request.CategoryUpsertRequest;
import com.myshop.model.response.CategoryResponse;
import com.myshop.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {

    @Mock
    private CategoryService categoryService;

    @Test
    void publicCategoryResponseSerializesTheNestedTreeContract() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        when(categoryService.listCategories()).thenReturn(List.of(
                new CategoryResponse(rootId, "Software", 1, null,
                        List.of(new CategoryResponse(childId, "AI tools", 1, rootId, List.of())))));

        ApiResponse<?> response = new CategoryController(categoryService).listCategories();
        ObjectMapper objectMapper = new ObjectMapper()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        String json = objectMapper.writeValueAsString(response.getData());

        assertTrue(json.contains("\"parent_id\":null"));
        assertTrue(json.contains("\"children\""));
        assertTrue(json.contains("\"parent_id\":\"" + rootId + "\""));
    }

    @Test
    void adminCreateDeserializesParentIdIntoTheTypedRequest() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AdminCategoryController(categoryService)).build();
        UUID parentId = UUID.randomUUID();

        mockMvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" AI tools \",\"sort_order\":2,\"parent_id\":\""
                                + parentId + "\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<CategoryUpsertRequest> captor = ArgumentCaptor.forClass(CategoryUpsertRequest.class);
        verify(categoryService).createCategory(captor.capture());
        assertEquals(" AI tools ", captor.getValue().name());
        assertEquals(2, captor.getValue().sortOrder());
        assertEquals(parentId, captor.getValue().parentId());
    }
}
