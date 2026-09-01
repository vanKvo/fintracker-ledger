package com.fintracker.ledger.budget.service.impl;

import com.fintracker.ledger.budget.dto.QuickStartBudgetRequest;
import com.fintracker.ledger.budget.exception.DuplicateTemplateException;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.Budget;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.budget.model.BudgetTemplate;
import com.fintracker.ledger.budget.model.BudgetTemplateLine;
import com.fintracker.ledger.budget.repository.BudgetTemplateRepository;
import com.fintracker.ledger.budget.service.BudgetService;
import com.fintracker.ledger.budget.service.BudgetTemplateService;
import com.fintracker.ledger.budget.validation.BudgetMonetaryPolicy;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * REQ-5.3 "Quick Start Templates".
 *
 * <p>Instantiation is layered on top of {@link BudgetService#upsertBudget}, deliberately. Every
 * REQ-5.1 invariant a budget must satisfy — month normalization, the CLOSED write guard, the
 * 50-line ceiling, monetary range and scale, category uniqueness, spend enrichment — is enforced
 * there and nowhere else. Re-implementing any of it here would create a second, divergent
 * definition of a valid budget, reachable through a different endpoint.
 */
@Service
public class BudgetTemplateServiceImpl implements BudgetTemplateService {

    private static final Logger log = LoggerFactory.getLogger(BudgetTemplateServiceImpl.class);

    /** REQ-5.3 B / REQ-5.2 B: category names are capped at the template column width. */
    private static final int MAX_CATEGORY_LENGTH = 50;
    private static final int MAX_TEMPLATE_NAME_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 255;

    private final BudgetTemplateRepository templateRepository;
    private final BudgetService budgetService;

    public BudgetTemplateServiceImpl(BudgetTemplateRepository templateRepository,
                                     BudgetService budgetService) {
        this.templateRepository = templateRepository;
        this.budgetService = budgetService;
    }

    @Override
    public List<BudgetTemplate> getAvailableTemplates(UUID userId) {
        requireUser(userId);
        var templates = templateRepository.findAvailable(userId);
        log.debug("Listed budget templates. userId={} count={}", userId, templates.size());
        return templates;
    }

    @Override
    public BudgetTemplate getTemplateById(UUID userId, UUID templateId) {
        requireUser(userId);
        if (templateId == null) {
            throw new ResourceNotFoundException("BudgetTemplate", null);
        }
        return templateRepository.findVisibleById(userId, templateId)
                // "Does not exist" and "is another user's" are one and the same answer, so a
                // probe cannot confirm that a template it cannot see exists.
                .orElseThrow(() -> new ResourceNotFoundException("BudgetTemplate", templateId));
    }

    @Override
    public Budget instantiateQuickStartBudget(UUID userId, QuickStartBudgetRequest request) {
        requireUser(userId);
        if (request == null || request.effectiveMonth() == null) {
            throw new InvalidBudgetException("effectiveMonth is required.");
        }

        List<BudgetLine> lines = resolveQuickStartLines(userId, request);

        // REQ-5.3 B "Target Line Ceiling" is checked before the write so the failure names the
        // merge as the cause; upsertBudget re-checks it as the authoritative guard.
        if (lines.size() > LineItemLimitExceededException.MAX_LINE_ITEMS) {
            throw new LineItemLimitExceededException(lines.size());
        }

        // templateId is passed as null on purpose: REQ-5.1's templateId parameter means "an
        // existing *budget* to clone", a different concept from REQ-5.3's template catalog. The
        // lines have already been resolved here, and REQ-5.1 treats explicit lines as
        // authoritative over any template.
        var budget = budgetService.upsertBudget(userId, request.effectiveMonth(), null, lines);

        log.info("Instantiated quick-start budget. budgetId={} userId={} month={} templateId={} lineCount={}",
                budget.budgetId(), userId, budget.effectiveMonth(), request.templateId(), lines.size());
        return budget;
    }

    /**
     * REQ-5.3 A "Template Isolation &amp; Copy-on-Instantiate" + "Template Line Item Overrides".
     *
     * <p>Template lines seed the budget; overrides are merged on top. An override whose category
     * already came from the template <em>adjusts</em> that line rather than appending a second
     * one — matched case-insensitively, because REQ-5.2 "Category Uniqueness" is case-insensitive
     * and a merge producing both "Rent" and "rent" would build a budget that violates the rule the
     * moment it is written.
     *
     * <p>The returned lines are detached values with no identity: they become independent
     * {@code ledger.budget_lines} rows, never a reference back to the template.
     */
    private List<BudgetLine> resolveQuickStartLines(UUID userId, QuickStartBudgetRequest request) {
        // Insertion-ordered so the template's own ordering survives into the budget.
        Map<String, BudgetLine> merged = new LinkedHashMap<>();

        if (request.templateId() != null) {
            var template = getTemplateById(userId, request.templateId());
            for (BudgetTemplateLine line : template.lines()) {
                var category = requireCategory(line.categoryName());
                merged.put(key(category), detachedLine(category, line.defaultLimit()));
            }
        }

        if (request.customOverrides() != null) {
            for (var override : request.customOverrides()) {
                if (override == null) {
                    throw new InvalidBudgetException("Override entries must not be null.");
                }
                var category = requireCategory(override.categoryName());
                BudgetMonetaryPolicy.validate(category, override.limitAmount());
                // put() on an existing key adjusts; on a new key appends.
                merged.put(key(category), detachedLine(category, override.limitAmount()));
            }
        }

        if (!merged.isEmpty()) {
            return List.copyOf(merged.values());
        }

        // REQ-5.3 E: "If request.getTemplateId() is null, lines are cloned from the user's most
        // recent active budget." Delegated to REQ-5.1's existing rollover rather than duplicated:
        // upsertBudget with no lines and no template creates an empty budget, and
        // getOrCreateBudgetFromPrevious is what performs the clone.
        if (request.templateId() == null) {
            return rolloverLines(userId, request);
        }

        // A template that exists but has no lines yields an empty budget, not a rollover — the
        // user picked that template and an empty one is a legitimate choice.
        return List.of();
    }

    private List<BudgetLine> rolloverLines(UUID userId, QuickStartBudgetRequest request) {
        if (budgetService.budgetExistsForMonth(userId, request.effectiveMonth())) {
            // The month already has a budget; cloning over it would silently discard the user's
            // existing lines. Leave it to upsertBudget, which treats empty lines as "no change".
            return List.of();
        }
        // getOrCreateBudgetFromPrevious performs REQ-5.1 "Template Inheritance" — the same
        // most-recent-ACTIVE-budget clone REQ-5.3 calls "Previous Month Rollover".
        var seeded = budgetService.getOrCreateBudgetFromPrevious(userId, request.effectiveMonth());
        return seeded.lines().stream()
                .map(l -> detachedLine(l.category(), l.limitAmount()))
                .toList();
    }

    @Override
    public BudgetTemplate createCustomTemplate(UUID userId, String name, String description,
                                               List<BudgetTemplateLine> lines) {
        requireUser(userId);

        var cleanName = name == null ? "" : name.strip();
        if (cleanName.isBlank()) {
            throw new InvalidBudgetException("Template name is required.");
        }
        if (cleanName.length() > MAX_TEMPLATE_NAME_LENGTH) {
            throw new InvalidBudgetException(
                    "Template name must be at most %d characters.".formatted(MAX_TEMPLATE_NAME_LENGTH));
        }
        var cleanDescription = description == null ? null : description.strip();
        if (cleanDescription != null && cleanDescription.length() > MAX_DESCRIPTION_LENGTH) {
            throw new InvalidBudgetException(
                    "Template description must be at most %d characters.".formatted(MAX_DESCRIPTION_LENGTH));
        }

        var cleanLines = validateTemplateLines(lines);

        // REQ-5.3 B "Template Name Uniqueness". Checked here so the user gets a 409 that names the
        // conflict; the partial unique index is the authoritative guard and is caught below,
        // because two concurrent saves can both pass this check.
        if (templateRepository.existsByUserAndNameIgnoreCase(userId, cleanName)) {
            throw DuplicateTemplateException.forName(cleanName);
        }

        var draft = new BudgetTemplate(null, userId, cleanName, cleanDescription, false, List.of(), null);
        try {
            var saved = templateRepository.saveCustom(draft, cleanLines);
            log.info("Created custom budget template. templateId={} userId={} lineCount={}",
                    saved.templateId(), userId, cleanLines.size());
            return saved;
        } catch (DuplicateKeyException raceLoss) {
            log.info("Lost the create race for template name='{}' userId={}", cleanName, userId);
            throw DuplicateTemplateException.forName(cleanName);
        }
    }

    /**
     * REQ-5.3 B: at most 50 lines, no duplicate categories (case-insensitive), and every
     * {@code defaultLimit} inside REQ-5.1's monetary range and scale.
     */
    private List<BudgetTemplateLine> validateTemplateLines(List<BudgetTemplateLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        if (lines.size() > LineItemLimitExceededException.MAX_LINE_ITEMS) {
            throw new LineItemLimitExceededException(lines.size());
        }

        Map<String, BudgetTemplateLine> seen = new LinkedHashMap<>();
        for (BudgetTemplateLine line : lines) {
            if (line == null) {
                throw new InvalidBudgetException("Template lines must not contain null entries.");
            }
            var category = requireCategory(line.categoryName());
            BudgetMonetaryPolicy.validate(category, line.defaultLimit());
            if (seen.putIfAbsent(key(category), new BudgetTemplateLine(
                    null, null, category, line.defaultLimit())) != null) {
                throw new InvalidBudgetException(
                        "Duplicate category '%s' in template payload.".formatted(category));
            }
        }
        return List.copyOf(seen.values());
    }

    // ------------------------------------------------------------------ helpers

    private static BudgetLine detachedLine(String category, BigDecimal limitAmount) {
        return new BudgetLine(null, null, category, limitAmount, null, null);
    }

    /** Case-insensitive identity of a category — the same rule REQ-5.2 applies. */
    private static String key(String category) {
        return category.toLowerCase(Locale.ROOT);
    }

    private static String requireCategory(String categoryName) {
        if (categoryName == null || categoryName.isBlank()) {
            throw new InvalidBudgetException("Every template line requires a non-blank category.");
        }
        var clean = categoryName.strip();
        if (clean.length() > MAX_CATEGORY_LENGTH) {
            throw new InvalidBudgetException(
                    "Category '%s' exceeds the %d character limit.".formatted(clean, MAX_CATEGORY_LENGTH));
        }
        return clean;
    }

    private static void requireUser(UUID userId) {
        if (userId == null) {
            throw new InvalidBudgetException("userId is required.");
        }
    }
}
