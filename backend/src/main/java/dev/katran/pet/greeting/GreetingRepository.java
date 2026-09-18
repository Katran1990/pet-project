package dev.katran.pet.greeting;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GreetingRepository extends JpaRepository<Greeting, Long> {

	Optional<Greeting> findFirstByOrderByIdAsc();

}
