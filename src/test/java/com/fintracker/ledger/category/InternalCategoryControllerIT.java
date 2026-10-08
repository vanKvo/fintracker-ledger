package com.fintracker.ledger.category;

import com.fintracker.ledger.category.repository.CategoryRepository;
import com.fintracker.ledger.shared.UserContextHolder;
import com.fintracker.ledger.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DP-LEDGER-CATEGORIES-02: internal, read-only category endpoints the Data Pipeline caches —
 * SYSTEM categories with an ETag (304 when unchanged), and one user's own categories.
 */
@AutoConfigureMockMvc
class InternalCategoryControllerIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/ledger/categories/internal";
    private static final String CALLER_HEADER = "X-Internal-Caller-Arn";
    private static final String ALLOWED_ARN = "arn:aws:iam::000000000000:role/local-dev-dispatcher";
    private static final String USER_HEADER = "X-Internal-User-Id";
    private static final String NIL_USER = "00000000-0000-0000-0000-000000000000";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    @DisplayName("GET /system returns every SYSTEM category with its code, display name and active flag")
    void systemCategoriesIncludeCodes() throws Exception {
        mockMvc.perform(get(BASE + "/system").header(CALLER_HEADER, ALLOWED_ARN).header(USER_HEADER, NIL_USER))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$", hasSize(17)))
                .andExpect(jsonPath("$[?(@.code=='food-and-drink')].categoryName").value("Food & Drink"))
                .andExpect(jsonPath("$[?(@.code=='uncategorized')].isActive").value(true))
                .andExpect(jsonPath("$[0].categoryId").exists());
    }

    @Test
    @DisplayName("GET /system answers 304 when If-None-Match carries the current ETag")
    void systemCategoriesSupportConditionalGet() throws Exception {
        var etag = mockMvc.perform(get(BASE + "/system").header(CALLER_HEADER, ALLOWED_ARN).header(USER_HEADER, NIL_USER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();

        mockMvc.perform(get(BASE + "/system")
                        .header(CALLER_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, NIL_USER)
                        .header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    @Test
    @DisplayName("GET /users/{userId} returns only that user's categories, including deactivated ones")
    void userCategoriesAreScopedAndIncludeInactive() throws Exception {
        var userId = UUID.randomUUID();
        var otherUser = UUID.randomUUID();
        UserContextHolder.set(userId);
        var active = categoryRepository.insert(UUID.randomUUID(), "side_hustle", userId);
        var inactive = categoryRepository.insert(UUID.randomUUID(), "old_hobby", userId);
        categoryRepository.deactivate(inactive.categoryId());
        UserContextHolder.set(otherUser);
        categoryRepository.insert(UUID.randomUUID(), "not_yours", otherUser);
        UserContextHolder.clear();

        mockMvc.perform(get(BASE + "/users/" + userId)
                        .header(CALLER_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, userId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(2)))
                .andExpect(jsonPath("$.categories[?(@.categoryId=='" + active.categoryId() + "')].categoryName")
                        .value("Side Hustle"))
                .andExpect(jsonPath("$.categories[?(@.categoryId=='" + inactive.categoryId() + "')].isActive")
                        .value(false));
    }

    @Test
    @DisplayName("GET /users/{userId} is refused with 403 when the path user is not the caller's user")
    void userCategoriesRejectAMismatchedUser() throws Exception {
        mockMvc.perform(get(BASE + "/users/" + UUID.randomUUID())
                        .header(CALLER_HEADER, ALLOWED_ARN)
                        .header(USER_HEADER, UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("internal category endpoints reject a caller that is not allow-listed with 401")
    void internalEndpointsRequireAnAllowListedCaller() throws Exception {
        mockMvc.perform(get(BASE + "/system").header(USER_HEADER, NIL_USER))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(BASE + "/system")
                        .header(CALLER_HEADER, "arn:aws:iam::123456789012:role/someone-else")
                        .header(USER_HEADER, NIL_USER))
                .andExpect(status().isUnauthorized());
    }
}
