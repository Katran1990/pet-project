package dev.katran.pet.report;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

import dev.katran.pet.category.CategorySummary;

public record CategoryReportRow(
		CategorySummary category,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal share,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal limit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal remaining) {

	static CategoryReportRow from(CategoryReportLine line) {
		return new CategoryReportRow(
				new CategorySummary(line.categoryId(), line.categoryName(), line.categoryIcon()),
				line.amount(), line.share(), line.limitAmount(), line.remaining());
	}

}
