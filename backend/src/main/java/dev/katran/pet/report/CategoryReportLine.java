package dev.katran.pet.report;

import java.math.BigDecimal;

// One result row of db/report/by-category.sql; columns map by name (category_id -> categoryId).
public record CategoryReportLine(
		Long categoryId, String categoryName, String categoryIcon,
		BigDecimal amount, BigDecimal share, BigDecimal limitAmount, BigDecimal remaining,
		BigDecimal totalAmount) {
}
