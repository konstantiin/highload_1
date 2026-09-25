package com.example.tradingplatform.api;

import com.example.tradingplatform.auth.AuthService;
import com.example.tradingplatform.logging.AuditEvent;
import com.example.tradingplatform.logging.AuditLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/logs")
public class AuditController {
    private final AuthService authService;
    private final AuditLogService auditLogService;

    public AuditController(AuthService authService, AuditLogService auditLogService) {
        this.authService = authService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public List<AuditEvent> query(
            @RequestHeader("X-Username") String username,
            @RequestHeader("X-Password") String password,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String tags
    ) {
        authService.requireRole(authService.authenticate(username, password), AuthService.ROLE_AUDITOR);
        Set<String> requiredTags = tags == null || tags.isBlank()
                ? Set.of()
                : Arrays.stream(tags.split(",")).map(String::trim).filter(tag -> !tag.isEmpty()).collect(Collectors.toSet());
        return auditLogService.query(from, to, requiredTags);
    }
}
