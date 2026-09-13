package com.nongxin.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 图片（视觉）能力判定。
 *
 * <p>为什么需要它：模型不支持图片时，请求要么被供应商拒绝，要么更糟——模型**直接忽略图片**却照常回答，
 * 用户会以为"它看过照片了"。所以：
 * <ol>
 *   <li>默认按模型名单判断（{@code auto}）；</li>
 *   <li>用户可以在「模型设置」里强制开启或关闭（{@code on}/{@code off}）——名单不可能覆盖所有新模型；</li>
 *   <li>不在名单里就被明确拒绝，并提示怎么改，绝不会把图片发给一个可能不看图的模型冒充视觉请求。</li>
 * </ol>
 * 名单里的 DeepSeek 型号是 2026-09-11 用真实 Key 实测确认能接受图片输入的（发送 1×1 图会被判为无效图，
 * 正常尺寸照片返回 200），不是照抄文档。
 */
@Service
public class VisionSupport {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_ON = "on";
    public static final String MODE_OFF = "off";

    /** 已知支持图片输入的模型名片段（小写匹配）。 */
    private static final List<String> KNOWN_VISION_MODELS = List.of(
            // 实测确认（本机 2026-09-11 用真实供应商返回 200）
            "deepseek-v4", "deepseek-chat", "deepseek-flash", "deepseek-vl",
            // OpenAI 系
            "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4",
            // Anthropic / Google
            "claude-", "gemini-",
            // 国内开源视觉模型
            "glm-4v", "glm-4.5v", "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl",
            "internvl", "llava", "minicpm-v", "yi-vl", "step-1v", "moonshot-v1-vision", "kimi-latest");

    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) return MODE_AUTO;
        String value = mode.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case MODE_ON, "true", "yes" -> MODE_ON;
            case MODE_OFF, "false", "no" -> MODE_OFF;
            default -> MODE_AUTO;
        };
    }

    public boolean isKnownVisionModel(String model) {
        if (model == null || model.isBlank()) return false;
        String value = model.toLowerCase(Locale.ROOT);
        return KNOWN_VISION_MODELS.stream().anyMatch(value::contains);
    }

    /** 最终是否按"能看图"处理：用户显式设置优先，否则看名单。 */
    public boolean effective(String mode, String model) {
        String normalized = normalizeMode(mode);
        if (MODE_ON.equals(normalized)) return true;
        if (MODE_OFF.equals(normalized)) return false;
        return isKnownVisionModel(model);
    }

    /** 供前端展示：不需要用户去猜自己的模型支不支持。 */
    public String modeOf(String mode) { return normalizeMode(mode); }

    public List<String> knownModels() { return KNOWN_VISION_MODELS; }
}
