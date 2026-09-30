package dev.katran.pet.budgetlimit;

import java.math.BigDecimal;
import java.time.YearMonth;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.fasterxml.jackson.annotation.JsonFormat;

public record BudgetLimitRequest(
		@NotNull Long categoryId,
		@NotNull @JsonFormat(pattern = "uuuu-MM") YearMonth month,
		@NotNull @Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount) {
}
