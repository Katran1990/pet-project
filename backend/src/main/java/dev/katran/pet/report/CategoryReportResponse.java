package dev.katran.pet.report;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record CategoryReportResponse(
		YearMonth month,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
		List<CategoryReportRow> rows) {

	// Every SQL row carries the same month total; a month without rows sums to zero.
	private static final BigDecimal ZERO_AMOUNT = new BigDecimal("0.00");

	public static CategoryReportResponse of(YearMonth month, List<CategoryReportLine> lines) {
		BigDecimal total = lines.isEmpty() ? ZERO_AMOUNT : lines.getFirst().totalAmount();
		return new CategoryReportResponse(month, total, lines.stream().map(CategoryReportRow::from).toList());
	}

}
