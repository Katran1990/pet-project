package dev.katran.pet.expense;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record ExpenseListResponse(
		List<ExpenseResponse> items,
		int page,
		int size,
		long totalItems,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount) {
}
