package dev.katran.pet.quicktemplate;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateQuickTemplateRequest(
		@Size(min = 1, max = 64) String name,
		Long categoryId,
		@Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount,
		Integer sortOrder) {

	public UpdateQuickTemplateRequest {
		name = name == null ? null : name.strip();
	}

}
