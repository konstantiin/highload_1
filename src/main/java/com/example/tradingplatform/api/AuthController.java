package com.example.tradingplatform.api;

import com.example.tradingplatform.auth.AuthService;
import com.example.tradingplatform.auth.Balance;
import com.example.tradingplatform.auth.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public User register(@Valid @RequestBody RegisterRequest request) {
        return authService.registerTrader(request.username(), request.password());
    }

    @PostMapping("/login")
    public User login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @GetMapping("/me")
    public Object me(@RequestHeader("X-Username") String username, @RequestHeader("X-Password") String password) {
        return authService.authenticate(username, password);
    }

    @GetMapping("/users")
    public List<User> users(@RequestHeader("X-Username") String username, @RequestHeader("X-Password") String password) {
        return authService.listUsers(username, password);
    }

    @GetMapping("/balances/{userId}")
    public Balance balance(@RequestHeader("X-Username") String username, @RequestHeader("X-Password") String password, @PathVariable String userId) {
        return authService.getBalance(username, password, userId);
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

}
