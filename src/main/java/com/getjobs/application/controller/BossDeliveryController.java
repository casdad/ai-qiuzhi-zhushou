package com.getjobs.application.controller;

import com.getjobs.application.service.BossDeliveryService;
import com.getjobs.application.service.BossDeliveryService.DeliveryDraft;
import com.getjobs.worker.service.BossJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/boss/deliveries")
@RequiredArgsConstructor
public class BossDeliveryController {
    private final BossDeliveryService deliveryService;
    private final BossJobService jobService;

    public record DraftView(String id, String jobName, String companyName, String jobUrl,
                            String message, boolean sendImgResume, String status, String reason,
                            String createdAt, String updatedAt) {
        static DraftView from(DeliveryDraft draft) {
            return new DraftView(draft.id(), draft.job().getJobName(), draft.job().getCompanyName(),
                    draft.job().getHref(), draft.message(), draft.sendImgResume(), draft.status(),
                    draft.reason(), draft.createdAt(), draft.updatedAt());
        }
    }

    public record ConfirmationRequest(String message) {
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("success", true, "items", deliveryService.list().stream().map(DraftView::from).toList());
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable String id,
                                                      @RequestBody ConfirmationRequest request) {
        try {
            Map<String, Object> response = jobService.confirmDelivery(id, request == null ? null : request.message());
            return Boolean.TRUE.equals(response.get("success"))
                    ? ResponseEntity.ok(response) : ResponseEntity.status(409).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "status", "invalid_message",
                    "message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(@PathVariable String id) {
        if (deliveryService.dismiss(id)) {
            return ResponseEntity.ok(Map.of("success", true, "status", "DISMISSED", "message", "草稿已忽略"));
        }
        return ResponseEntity.status(409).body(Map.of("success", false, "status", "already_processed",
                "message", "草稿不存在或已处理，不能忽略"));
    }
}
