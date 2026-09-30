package dev.katran.pet.expense;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ExpenseRequest(
		@NotNull @Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount,
		@NotNull Long categoryId,
		@NotNull @PastOrPresent LocalDate spentOn,
		@Size(max = 255) String note) {

	public ExpenseRequest {
		note = note == null || note.isEmpty() ? null : note;
	}

}
