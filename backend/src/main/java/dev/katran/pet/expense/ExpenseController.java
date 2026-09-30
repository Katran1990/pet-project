package dev.katran.pet.expense;

import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.katran.pet.category.Category;
import dev.katran.pet.category.CategoryRepository;
import dev.katran.pet.web.ConflictException;
import dev.katran.pet.web.InvalidFieldException;
import dev.katran.pet.web.NotFoundException;

@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

	private final ExpenseRepository expenses;
	private final CategoryRepository categories;
	private final Clock clock;

	public ExpenseController(ExpenseRepository expenses, CategoryRepository categories, Clock clock) {
		this.expenses = expenses;
		this.categories = categories;
		this.clock = clock;
	}

	@PostMapping
	public ResponseEntity<ExpenseResponse> create(@Valid @RequestBody ExpenseRequest request) {
		Category category = activeCategory(request.categoryId());
		Expense saved = expenses.saveAndFlush(
				new Expense(category, request.amount(), request.spentOn(), request.note()));
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}").buildAndExpand(saved.getId()).toUri();
		return ResponseEntity.created(location).body(ExpenseResponse.from(saved));
	}

	@GetMapping
	public ExpenseListResponse list(@Valid @ModelAttribute ExpenseListQuery query) {
		if (query.categoryIds().stream().anyMatch(Objects::isNull)) {
			// not contains(null): List.of().contains(null) throws NPE
			throw new InvalidFieldException("categoryIds", "invalid value");
		}
		YearMonth currentMonth = YearMonth.now(clock);
		LocalDate from = query.from() != null ? query.from() : currentMonth.atDay(1);
		LocalDate to = query.to() != null ? query.to() : currentMonth.atEndOfMonth();
		if (from.isAfter(to)) {
			throw new InvalidFieldException("from", "must not be after to (" + from + " > " + to + ")");
		}
		Set<Long> categoryIds = new LinkedHashSet<>(query.categoryIds());
		requireExistingCategories(categoryIds);
		boolean allCategories = categoryIds.isEmpty();

		ExpenseTotals totals = expenses.totals(from, to, allCategories, categoryIds);
		long offset = (long) query.page() * query.size();
		List<ExpenseResponse> items = offset >= totals.count()
				? List.of()
				: expenses.findPage(from, to, allCategories, categoryIds, PageRequest.of(query.page(), query.size()))
						.stream().map(ExpenseResponse::from).toList();
		return new ExpenseListResponse(items, query.page(), query.size(), totals.count(),
				totals.amount().setScale(2, RoundingMode.UNNECESSARY));
	}

	@GetMapping("/{id}")
	public ExpenseResponse get(@PathVariable Long id) {
		return expenses.findWithCategoryById(id)
				.map(ExpenseResponse::from)
				.orElseThrow(() -> new NotFoundException("Expense", id));
	}

	@PutMapping("/{id}")
	public ExpenseResponse update(@PathVariable Long id, @Valid @RequestBody ExpenseRequest request) {
		Expense expense = expenses.findWithCategoryById(id)
				.orElseThrow(() -> new NotFoundException("Expense", id));
		if (!request.categoryId().equals(expense.getCategory().getId())) {
			expense.setCategory(activeCategory(request.categoryId()));
		}
		expense.setAmount(request.amount());
		expense.setSpentOn(request.spentOn());
		expense.setNote(request.note());
		expenses.saveAndFlush(expense);
		return ExpenseResponse.from(expense);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		Expense expense = expenses.findById(id).orElseThrow(() -> new NotFoundException("Expense", id));
		expenses.delete(expense);
	}

	private Category activeCategory(Long categoryId) {
		Category category = categories.findById(categoryId)
				.orElseThrow(() -> new InvalidFieldException("categoryId", "Category not found"));
		if (category.isArchived()) {
			throw new ConflictException("Category is archived");
		}
		return category;
	}

	// Archived categories are known ids too: reading history of an archived category is legitimate.
	private void requireExistingCategories(Set<Long> ids) {
		if (ids.isEmpty()) {
			return;
		}
		Set<Long> found = categories.findAllById(ids).stream().map(Category::getId).collect(Collectors.toSet());
		List<Long> missing = ids.stream().filter(id -> !found.contains(id)).toList();
		if (!missing.isEmpty()) {
			String joined = missing.stream().map(String::valueOf).collect(Collectors.joining(", "));
			throw new InvalidFieldException("categoryIds", "Category not found: " + joined);
		}
	}

}
