package dev.katran.pet.quicktemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.katran.pet.category.ActiveCategoryLookup;
import dev.katran.pet.category.Category;
import dev.katran.pet.expense.Expense;
import dev.katran.pet.expense.ExpenseRepository;
import dev.katran.pet.expense.ExpenseResponse;
import dev.katran.pet.web.NotFoundException;

@RestController
@RequestMapping("/api/quick-templates")
public class QuickTemplateController {

	private final QuickTemplateRepository templates;
	private final ActiveCategoryLookup activeCategories;
	private final ExpenseRepository expenses;
	private final Clock clock;

	public QuickTemplateController(QuickTemplateRepository templates, ActiveCategoryLookup activeCategories,
			ExpenseRepository expenses, Clock clock) {
		this.templates = templates;
		this.activeCategories = activeCategories;
		this.expenses = expenses;
		this.clock = clock;
	}

	@PostMapping
	public ResponseEntity<QuickTemplateResponse> create(@Valid @RequestBody CreateQuickTemplateRequest request) {
		Category category = activeCategories.getActive(request.categoryId());
		QuickTemplate saved = templates.saveAndFlush(
				new QuickTemplate(request.name(), category, request.amount(), request.sortOrder()));
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}").buildAndExpand(saved.getId()).toUri();
		return ResponseEntity.created(location).body(QuickTemplateResponse.from(saved));
	}

	@GetMapping
	public List<QuickTemplateResponse> list() {
		return templates.findAllByOrderBySortOrderAscIdAsc().stream().map(QuickTemplateResponse::from).toList();
	}

	@GetMapping("/{id}")
	public QuickTemplateResponse get(@PathVariable Long id) {
		return templates.findWithCategoryById(id).map(QuickTemplateResponse::from)
				.orElseThrow(() -> new NotFoundException("Quick template", id));
	}

	@PatchMapping("/{id}")
	public QuickTemplateResponse update(@PathVariable Long id, @Valid @RequestBody UpdateQuickTemplateRequest request) {
		QuickTemplate template = templates.findWithCategoryById(id)
				.orElseThrow(() -> new NotFoundException("Quick template", id));
		// Every check that can throw runs before the first setter (OSIV, expense-crud section 7).
		if (request.categoryId() != null && !request.categoryId().equals(template.getCategory().getId())) {
			template.setCategory(activeCategories.getActive(request.categoryId()));
		}
		if (request.name() != null) {
			template.setName(request.name());
		}
		if (request.amount() != null) {
			template.setAmount(request.amount());
		}
		if (request.sortOrder() != null) {
			template.setSortOrder(request.sortOrder());
		}
		templates.saveAndFlush(template);
		return QuickTemplateResponse.from(template);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		QuickTemplate template = templates.findById(id).orElseThrow(() -> new NotFoundException("Quick template", id));
		templates.delete(template);
	}

	@PostMapping("/{id}/apply")
	public ResponseEntity<ExpenseResponse> apply(@PathVariable Long id,
			@Valid @RequestBody(required = false) ApplyQuickTemplateRequest request) {
		QuickTemplate template = templates.findWithCategoryById(id)
				.orElseThrow(() -> new NotFoundException("Quick template", id));
		Category category = activeCategories.requireNotArchived(template.getCategory());
		BigDecimal amount = request != null && request.amount() != null ? request.amount() : template.getAmount();
		String note = request != null ? request.note() : null;
		Expense saved = expenses.saveAndFlush(new Expense(category, amount, LocalDate.now(clock), note));
		URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/expenses/{id}").buildAndExpand(saved.getId()).toUri();
		return ResponseEntity.created(location).body(ExpenseResponse.from(saved));
	}

}
