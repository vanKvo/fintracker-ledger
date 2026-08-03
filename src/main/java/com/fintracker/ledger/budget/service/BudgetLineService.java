package com.fintracker.ledger.budget.service;

import com.fintracker.ledger.budget.dto.BudgetLineInput;
import com.fintracker.ledger.budget.exception.DuplicateCategoryException;
import com.fintracker.ledger.budget.exception.HistoricalBudgetException;
import com.fintracker.ledger.budget.exception.InvalidBudgetException;
import com.fintracker.ledger.budget.exception.LineItemLimitExceededException;
import com.fintracker.ledger.budget.model.BudgetLine;
import com.fintracker.ledger.shared.exception.ResourceNotFoundException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * REQ-5.2: granular add / update-limit / remove operations against a single budget's line items,
 * as opposed to {@link BudgetService#upsertBudget} which replaces a budget's entire line list.
 */
public interface BudgetLineService {

    /**
     * Adds a single line item to an ACTIVE budget and computes its initial spentAmount.
     *
     * @param userId    Unique identifier of the requesting user.
     * @param budgetId  Unique identifier of the target budget.
     * @param lineInput DTO containing the category name and limit amount.
     * @return BudgetLine The persisted Java record representation of the added line item.
     *
     * @throws ResourceNotFoundException      If the budgetId does not exist or belong to the user.
     * @throws DuplicateCategoryException     If the category already exists in the budget.
     * @throws LineItemLimitExceededException If total lines exceed 50.
     * @throws InvalidBudgetException         If limitAmount violates range constraints [0.00, 999999999.99].
     * @throws HistoricalBudgetException      If budget status is CLOSED.
     */
    BudgetLine addLineItem(
            UUID userId,
            UUID budgetId,
            BudgetLineInput lineInput
    ) throws ResourceNotFoundException, DuplicateCategoryException, LineItemLimitExceededException,
            InvalidBudgetException, HistoricalBudgetException;

    /**
     * Updates the target limit amount of an existing line item on an ACTIVE budget.
     *
     * @param userId         Unique identifier of the requesting user.
     * @param budgetId       Unique identifier of the target budget.
     * @param lineId         Unique identifier of the line item to modify.
     * @param newLimitAmount The updated target limit amount.
     * @return BudgetLine    The updated line item domain entity.
     *
     * @throws ResourceNotFoundException If budgetId or lineId does not exist or belong to user.
     * @throws InvalidBudgetException    If newLimitAmount violates range constraints.
     * @throws HistoricalBudgetException If budget status is CLOSED.
     */
    BudgetLine updateLineItemLimit(
            UUID userId,
            UUID budgetId,
            UUID lineId,
            BigDecimal newLimitAmount
    ) throws ResourceNotFoundException, InvalidBudgetException, HistoricalBudgetException;

    /**
     * Removes a line item from an ACTIVE budget. Does not alter or delete the underlying
     * transactions that were counted against the removed category.
     *
     * @param userId   Unique identifier of the requesting user.
     * @param budgetId Unique identifier of the target budget.
     * @param lineId   Unique identifier of the line item to delete.
     *
     * @throws ResourceNotFoundException If budgetId or lineId does not exist.
     * @throws HistoricalBudgetException If budget status is CLOSED.
     */
    void removeLineItem(
            UUID userId,
            UUID budgetId,
            UUID lineId
    ) throws ResourceNotFoundException, HistoricalBudgetException;
}
