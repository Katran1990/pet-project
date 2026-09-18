package dev.katran.pet.greeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGreetingRequest(
		@NotBlank @Size(max = 200) String message) {
}
