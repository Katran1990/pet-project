package dev.katran.pet.expense;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

import dev.katran.pet.category.CategorySummary;

public record ExpenseResponse(
		Long id,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
		String currency,
		LocalDate spentOn,
		String note,
		Instant createdAt,
		CategorySummary category) {

	public static ExpenseResponse from(Expense expense) {
		return new ExpenseResponse(
				expense.getId(),
				expense.getAmount().setScale(2, RoundingMode.UNNECESSARY),
				expense.getCurrency(),
				expense.getSpentOn(),
				expense.getNote(),
				expense.getCreatedAt(),
				CategorySummary.from(expense.getCategory()));
	}

}
