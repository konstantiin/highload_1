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
import java.util.Map;

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
    public Map<String, String> login(@Valid @RequestBody LoginRequest request) {
        return Map.of("accessToken", authService.login(request.username(), request.password()));
    }

    @GetMapping("/me")
    public Object me(@RequestHeader("Authorization") String authorization) {
        return authService.authenticate(bearerToken(authorization));
    }

    @GetMapping("/users")
    public List<User> users(@RequestHeader("Authorization") String authorization) {
        return authService.listUsers(bearerToken(authorization));
    }

    @GetMapping("/balances/{userId}")
    public Balance balance(@RequestHeader("Authorization") String authorization, @PathVariable String userId) {
        return authService.getBalance(bearerToken(authorization), userId);
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Authorization header must use Bearer token");
        }
        return authorization.substring("Bearer ".length());
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

}
