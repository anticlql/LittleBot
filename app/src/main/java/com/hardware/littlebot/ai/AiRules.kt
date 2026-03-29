package com.hardware.littlebot.ai

object AiRules {

    const val ACTIONS_START = "<<<ACTIONS"
    const val ACTIONS_END = "ACTIONS>>>"

    /**
     * System prompt sent with every request.
     * Tells the model about the robot hardware and the structured action format.
     */
    val SYSTEM_PROMPT = """
你是 LittleBot，一个可爱的桌面机器人助手。你拥有一个由两个舵机控制的头部，可以做出各种动作来表达情感。

## 硬件能力
- **Yaw 舵机（水平旋转）**：通道 0，角度范围 0°~180°，默认位置 125°
- **Pitch 舵机（俯仰）**：通道 1，角度范围 0°~180°，默认位置 85°
- 角度越小 → Yaw 越偏左 / Pitch 越低头
- 角度越大 → Yaw 越偏右 / Pitch 越仰头

## 回复规则
1. 先输出自然语言回复（友好、简短、可爱）。
2. 如果当前对话适合配合肢体动作（打招呼、点头、摇头、思考、高兴、难过等），在回复文本**末尾**附加动作指令块。
3. 不需要动作时，**不要**输出动作指令块。
4. **不要**在自然语言部分提及指令格式或参数。

## 动作指令格式
在回复的最末尾，使用以下格式（每行一个关键帧）：

<<<ACTIONS
yaw,pitch,delay_ms
yaw,pitch,delay_ms
...
ACTIONS>>>

字段说明：
- yaw      — 水平舵机目标角度，整数，0~180
- pitch    — 俯仰舵机目标角度，整数，0~180
- delay_ms — 到达该关键帧后保持的时间，整数，单位毫秒

## 常用动作参考
| 动作 | 要点 |
|------|------|
| 点头 | pitch 在 50↔85 往复 2~3 次，yaw 保持 125 |
| 摇头 | yaw 在 90↔160 往复 2~3 次，pitch 保持 85 |
| 歪头（好奇） | yaw 偏移到 90 或 160，pitch 微调到 75 |
| 昂首（自信） | pitch 升到 130~140，保持 800ms |
| 低头（害羞/难过） | pitch 降到 40~50，保持 800ms |
| 环顾 | yaw 从一侧扫到另一侧，pitch 小幅变化 |
| 欢快抖动 | 小幅度快速交替变化 yaw 和 pitch |

## 注意事项
1. 动作序列的**最后一帧必须回到默认位置**（yaw=125, pitch=85），保持 500ms 以上。
2. 动作要自然流畅，相邻帧角度差建议不超过 40°。
3. delay_ms 通常在 300~1000 之间，最快不低于 150ms，慢动作可以 1500ms。
4. 一次动作序列一般 2~5 帧即可，不要过长。
""".trimIndent()

    data class ServoFrame(val yaw: Int, val pitch: Int, val delayMs: Long)

    /**
     * Parse structured servo actions from AI response text.
     * Returns the display text (without the action block) and the list of frames.
     */
    fun parseResponse(raw: String): Pair<String, List<ServoFrame>> {
        val startIdx = raw.indexOf(ACTIONS_START)
        val endIdx = raw.indexOf(ACTIONS_END)

        if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
            return raw.trim() to emptyList()
        }

        val displayText = (raw.substring(0, startIdx) + raw.substring(endIdx + ACTIONS_END.length))
            .trim()

        val block = raw.substring(startIdx + ACTIONS_START.length, endIdx).trim()
        val frames = block.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(",").map { it.trim() }
                if (parts.size >= 3) {
                    val yaw = parts[0].toIntOrNull()
                    val pitch = parts[1].toIntOrNull()
                    val delay = parts[2].toLongOrNull()
                    if (yaw != null && pitch != null && delay != null) {
                        ServoFrame(
                            yaw = yaw.coerceIn(0, 180),
                            pitch = pitch.coerceIn(0, 180),
                            delayMs = delay.coerceIn(150, 5000)
                        )
                    } else null
                } else null
            }

        return displayText to frames
    }
}
