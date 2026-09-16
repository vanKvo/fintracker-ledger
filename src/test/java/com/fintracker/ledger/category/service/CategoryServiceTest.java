package com.fintracker.ledger.category.service;

import com.fintracker.ledger.category.exception.CategoryAlreadyExistsException;
import com.fintracker.ledger.category.exception.CategoryInUseException;
import com.fintracker.ledger.category.exception.CategoryLimitExceededException;
import com.fintracker.ledger.category.exception.CategoryNotFoundException;
import com.fintracker.ledger.category.exception.InvalidCategoryNameException;
import com.fintracker.ledger.category.exception.SystemCategoryImmutableException;
import com.fintracker.ledger.category.model.Category;
import com.fintracker.ledger.category.repository.CategoryRepository;
import com.fintracker.ledger.category.service.impl.CategoryServiceImpl;
import com.fintracker.ledger.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FAIL-TO-PASS: com.fintracker.ledger.category.* does not exist yet. This suite is written
 * against ledger-transaction-spec-01.md's Technical References and is expected to fail to
 * compile until CategoryService/CategoryServiceImpl, the Category domain model, the six
 * category.exception types, and CategoryRepository are implemented.
 *
 * <p>SIGNATURE POLICY — the spec is the source of truth; where it leaves a type unnamed, this
 * suite resolves it as follows (call these out for review when implementing):
 * <ul>
 *   <li>{@code CustomCategory} in the spec's Interface Details is never defined in Data
 *       Contracts. This suite treats {@code CategoryService} as returning the domain model
 *       {@link Category} directly (categoryId, normalized categoryName, {@code Level}, userId),
 *       mirroring how {@code StatementService} exposes domain records at the service layer and
 *       maps to response DTOs at the controller boundary — see CategoryControllerTest.</li>
 *   <li>{@code CategoryRepository} is assumed to expose: {@code findByIdAndAccessibleToUser}
 *       (a category is "accessible" if it is SYSTEM-level or owned by the given user — the same
 *       set Requested Changes #1 says is shown to that user), {@code findAllAccessibleToUser},
 *       {@code existsByNormalizedNameAccessibleToUser} (the collision check backing #5),
 *       {@code countByUserId} (the cap check backing #9), and insert/update/delete.</li>
 *   <li>Requested Changes #6 (reassign-before-delete) is assumed to be backed by
 *       {@code TransactionRepository.countByCategoryIdAndUserId} and
 *       {@code TransactionRepository.reassignCategory(oldCategoryId, newCategoryId, userId)}.</li>
 * </ul>
 *
 * <p>Coverage map to REQ-TS-01's Requested Changes:
 * <ul>
 *   <li>#1 Customizing categories, #4 Display of categories — GetAllCategoriesForUser</li>
 *   <li>#2 User-level Category Isolation (view + modify) — every Nested class's tenancy cases</li>
 *   <li>#3 Normalization — CreateCustomCategory.normalization, UpdateCustomCategory</li>
 *   <li>#5 Name collisions — CreateCustomCategory.collisions, UpdateCustomCategory</li>
 *   <li>#6 Deleting an existing custom category — DeleteCustomCategory</li>
 *   <li>#7 Updating/Renaming — UpdateCustomCategory (categoryId is stable across rename)</li>
 *   <li>#9 Constraints (50-category cap, 100-char max length) — CreateCustomCategory.constraints</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryService Unit Tests")
class CategoryServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private TransactionRepository transactionRepository;

    private CategoryService newService() {
        return new CategoryServiceImpl(categoryRepository, transactionRepository);
    }

    private Category systemCategory(String normalizedName) {
        return new Category(UUID.randomUUID(), normalizedName, Category.Level.SYSTEM, null);
    }

    private Category userCategory(UUID userId, String normalizedName) {
        return new Category(UUID.randomUUID(), normalizedName, Category.Level.USER, userId);
    }

    @Nested
    @DisplayName("createCustomCategory()")
    class CreateCustomCategory {

        @Test
        @DisplayName("#3: rejects a name containing characters outside alphanumeric/space")
        void rejectsInvalidCharacters() {
            var userId = UUID.randomUUID();
            var service = newService();

            assertThatThrownBy(() -> service.createCustomCategory("Auto-Tax!", userId))
                    .isInstanceOf(InvalidCategoryNameException.class);

            verify(categoryRepository, never()).insert(any(), any(), any());
        }

        @Test
        @DisplayName("#3: normalizes to lowercase with a single underscore between words before storing")
        void normalizesBeforeStoring() {
            var userId = UUID.randomUUID();
            when(categoryRepository.existsByNormalizedNameAccessibleToUser(anyString(), eq(userId)))
                    .thenReturn(false);
            when(categoryRepository.countByUserId(userId)).thenReturn(0L);
            when(categoryRepository.insert(any(), eq("auto_property_tax"), eq(userId)))
                    .thenReturn(new Category(UUID.randomUUID(), "auto_property_tax", Category.Level.USER, userId));

            newService().createCustomCategory("  Auto   Property   Tax  ", userId);

            verify(categoryRepository).insert(any(), eq("auto_property_tax"), eq(userId));
        }

        @Test
        @DisplayName("#3/#9: rejects a name that normalizes to blank (spaces only)")
        void rejectsBlankAfterNormalization() {
            var userId = UUID.randomUUID();
            assertThatThrownBy(() -> newService().createCustomCategory("   ", userId))
                    .isInstanceOf(InvalidCategoryNameException.class);
        }

        @Test
        @DisplayName("#9: rejects a category name longer than 100 characters")
        void rejectsNameOverMaxLength() {
            var userId = UUID.randomUUID();
            assertThatThrownBy(() -> newService().createCustomCategory("a".repeat(101), userId))
                    .isInstanceOf(InvalidCategoryNameException.class);
        }

        @Test
        @DisplayName("#9: accepts a category name of exactly 100 characters")
        void acceptsNameAtMaxLength() {
            var userId = UUID.randomUUID();
            var name = "a".repeat(100);
            when(categoryRepository.existsByNormalizedNameAccessibleToUser(anyString(), eq(userId)))
                    .thenReturn(false);
            when(categoryRepository.countByUserId(userId)).thenReturn(0L);
            when(categoryRepository.insert(any(), eq(name), eq(userId)))
                    .thenReturn(new Category(UUID.randomUUID(), name, Category.Level.USER, userId));

            assertThat(newService().createCustomCategory(name, userId)).isNotNull();
        }

        @Test
        @DisplayName("#5: rejects a name colliding with an existing SYSTEM-level category")
        void rejectsCollisionWithSystemCategory() {
            var userId = UUID.randomUUID();
            when(categoryRepository.existsByNormalizedNameAccessibleToUser("groceries", userId))
                    .thenReturn(true);

            assertThatThrownBy(() -> newService().createCustomCategory("Groceries", userId))
                    .isInstanceOf(CategoryAlreadyExistsException.class);

            verify(categoryRepository, never()).insert(any(), any(), any());
        }

        @Test
        @DisplayName("#5: rejects a name colliding with the user's own existing custom category")
        void rejectsCollisionWithOwnCustomCategory() {
            var userId = UUID.randomUUID();
            when(categoryRepository.existsByNormalizedNameAccessibleToUser("auto_tax", userId))
                    .thenReturn(true);

            assertThatThrownBy(() -> newService().createCustomCategory("auto   tax", userId))
                    .isInstanceOf(CategoryAlreadyExistsException.class);
        }

        @Test
        @DisplayName("#2: the same normalized name is allowed for two different users")
        void sameNameAllowedAcrossDifferentUsers() {
            var userA = UUID.randomUUID();
            var userB = UUID.randomUUID();
            when(categoryRepository.existsByNormalizedNameAccessibleToUser("auto_tax", userA)).thenReturn(false);
            when(categoryRepository.existsByNormalizedNameAccessibleToUser("auto_tax", userB)).thenReturn(false);
            when(categoryRepository.countByUserId(any())).thenReturn(0L);
            when(categoryRepository.insert(any(), eq("auto_tax"), eq(userA)))
                    .thenReturn(userCategory(userA, "auto_tax"));
            when(categoryRepository.insert(any(), eq("auto_tax"), eq(userB)))
                    .thenReturn(userCategory(userB, "auto_tax"));

            assertThat(newService().createCustomCategory("Auto Tax", userA)).isNotNull();
            assertThat(newService().createCustomCategory("Auto Tax", userB)).isNotNull();
        }

        @Test
        @DisplayName("#9: rejects creation when the user already has 50 custom categories")
        void rejectsAtCap() {
            var userId = UUID.randomUUID();
            when(categoryRepository.existsByNormalizedNameAccessibleToUser(anyString(), eq(userId)))
                    .thenReturn(false);
            when(categoryRepository.countByUserId(userId)).thenReturn(50L);

            assertThatThrownBy(() -> newService().createCustomCategory("One More", userId))
                    .isInstanceOf(CategoryLimitExceededException.class);

            verify(categoryRepository, never()).insert(any(), any(), any());
        }

        @Test
        @DisplayName("#9: the 50th custom category is accepted (boundary, not one past it)")
        void acceptsExactlyAtBoundary() {
            var userId = UUID.randomUUID();
            when(categoryRepository.existsByNormalizedNameAccessibleToUser(anyString(), eq(userId)))
                    .thenReturn(false);
            when(categoryRepository.countByUserId(userId)).thenReturn(49L);
            when(categoryRepository.insert(any(), eq("fiftieth"), eq(userId)))
                    .thenReturn(userCategory(userId, "fiftieth"));

            assertThat(newService().createCustomCategory("Fiftieth", userId)).isNotNull();
        }

        @Test
        @DisplayName("returns the created category carrying a generated categoryId and USER level")
        void returnsCreatedCategory() {
            var userId = UUID.randomUUID();
            when(categoryRepository.existsByNormalizedNameAccessibleToUser(anyString(), eq(userId)))
                    .thenReturn(false);
            when(categoryRepository.countByUserId(userId)).thenReturn(0L);
            var created = userCategory(userId, "side_hustle");
            when(categoryRepository.insert(any(), eq("side_hustle"), eq(userId))).thenReturn(created);

            var result = newService().createCustomCategory("Side Hustle", userId);

            assertThat(result.categoryId()).isNotNull();
            assertThat(result.level()).isEqualTo(Category.Level.USER);
            assertThat(result.userId()).isEqualTo(userId);
        }
    }

    @Nested
    @DisplayName("updateCustomCategory()")
    class UpdateCustomCategory {

        @Test
        @DisplayName("#2: updating a category not owned by the caller fails as not-found")
        void rejectsUpdateOfAnotherUsersCategory() {
            var owner = UUID.randomUUID();
            var attacker = UUID.randomUUID();
            var categoryId = UUID.randomUUID();
            when(categoryRepository.findByIdAndAccessibleToUser(categoryId, attacker))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> newService().updateCustomCategory(categoryId, "New Name", attacker))
                    .isInstanceOf(CategoryNotFoundException.class);

            verify(categoryRepository, never()).update(any(), any());
        }

        @Test
        @DisplayName("#2: updating a SYSTEM-level category is rejected as immutable")
        void rejectsUpdateOfSystemCategory() {
            var userId = UUID.randomUUID();
            var system = systemCategory("groceries");
            when(categoryRepository.findByIdAndAccessibleToUser(system.categoryId(), userId))
                    .thenReturn(Optional.of(system));

            assertThatThrownBy(() -> newService().updateCustomCategory(system.categoryId(), "Food", userId))
                    .isInstanceOf(SystemCategoryImmutableException.class);

            verify(categoryRepository, never()).update(any(), any());
        }

        @Test
        @DisplayName("#3: re-validates character rules on rename")
        void reappliesCharacterValidationOnRename() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "auto_tax");
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> newService()
                    .updateCustomCategory(existing.categoryId(), "Auto-Tax!", userId))
                    .isInstanceOf(InvalidCategoryNameException.class);
        }

        @Test
        @DisplayName("#5: rejects a rename that collides with a different existing category")
        void rejectsRenameCollidingWithAnotherCategory() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "auto_tax");
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));
            when(categoryRepository.existsByNormalizedNameAccessibleToUser("groceries", userId))
                    .thenReturn(true);

            assertThatThrownBy(() -> newService()
                    .updateCustomCategory(existing.categoryId(), "Groceries", userId))
                    .isInstanceOf(CategoryAlreadyExistsException.class);
        }

        @Test
        @DisplayName("renaming a category to its own current name is not a self-collision")
        void renamingToOwnCurrentNameIsNotACollision() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "auto_tax");
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));
            when(categoryRepository.update(existing.categoryId(), "auto_tax"))
                    .thenReturn(userCategory(userId, "auto_tax"));

            // Re-submitting "Auto   Tax" normalizes back to the identical stored value "auto_tax" —
            // must not be rejected as a duplicate of itself.
            assertThat(newService().updateCustomCategory(existing.categoryId(), "Auto   Tax", userId))
                    .isNotNull();
            verify(categoryRepository, never()).existsByNormalizedNameAccessibleToUser(anyString(), any());
        }

        @Test
        @DisplayName("#7: rename preserves categoryId — the same id identifies the category before and after")
        void renamePreservesCategoryId() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "auto_tax");
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));
            when(categoryRepository.existsByNormalizedNameAccessibleToUser("side_hustle", userId))
                    .thenReturn(false);
            when(categoryRepository.update(existing.categoryId(), "side_hustle"))
                    .thenReturn(new Category(existing.categoryId(), "side_hustle", Category.Level.USER, userId));

            var updated = newService().updateCustomCategory(existing.categoryId(), "Side Hustle", userId);

            assertThat(updated.categoryId()).isEqualTo(existing.categoryId());
            assertThat(updated.categoryName()).isEqualTo("side_hustle");
        }
    }

    @Nested
    @DisplayName("deleteCustomCategory()")
    class DeleteCustomCategory {

        @Test
        @DisplayName("#2: deleting a category not owned by the caller fails as not-found")
        void rejectsDeleteOfAnotherUsersCategory() {
            var attacker = UUID.randomUUID();
            var categoryId = UUID.randomUUID();
            when(categoryRepository.findByIdAndAccessibleToUser(categoryId, attacker))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> newService().deleteCustomCategory(categoryId, attacker, null))
                    .isInstanceOf(CategoryNotFoundException.class);

            verify(categoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("#2: deleting a SYSTEM-level category is rejected as immutable")
        void rejectsDeleteOfSystemCategory() {
            var userId = UUID.randomUUID();
            var system = systemCategory("groceries");
            when(categoryRepository.findByIdAndAccessibleToUser(system.categoryId(), userId))
                    .thenReturn(Optional.of(system));

            assertThatThrownBy(() -> newService().deleteCustomCategory(system.categoryId(), userId, null))
                    .isInstanceOf(SystemCategoryImmutableException.class);

            verify(categoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("#6: deleting an unused custom category succeeds without a reassignment target")
        void deletesUnusedCategoryWithoutReassignment() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "one_off");
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));
            when(transactionRepository.countByCategoryIdAndUserId(existing.categoryId(), userId))
                    .thenReturn(0L);

            newService().deleteCustomCategory(existing.categoryId(), userId, null);

            verify(categoryRepository).delete(existing.categoryId());
            verify(transactionRepository, never()).reassignCategory(any(), any(), any());
        }

        @Test
        @DisplayName("#6: deleting a category with referencing transactions and no reassignment target is rejected")
        void rejectsDeleteInUseWithoutReassignmentTarget() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "commute");
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));
            when(transactionRepository.countByCategoryIdAndUserId(existing.categoryId(), userId))
                    .thenReturn(12L);

            assertThatThrownBy(() -> newService().deleteCustomCategory(existing.categoryId(), userId, null))
                    .isInstanceOf(CategoryInUseException.class)
                    .satisfies(ex -> assertThat(((CategoryInUseException) ex).getTransactionCount()).isEqualTo(12L));

            verify(categoryRepository, never()).delete(any());
        }

        @Test
        @DisplayName("#6: deleting with a valid reassignment target reassigns transactions, then deletes")
        void deletesWithValidReassignmentTarget() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "commute");
            var target = systemCategory("transportation");
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));
            when(categoryRepository.findByIdAndAccessibleToUser(target.categoryId(), userId))
                    .thenReturn(Optional.of(target));
            when(transactionRepository.countByCategoryIdAndUserId(existing.categoryId(), userId))
                    .thenReturn(12L);

            newService().deleteCustomCategory(existing.categoryId(), userId, target.categoryId());

            verify(transactionRepository).reassignCategory(existing.categoryId(), target.categoryId(), userId);
            verify(categoryRepository).delete(existing.categoryId());
        }

        @Test
        @DisplayName("rejects a reassignment target equal to the category being deleted")
        void rejectsSelfReassignment() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "commute");
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));
            when(transactionRepository.countByCategoryIdAndUserId(existing.categoryId(), userId))
                    .thenReturn(3L);

            assertThatThrownBy(() -> newService()
                    .deleteCustomCategory(existing.categoryId(), userId, existing.categoryId()))
                    .isInstanceOf(IllegalArgumentException.class);

            verify(categoryRepository, never()).delete(any());
            verify(transactionRepository, never()).reassignCategory(any(), any(), any());
        }

        @Test
        @DisplayName("rejects a reassignment target not visible to the user (another user's custom category)")
        void rejectsReassignmentTargetNotVisibleToUser() {
            var userId = UUID.randomUUID();
            var existing = userCategory(userId, "commute");
            var otherUsersCategory = UUID.randomUUID();
            when(categoryRepository.findByIdAndAccessibleToUser(existing.categoryId(), userId))
                    .thenReturn(Optional.of(existing));
            when(categoryRepository.findByIdAndAccessibleToUser(otherUsersCategory, userId))
                    .thenReturn(Optional.empty());
            when(transactionRepository.countByCategoryIdAndUserId(existing.categoryId(), userId))
                    .thenReturn(3L);

            assertThatThrownBy(() -> newService()
                    .deleteCustomCategory(existing.categoryId(), userId, otherUsersCategory))
                    .isInstanceOf(CategoryNotFoundException.class);

            verify(categoryRepository, never()).delete(any());
        }
    }

    @Nested
    @DisplayName("getAllCategoriesForUser()")
    class GetAllCategoriesForUser {

        @Test
        @DisplayName("#1: merges SYSTEM and the caller's USER categories into one list")
        void mergesSystemAndUserCategories() {
            var userId = UUID.randomUUID();
            when(categoryRepository.findAllAccessibleToUser(userId)).thenReturn(List.of(
                    systemCategory("groceries"), userCategory(userId, "auto_tax")));

            var result = newService().getAllCategoriesForUser(userId);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("#1: with no custom categories, only system-level categories are returned")
        void returnsOnlySystemWhenNoCustomCategoriesExist() {
            var userId = UUID.randomUUID();
            when(categoryRepository.findAllAccessibleToUser(userId))
                    .thenReturn(List.of(systemCategory("groceries"), systemCategory("dining")));

            var result = newService().getAllCategoriesForUser(userId);

            assertThat(result).allMatch(c -> c.level() == Category.Level.SYSTEM);
        }

        @Test
        @DisplayName("#2: another user's custom categories never appear in this user's list")
        void anotherUsersCustomCategoriesAreExcluded() {
            var userId = UUID.randomUUID();
            var otherUser = UUID.randomUUID();
            // The repository contract itself is scoped to accessible rows — this pins that the
            // service passes the caller's own id through and does not, say, fetch everything and
            // filter client-side (which a future refactor could silently break).
            when(categoryRepository.findAllAccessibleToUser(userId))
                    .thenReturn(List.of(systemCategory("groceries"), userCategory(userId, "auto_tax")));

            var result = newService().getAllCategoriesForUser(userId);

            assertThat(result).noneMatch(c -> otherUser.equals(c.userId()));
        }

        @Test
        @DisplayName("#4: the combined list is sorted alphabetically by display name, not by stored form")
        void sortedAlphabeticallyByDisplayName() {
            var userId = UUID.randomUUID();
            // Stored order deliberately not alphabetical, to prove sorting happens rather than
            // passing repository order through untouched.
            when(categoryRepository.findAllAccessibleToUser(userId)).thenReturn(List.of(
                    systemCategory("utilities"), userCategory(userId, "auto_property_tax"),
                    systemCategory("dining")));

            var result = newService().getAllCategoriesForUser(userId);

            // "Auto Property Tax" < "Dining" < "Utilities"
            assertThat(result).extracting(Category::categoryName)
                    .containsExactly("auto_property_tax", "dining", "utilities");
        }
    }

    @Nested
    @DisplayName("countTransactionsUsingCategory() / usage check")
    class CountTransactionsUsingCategory {

        @Test
        @DisplayName("delegates to TransactionRepository, scoped to the requesting user")
        void delegatesToTransactionRepositoryScopedToUser() {
            var userId = UUID.randomUUID();
            var categoryId = UUID.randomUUID();
            when(categoryRepository.findByIdAndAccessibleToUser(categoryId, userId))
                    .thenReturn(Optional.of(userCategory(userId, "commute")));
            when(transactionRepository.countByCategoryIdAndUserId(categoryId, userId)).thenReturn(7L);

            assertThat(newService().countTransactionsUsingCategory(categoryId, userId)).isEqualTo(7L);
        }

        @Test
        @DisplayName("#2: checking usage of a category not accessible to the caller fails as not-found")
        void rejectsUsageCheckForInaccessibleCategory() {
            var userId = UUID.randomUUID();
            var categoryId = UUID.randomUUID();
            when(categoryRepository.findByIdAndAccessibleToUser(categoryId, userId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> newService().countTransactionsUsingCategory(categoryId, userId))
                    .isInstanceOf(CategoryNotFoundException.class);

            verify(transactionRepository, never()).countByCategoryIdAndUserId(any(), any());
        }
    }
}
