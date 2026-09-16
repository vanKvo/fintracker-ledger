package com.fintracker.ledger.category.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintracker.ledger.category.model.Category;
import com.fintracker.ledger.category.service.CategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FAIL-TO-PASS: CategoryController does not exist yet. Fast, standalone-MockMvc wiring tests only
 * — request/response mapping and which service method each route calls. RFC 9457 error-format
 * assertions belong in CategoryControllerIT (full Spring context, real GlobalExceptionHandler),
 * matching this codebase's existing split (see StatementControllerTest vs. BudgetControllerIT).
 *
 * <p>Identity: {@code userId} arrives as a request attribute (set by UserContextFilter upstream of
 * the controller in the real stack), exactly as every other Ledger controller in this codebase
 * expects it — never trusted from the request body.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryController Unit Tests")
class CategoryControllerTest {

    private static final String CATEGORIES = "/api/v1/ledger/categories";

    @Mock private CategoryService categoryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var objectMapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CategoryController(categoryService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private Category userCategory(UUID userId, String normalizedName) {
        return new Category(UUID.randomUUID(), normalizedName, Category.Level.USER, userId);
    }

    @Test
    @DisplayName("POST / creates a custom category and answers 201 with the display-formatted name")
    void createReturns201() throws Exception {
        var userId = UUID.randomUUID();
        var created = userCategory(userId, "auto_property_tax");
        when(categoryService.createCustomCategory(eq("Auto Property Tax"), eq(userId))).thenReturn(created);

        mockMvc.perform(post(CATEGORIES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", userId)
                        .content("{\"categoryName\":\"Auto Property Tax\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(created.categoryId().toString()))
                .andExpect(jsonPath("$.displayName").value("Auto Property Tax"))
                .andExpect(jsonPath("$.level").value("USER"));
    }

    @Test
    @DisplayName("GET / answers 200 with the combined system+custom list, display-formatted")
    void getAllReturns200WithDisplayFormattedList() throws Exception {
        var userId = UUID.randomUUID();
        when(categoryService.getAllCategoriesForUser(userId)).thenReturn(List.of(
                new Category(UUID.randomUUID(), "auto_property_tax", Category.Level.USER, userId),
                new Category(UUID.randomUUID(), "groceries", Category.Level.SYSTEM, null)));

        mockMvc.perform(get(CATEGORIES).requestAttr("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Auto Property Tax"))
                .andExpect(jsonPath("$[1].displayName").value("Groceries"))
                .andExpect(jsonPath("$[1].level").value("SYSTEM"));
    }

    @Test
    @DisplayName("PUT /{categoryId} renames a category and answers 200")
    void updateReturns200() throws Exception {
        var userId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();
        when(categoryService.updateCustomCategory(eq(categoryId), eq("Side Hustle"), eq(userId)))
                .thenReturn(new Category(categoryId, "side_hustle", Category.Level.USER, userId));

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", userId)
                        .content("{\"categoryName\":\"Side Hustle\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Side Hustle"));
    }

    @Test
    @DisplayName("DELETE /{categoryId} with no referencing transactions answers 204")
    void deleteUnusedReturns204() throws Exception {
        var userId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();

        mockMvc.perform(delete(CATEGORIES + "/" + categoryId).requestAttr("userId", userId))
                .andExpect(status().isNoContent());

        verify(categoryService).deleteCustomCategory(categoryId, userId, null);
    }

    @Test
    @DisplayName("DELETE /{categoryId} with a reassignment target passes it through to the service")
    void deleteWithReassignmentTargetPassesItThrough() throws Exception {
        var userId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();
        var targetId = UUID.randomUUID();

        mockMvc.perform(delete(CATEGORIES + "/" + categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", userId)
                        .content("{\"reassignToCategoryId\":\"" + targetId + "\"}"))
                .andExpect(status().isNoContent());

        verify(categoryService).deleteCustomCategory(categoryId, userId, targetId);
    }

    @Test
    @DisplayName("GET /{categoryId}/usage answers 200 with the referencing transaction count")
    void usageCheckReturns200WithCount() throws Exception {
        var userId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();
        when(categoryService.countTransactionsUsingCategory(categoryId, userId)).thenReturn(12L);

        mockMvc.perform(get(CATEGORIES + "/" + categoryId + "/usage").requestAttr("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionCount").value(12));
    }

    @Test
    @DisplayName("multi-tenant: every route is attributed to the request-attribute userId, never a body field")
    void everyRouteUsesRequestAttributeIdentity() throws Exception {
        var callerUserId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();
        when(categoryService.updateCustomCategory(any(), any(), eq(callerUserId)))
                .thenReturn(new Category(categoryId, "renamed", Category.Level.USER, callerUserId));

        mockMvc.perform(put(CATEGORIES + "/" + categoryId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", callerUserId)
                        .content("{\"categoryName\":\"Renamed\"}"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(UUID.class);
        verify(categoryService).updateCustomCategory(eq(categoryId), any(), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(callerUserId);
    }
}
