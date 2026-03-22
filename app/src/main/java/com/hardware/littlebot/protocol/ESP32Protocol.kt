package com.hardware.littlebot.protocol

/**
 * Command protocol for communicating with the ESP32.
 *
 * All commands are plain-text strings terminated by '\n'.
 * The ESP32 firmware should parse these commands and act accordingly.
 *
 * ── Servo Commands ──────────────────────────────────────────────
 *
 *   SERVO:<channel>:<angle>:<speed>
 *     channel  – servo channel / pin index (0-15)
 *     angle    – target angle in degrees (0-180)
 *     speed    – rotation speed as a percentage of max (1-100)
 *
 *   MULTI_SERVO:<ch>:<angle>:<speed>|<ch>:<angle>:<speed>|...
 *     Control multiple servos in a single command.
 *
 *   QUERY_SERVO:<channel>
 *     Request the current position of a servo.
 *
 *   RESET_ALL
 *     Reset all servos to their default position (90°).
 *
 *   STOP_ALL
 *     Stop all servo movement immediately.
 *
 * ── Response Format ─────────────────────────────────────────────
 *
 *   OK:<message>   – success
 *   ERR:<message>  – error
 */
object ESP32Protocol {

    /** Single servo control command. */
    fun servoCommand(channel: Int, angle: Int, speed: Int): String {
        return "SERVO:$channel:$angle:$speed\n"
    }

    /** Multi-servo control command. Each Triple is (channel, angle, speed). */
    fun multiServoCommand(servos: List<Triple<Int, Int, Int>>): String {
        val params = servos.joinToString("|") { "${it.first}:${it.second}:${it.third}" }
        return "MULTI_SERVO:$params\n"
    }

    /** Query current position of a servo. */
    fun queryServoPosition(channel: Int): String {
        return "QUERY_SERVO:$channel\n"
    }

    /** Reset all servos to default (90°). */
    fun resetAll(): String {
        return "RESET_ALL\n"
    }

    /** Emergency stop for all servos. */
    fun stopAll(): String {
        return "STOP_ALL\n"
    }

    /** Parse an ESP32 response string. Returns (success, message). */
    fun parseResponse(response: String): Pair<Boolean, String> {
        return when {
            response.startsWith("OK:") -> true to response.removePrefix("OK:")
            response.startsWith("ERR:") -> false to response.removePrefix("ERR:")
            else -> false to response
        }
    }
}
