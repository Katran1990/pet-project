package dev.katran.pet.report;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

	private final CategoryReportRepository categoryReports;

	public ReportController(CategoryReportRepository categoryReports) {
		this.categoryReports = categoryReports;
	}

	@GetMapping("/by-category")
	public CategoryReportResponse byCategory(@Valid @ModelAttribute CategoryReportQuery query) {
		return CategoryReportResponse.of(query.month(), categoryReports.findByMonth(query.month()));
	}

}
