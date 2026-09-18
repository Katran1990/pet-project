package dev.katran.pet.greeting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class GreetingController {

	private final GreetingRepository greetings;

	public GreetingController(GreetingRepository greetings) {
		this.greetings = greetings;
	}

	@GetMapping("/greeting")
	public GreetingResponse greeting() {
		return greetings.findFirstByOrderByIdAsc()
				.map(greeting -> new GreetingResponse(greeting.getMessage()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No greeting found"));
	}

}
