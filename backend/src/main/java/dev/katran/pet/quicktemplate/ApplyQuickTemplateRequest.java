package dev.katran.pet.quicktemplate;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ApplyQuickTemplateRequest(
		@Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount,
		@Size(max = 255) String note) {

	public ApplyQuickTemplateRequest {
		note = note == null || note.isEmpty() ? null : note;
	}

}
