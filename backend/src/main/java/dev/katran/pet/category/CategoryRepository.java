package dev.katran.pet.category;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	List<Category> findAllByArchivedFalseOrderByIdAsc();

	List<Category> findAllByOrderByIdAsc();

}
