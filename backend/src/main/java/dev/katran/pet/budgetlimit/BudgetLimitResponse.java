package dev.katran.pet.budgetlimit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;

import com.fasterxml.jackson.annotation.JsonFormat;

import dev.katran.pet.category.CategorySummary;

public record BudgetLimitResponse(
		Long id,
		YearMonth month,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
		CategorySummary category) {

	public static BudgetLimitResponse from(BudgetLimit limit) {
		return new BudgetLimitResponse(limit.getId(), limit.getMonth(),
				limit.getAmount().setScale(2, RoundingMode.UNNECESSARY), CategorySummary.from(limit.getCategory()));
	}

}
