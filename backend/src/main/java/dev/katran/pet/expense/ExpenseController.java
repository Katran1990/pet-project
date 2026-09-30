package dev.katran.pet.expense;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

	public ExpenseController(ExpenseRepository expenses, CategoryRepository categories) {
		this.expenses = expenses;
		this.categories = categories;
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

}
