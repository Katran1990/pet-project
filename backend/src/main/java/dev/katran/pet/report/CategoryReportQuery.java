package dev.katran.pet.report;

import java.time.YearMonth;

import jakarta.validation.constraints.NotNull;

public record CategoryReportQuery(@NotNull YearMonth month) {
}
