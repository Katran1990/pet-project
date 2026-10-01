package dev.katran.pet.quicktemplate;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateQuickTemplateRequest(
		@NotBlank @Size(max = 64) String name,
		@NotNull Long categoryId,
		@NotNull @Positive @Digits(integer = 10, fraction = 2) @DecimalMax("9999999999.99") BigDecimal amount,
		@NotNull Integer sortOrder) {

	public CreateQuickTemplateRequest {
		name = name == null ? null : name.strip();
	}

}
