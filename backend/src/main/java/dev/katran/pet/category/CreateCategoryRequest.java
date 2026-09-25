package dev.katran.pet.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
		@NotBlank @Size(max = 64) String name,
		@Size(max = 32) String icon) {

	public CreateCategoryRequest {
		name = name == null ? null : name.strip();
		icon = icon == null || icon.isEmpty() ? null : icon;
	}

}
