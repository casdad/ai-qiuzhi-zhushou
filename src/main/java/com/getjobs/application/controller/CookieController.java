package com.getjobs.application.controller;

import com.getjobs.application.entity.CookieEntity;
import com.getjobs.application.service.CookieService;
import com.getjobs.worker.manager.PlaywrightManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 统一的 Cookie 读/写控制器
 * 提供：
 * - GET /api/cookie?platform=... 读取指定平台的 Cookie 记录
 * - POST /api/cookie/save?platform=...&remark=... 保存当前上下文 Cookie 到数据库
 */
@Slf4j
@RestController
@RequestMapping("/api/cookie")
@RequiredArgsConstructor
public class CookieController {

    private final CookieService cookieService;
    private final PlaywrightManager playwrightManager;

    private static final Set<String> ALLOWED_PLATFORMS = Set.of("boss", "liepin", "51job", "zhilian");

    @GetMapping
    public ResponseEntity<Map<String, Object>> getCookie(@RequestParam("platform") String platform) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!ALLOWED_PLATFORMS.contains(platform)) {
                response.put("success", false);
                response.put("message", "不支持的平台: " + platform);
                return ResponseEntity.badRequest().body(response);
            }

            CookieEntity cookie = cookieService.getCookieByPlatform(platform);
            Map<String, Object> data = new HashMap<>();
            if (cookie != null) {
                data.put("id", cookie.getId());
                data.put("platform", cookie.getPlatform());
                data.put("hasCookie", cookie.getCookieValue() != null && !cookie.getCookieValue().isBlank());
                data.put("remark", cookie.getRemark());
                data.put("created_at", cookie.getCreatedAt());
                data.put("updated_at", cookie.getUpdatedAt());
            } else {
                data.put("platform", platform);
                data.put("hasCookie", false);
                data.put("message", "未找到Cookie记录");
            }
            response.put("success", true);
            response.put("data", data);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("读取Cookie记录失败", e);
            response.put("success", false);
            response.put("message", "读取Cookie记录失败");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> saveCookie(
            @RequestParam("platform") String platform,
            @RequestParam(value = "remark", defaultValue = "manual save") String remark
    ) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!ALLOWED_PLATFORMS.contains(platform)) {
                response.put("success", false);
                response.put("message", "不支持的平台: " + platform);
                return ResponseEntity.badRequest().body(response);
            }

            response.put("success", false);
            response.put("message", "每次必须重新登录，登录凭证不再保存或复用");
            return ResponseEntity.status(410).body(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            log.error("保存Cookie失败", e);
            response.put("success", false);
            response.put("message", "保存Cookie失败");
            return ResponseEntity.internalServerError().body(response);
        }
    }

    @PostMapping("/import-legacy-boss")
    public ResponseEntity<Map<String, Object>> importLegacyBossCookie() {
        if (playwrightManager.isPlaywrightBusy()) {
            return ResponseEntity.status(409).body(Map.of("success", false,
                    "message", "请先停止浏览器任务，再执行一次性旧会话导入"));
        }
        try {
            boolean imported = playwrightManager.importLegacyBossCookies();
            return ResponseEntity.ok(Map.of("success", imported,
                    "message", imported ? "旧会话已导入，请在官方页面刷新并核对登录状态"
                            : "没有可导入的旧会话，请通过官方页面登录"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.warn("Legacy session import failed: {}", e.getClass().getSimpleName());
            return ResponseEntity.internalServerError().body(Map.of("success", false,
                    "message", "旧会话导入失败，请通过官方页面登录"));
        }
    }
}
