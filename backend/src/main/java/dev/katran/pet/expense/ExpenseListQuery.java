package dev.katran.pet.expense;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.format.annotation.DateTimeFormat;

public record ExpenseListQuery(
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
		@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
		List<Long> categoryIds,
		@Min(0) Integer page,
		@Min(1) @Max(200) Integer size) {

	public ExpenseListQuery {
		categoryIds = categoryIds == null ? List.of() : categoryIds;
		page = page == null ? 0 : page;
		size = size == null ? 50 : size;
	}

}
