package dev.katran.pet.category;

import java.time.Instant;

public record CategoryResponse(Long id, String name, String icon, boolean archived, Instant createdAt) {

	public static CategoryResponse from(Category category) {
		return new CategoryResponse(
				category.getId(),
				category.getName(),
				category.getIcon(),
				category.isArchived(),
				category.getCreatedAt());
	}

}
