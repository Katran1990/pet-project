package dev.katran.pet.quicktemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fasterxml.jackson.annotation.JsonFormat;

public record QuickTemplateResponse(
		Long id,
		String name,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
		int sortOrder,
		QuickTemplateCategory category) {

	public static QuickTemplateResponse from(QuickTemplate template) {
		return new QuickTemplateResponse(template.getId(), template.getName(),
				template.getAmount().setScale(2, RoundingMode.UNNECESSARY), template.getSortOrder(),
				QuickTemplateCategory.from(template.getCategory()));
	}

}
