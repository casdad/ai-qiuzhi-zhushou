package com.getjobs.application.controller;

import com.getjobs.worker.platform.PlatformDeliveryEngine;
import com.getjobs.worker.platform.PlatformDeliveryStore;
import com.getjobs.worker.platform.RecruitmentPlatform;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/{platform:liepin|51job|zhilian}")
public class PlatformDeliveryController {
    private final PlatformDeliveryEngine engine;
    private final PlatformDeliveryStore store;
    public record Settings(String mode) { }
    public record Review(boolean acknowledged) { }

    @GetMapping("/delivery-settings")
    public Map<String, Object> settings(@PathVariable String platform) {
        return Map.of("success", true, "mode", store.mode(RecruitmentPlatform.from(platform)));
    }

    @PutMapping("/delivery-settings")
    public Map<String, Object> saveSettings(@PathVariable String platform, @RequestBody Settings settings) {
        store.setMode(RecruitmentPlatform.from(platform), settings.mode());
        return Map.of("success", true, "mode", settings.mode());
    }

    @GetMapping("/deliveries")
    public Map<String, Object> deliveries(@PathVariable String platform) {
        return Map.of("success", true, "items", store.list(RecruitmentPlatform.from(platform)));
    }

    @PostMapping("/deliveries/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String platform, @PathVariable String id) {
        return response(engine.confirm(RecruitmentPlatform.from(platform), id));
    }

    @PostMapping("/deliveries/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@PathVariable String platform, @PathVariable String id) {
        boolean success = store.dismiss(RecruitmentPlatform.from(platform), id);
        return response(PlatformDeliveryEngine.result(success, success ? "DISMISSED" : "conflict",
                success ? "已忽略，不执行投递" : "草稿已处理，不能忽略"));
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable String platform, @RequestBody Review review) {
        return response(engine.resume(RecruitmentPlatform.from(platform), review.acknowledged()));
    }

    @PostMapping("/session/open")
    public ResponseEntity<Map<String, Object>> open(@PathVariable String platform) {
        return response(engine.openLogin(RecruitmentPlatform.from(platform)));
    }

    private ResponseEntity<Map<String, Object>> response(Map<String, Object> result) {
        return ResponseEntity.status(Boolean.TRUE.equals(result.get("success")) ? 200 : 409).body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
    }
}
