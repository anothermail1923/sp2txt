package com.example.speech.controller;

import com.example.speech.dto.StatusResponse;
import com.example.speech.service.StatusService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Health and status endpoints for monitoring and client discovery. */
@RestController
@RequestMapping("/api")
public class StatusController {

    private final StatusService statusService;

    public StatusController(StatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return statusService.getStatus();
    }
}

