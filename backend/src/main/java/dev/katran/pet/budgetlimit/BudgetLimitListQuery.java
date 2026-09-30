package dev.katran.pet.budgetlimit;

import java.time.YearMonth;

import jakarta.validation.constraints.NotNull;

public record BudgetLimitListQuery(@NotNull YearMonth month) {
}
