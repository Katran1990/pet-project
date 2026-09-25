package dev.katran.pet.category;

import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
		@Size(min = 1, max = 64) String name,
		@Size(max = 32) String icon,
		Boolean archived) {

	public UpdateCategoryRequest {
		name = name == null ? null : name.strip();
	}

}
