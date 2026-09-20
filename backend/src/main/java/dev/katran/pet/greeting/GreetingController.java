package dev.katran.pet.greeting;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

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
				.map(greeting -> new GreetingResponse(greeting.getId(), greeting.getMessage()))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No greeting found"));
	}

	@PostMapping("/greetings")
	public ResponseEntity<GreetingResponse> create(@Valid @RequestBody CreateGreetingRequest request) {
		Greeting saved = greetings.save(new Greeting(request.message()));
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}").buildAndExpand(saved.getId()).toUri();
		return ResponseEntity.created(location)
				.body(new GreetingResponse(saved.getId(), saved.getMessage()));
	}

}
