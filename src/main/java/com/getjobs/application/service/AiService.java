package com.getjobs.application.service;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.mapper.AiMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * AI 服务（Spring 管理）
 * 从数据库配置获取 BASE_URL、API_KEY、MODEL 并发起 AI 请求。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiService {
    private final ConfigService configService;
    private final AiMapper aiMapper;

    /**
     * 发送 AI 请求（非流式）并返回回复内容。
     * @param content 用户消息内容
     * @return AI 回复文本
     */
    public String sendRequest(String content) {
        // 读取并校验配置
        var cfg = configService.getAiConfigs();
        String baseUrl = cfg.get("BASE_URL");
        String apiKey = cfg.get("API_KEY");
        String model = cfg.get("MODEL");
        // 根据模型类型选择兼容的端点（部分“推理/Reasoning”模型需要使用 Responses API）
        String endpoint = isResponsesModel(model)
                ? buildResponsesEndpoint(baseUrl)
                : buildChatCompletionsEndpoint(baseUrl);

        int timeoutInSeconds = 60;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutInSeconds))
                .build();

        // 构建 JSON 请求体
        JSONObject requestData = new JSONObject();
        requestData.put("model", model);
        requestData.put("temperature", 0.5);
        if (endpoint.endsWith("/responses")) {
            // Responses API 采用 input 字段
            requestData.put("input", content);
            // 如需显式控制推理强度，可按需开启：
            // JSONObject reasoning = new JSONObject();
            // reasoning.put("effort", "medium");
            // requestData.put("reasoning", reasoning);
        } else {
            // Chat Completions API 使用 messages
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", content);
            messages.put(message);
            requestData.put("messages", messages);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                // 某些服务（例如 Azure OpenAI）需要 api-key 头，额外加一层兼容
                .header("api-key", apiKey)
                // 必须设置请求级超时：client.send 在对端不回包时会无限阻塞，
                // 而 AI 调用跑在唯一的 Playwright 线程上，卡住等于整个投递任务卡死。
                .timeout(Duration.ofSeconds(timeoutInSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestData.toString()))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject responseObject = new JSONObject(response.body());

                String requestId = responseObject.optString("id");
                long created = responseObject.optLong("created", 0);
                String usedModel = responseObject.optString("model");

                String responseContent = extractText(endpoint.endsWith("/responses"), responseObject);
                if (responseContent.isBlank()) {
                    // 绝不能把原始响应体当作回复返回：它会一路流到打招呼语里被投递给 HR。
                    log.error("AI响应解析失败，未取到文本内容");
                    throw new RuntimeException("AI响应解析失败，未能取到文本内容");
                }

                JSONObject usageObject = responseObject.optJSONObject("usage");
                int promptTokens = usageObject != null ? usageObject.optInt("prompt_tokens", -1) : -1;
                int completionTokens = usageObject != null ? usageObject.optInt("completion_tokens", -1) : -1;
                int totalTokens = usageObject != null ? usageObject.optInt("total_tokens", -1) : -1;

                LocalDateTime createdTime = created > 0
                        ? Instant.ofEpochSecond(created).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        : LocalDateTime.now();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                log.info("AI响应: id={}, time={}, model={}, promptTokens={}, completionTokens={}, totalTokens={}",
                        requestId, createdTime.format(formatter), usedModel, promptTokens, completionTokens, totalTokens);

                return responseContent;
            } else {
                // 更详细的错误日志，便于定位 400 问题
                log.error("AI请求失败: status={}", response.statusCode());
                // 针对 Responses-only 模型误用 Chat Completions 的常见错误做一次自动重试
                if (!endpoint.endsWith("/responses") && containsReasoningParamError(response.body())) {
                    String fallbackEndpoint = buildResponsesEndpoint(baseUrl);
                    log.warn("检测到 reasoning 参数错误，切换到 Responses API 重试一次");
                    return sendRequestViaResponses(content, apiKey, model, fallbackEndpoint);
                }
                throw new RuntimeException("AI请求失败，状态码: " + response.statusCode());
            }
        } catch (Exception e) {
            log.error("调用AI服务异常: type={}", e.getClass().getSimpleName());
            throw new RuntimeException("AI服务调用失败，请检查连接、配置及服务状态");
        }
    }

    /**
     * 从响应体中取出模型生成的文本，取不到时返回空串（由调用方决定是否报错）。
     *
     * Responses API 的文本不在顶层字段上，而是嵌在 output[] 数组里：
     * [{"type":"reasoning",...},{"type":"message","content":[{"type":"output_text","text":"..."}]}]。
     * 顶层 output_text 只存在于官方 SDK 的客户端封装中，原始 HTTP 响应体没有这个键，
     * 所以必须遍历 output 数组取 type=message 的项，并跳过 reasoning 项，否则会把
     * 推理过程或整段 JSON 当成回复。
     */
    private String extractText(boolean responsesApi, JSONObject responseObject) {
        if (!responsesApi) {
            return readChatCompletionsText(responseObject);
        }

        JSONArray output = responseObject.optJSONArray("output");
        if (output != null) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null || !"message".equals(item.optString("type"))) {
                    continue;
                }
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject part = content.optJSONObject(j);
                    if (part == null || !"output_text".equals(part.optString("type"))) continue;
                    String text = part.optString("text", "");
                    if (!text.isEmpty()) {
                        if (builder.length() > 0) builder.append('\n');
                        builder.append(text);
                    }
                }
            }
            if (builder.length() > 0) return builder.toString();
        }

        // 少数中转层会补上 SDK 风格的顶层 output_text，兼容一下
        String direct = responseObject.optString("output_text", "");
        if (!direct.isBlank()) return direct;

        // 部分代理会把 Responses 请求也回成 Chat Completions 结构
        return readChatCompletionsText(responseObject);
    }

    /** 读取 Chat Completions 结构的 choices[0].message.content，结构不匹配时返回空串。 */
    private String readChatCompletionsText(JSONObject responseObject) {
        JSONArray choices = responseObject.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) return "";
        JSONObject message = choice.optJSONObject("message");
        return message == null ? "" : message.optString("content", "");
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 根据配置构造 chat/completions 端点，避免重复拼接 /v1
     */
    private String buildChatCompletionsEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        // 如果 baseUrl 已经包含 /v1（常见配置为 https://api.openai.com/v1），则只拼接 /chat/completions
        if (normalized.endsWith("/v1") || normalized.contains("/v1/")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    /**
     * 构造 Responses API 端点
     */
    private String buildResponsesEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized.endsWith("/v1") || normalized.contains("/v1/")) {
            return normalized + "/responses";
        }
        return normalized + "/v1/responses";
    }

    /**
     * 粗略识别需要使用 Responses API 的模型（o-系列、4.1、reasoner 等）
     */
    private boolean isResponsesModel(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("o1") || m.contains("o3") || m.contains("o4")
                || m.contains("4.1") || m.contains("reasoner")
                || m.contains("4o-mini") || m.contains("gpt-4o-mini");
    }

    /**
     * 检查错误响应中是否包含 reasoning 相关参数错误（如 reasoning.summary unsupported_value）
     */
    private boolean containsReasoningParamError(String body) {
        if (body == null) return false;
        String s = body.toLowerCase();
        return (s.contains("reasoning") && s.contains("unsupported_value"))
                || s.contains("reasoning.summary");
    }

    /**
     * 使用 Responses API 发送一次请求（用于自动降级/重试）
     */
    private String sendRequestViaResponses(String content, String apiKey, String model, String endpoint) {
        int timeoutInSeconds = 60;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutInSeconds))
                .build();

        JSONObject requestData = new JSONObject();
        requestData.put("model", model);
        requestData.put("temperature", 0.5);
        requestData.put("input", content);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("api-key", apiKey)
                .timeout(Duration.ofSeconds(timeoutInSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestData.toString()))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject resp = new JSONObject(response.body());
                String outputText = extractText(true, resp);
                if (outputText.isBlank()) {
                    log.error("Responses API 响应解析失败");
                    throw new RuntimeException("AI响应解析失败，未能取到文本内容");
                }
                return outputText;
            }
            log.error("Responses API 调用失败: status={}", response.statusCode());
            throw new RuntimeException("AI请求失败，状态码: " + response.statusCode());
        } catch (Exception e) {
            log.error("Responses API 调用异常: type={}", e.getClass().getSimpleName());
            throw new RuntimeException("AI服务调用失败，请检查连接、配置及服务状态");
        }
    }

    // ================= 合并的 AI 配置管理方法 =================

    /**
     * 获取AI配置（获取最新一条，如果不存在则创建默认配置）
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfig() {
        var list = aiMapper.selectList(null);
        AiEntity aiEntity = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
        if (aiEntity == null) {
            aiEntity = createDefaultConfig();
        }
        return aiEntity;
    }

    /**
     * 获取所有AI配置
     */
    @Transactional(readOnly = true)
    public java.util.List<AiEntity> getAllAiConfigs() {
        return aiMapper.selectList(null);
    }

    /**
     * 根据ID获取AI配置
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfigById(Long id) {
        return aiMapper.selectById(id);
    }

    /**
     * 保存或更新AI配置（introduce/prompt）
     */
    @Transactional
    public AiEntity saveOrUpdateAiConfig(String introduce, String prompt) {
        var list = aiMapper.selectList(null);
        AiEntity aiEntity = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);

        if (aiEntity == null) {
            aiEntity = new AiEntity();
            aiEntity.setIntroduce(introduce);
            aiEntity.setPrompt(prompt);
            aiEntity.setCreatedAt(java.time.LocalDateTime.now());
            aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
            aiMapper.insert(aiEntity);
            log.info("创建新的AI配置，ID: {}", aiEntity.getId());
        } else {
            aiEntity.setIntroduce(introduce);
            aiEntity.setPrompt(prompt);
            aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
            aiMapper.updateById(aiEntity);
            log.info("更新AI配置，ID: {}", aiEntity.getId());
        }

        return aiEntity;
    }

    /**
     * 删除AI配置
     */
    @Transactional
    public boolean deleteAiConfig(Long id) {
        int result = aiMapper.deleteById(id);
        if (result > 0) {
            log.info("删除AI配置成功，ID: {}", id);
            return true;
        }
        return false;
    }

    /**
     * 创建默认配置
     */
    @Transactional
    protected AiEntity createDefaultConfig() {
        AiEntity aiEntity = new AiEntity();
        aiEntity.setIntroduce("请在此填写您的技能介绍");
        // 默认提示词必须是合法的 5 个 %s 模板：前端保存时会校验占位符数量，
        // 旧默认值「请在此填写AI提示词模板」没有任何占位符，会让配置页永远保存失败。
        aiEntity.setPrompt("请根据个人画像和岗位信息生成一段简洁、真诚的求职打招呼语。"
                + "个人画像：%s；搜索关键词：%s；岗位名称：%s；岗位描述：%s；默认招呼语：%s。\n"
                + "风格：专业、真诚、简洁\n"
                + "字数上限：60\n"
                + "规则：仅使用个人画像中的真实经历；突出与岗位最匹配的技术或项目；"
                + "不虚构学历、公司、论文、指标和工作经历；只输出最终打招呼语。");
        aiEntity.setCreatedAt(java.time.LocalDateTime.now());
        aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
        aiMapper.insert(aiEntity);
        log.info("创建默认AI配置，ID: {}", aiEntity.getId());
        return aiEntity;
    }
}
