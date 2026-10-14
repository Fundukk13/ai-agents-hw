package edu.hse.agent.llm

import edu.hse.agent.models.Message
import edu.hse.agent.models.ToolCall
import edu.hse.agent.models.ToolDefinition
import edu.hse.agent.serialization.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val FIXED_ORDER = """def calculate_total(prices: list[float], discount_percent: float) -> float:
    if not 0 <= discount_percent <= 100:
        raise ValueError("discount_percent must be between 0 and 100")
    return sum(prices) * (1 - discount_percent / 100)
"""

class ScriptedModelClient : ModelClient {
    override fun respond(
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): Message {
        val stage = messages.count { it.role == "assistant" }

        fun call(
            suffix: String,
            name: String,
            args: JsonObject,
        ) = ToolCall("scripted-$stage-$suffix", name, json.encodeToString(args))
        val planApproved = messages.any { it.role == "user" && it.content == "Plan approved. Execute it now." }
        val planning = messages.first().content!!.contains("Start in Plan Mode") && !planApproved
        val lastResult = messages.lastOrNull { it.role == "tool" }?.content
        if (lastResult != null &&
            runCatching {
                !json
                    .parseToJsonElement(lastResult)
                    .jsonObject
                    .getValue("ok")
                    .jsonPrimitive.boolean
            }.getOrDefault(false)
        ) {
            return Message("assistant", "Демонстрация остановлена: инструмент вернул ошибку. Смотрите сохранённый результат.")
        }
        if (planning) {
            return if (stage ==
                0
            ) {
                Message("assistant", toolCalls = listOf(call("read", "read_file", buildJsonObject { put("path", "order.py") })))
            } else {
                Message(
                    "assistant",
                    toolCalls =
                        listOf(
                            call(
                                "plan",
                                "submit_plan",
                                buildJsonObject {
                                    put("goal", "Исправить расчёт стоимости заказа")
                                    put(
                                        "steps",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("description", "Проверить код и тесты")
                                                    put("tools", buildJsonArray { add("read_file") })
                                                },
                                            )
                                            add(
                                                buildJsonObject {
                                                    put("description", "Исправить процент скидки и валидацию")
                                                    put("tools", buildJsonArray { add("write_file") })
                                                },
                                            )
                                            add(
                                                buildJsonObject {
                                                    put("description", "Запустить тесты")
                                                    put("tools", buildJsonArray { add("Bash") })
                                                },
                                            )
                                        },
                                    )
                                    put("risks", buildJsonArray { add("Bash исполняет код с правами пользователя") })
                                    put("assumptions", buildJsonArray { add("Python 3 доступен; работаем с учебной копией") })
                                    put("done_when", "Тесты проходят; test_order.py не менялся")
                                },
                            ),
                        ),
                )
            }
        }
        val completed = messages.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
        val writeDone =
            messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.any {
                it.name == "write_file" &&
                    it.id in completed
            }
        val bashDone = messages.filter { it.role == "assistant" }.flatMap { it.toolCalls }.any { it.name == "Bash" && it.id in completed }
        return when {
            bashDone -> {
                val result = json.parseToJsonElement(requireNotNull(lastResult)).jsonObject
                val passed = result["exit_code"]?.jsonPrimitive?.intOrNull == 0 && result["timed_out"]?.jsonPrimitive?.boolean == false
                Message(
                    "assistant",
                    if (passed) {
                        "Исправление внесено, unittest завершился с exit_code=0."
                    } else {
                        "Проверка не прошла; смотрите результат Bash."
                    },
                )
            }

            writeDone -> {
                Message(
                    "assistant",
                    toolCalls =
                        listOf(
                            call(
                                "test",
                                "Bash",
                                buildJsonObject {
                                    put("command", "python3 -B -m unittest -v")
                                    put("cwd", ".")
                                },
                            ),
                        ),
                )
            }

            messages.any { it.role == "tool" && it.toolCallId?.endsWith("tests") == true } -> {
                Message(
                    "assistant",
                    toolCalls =
                        listOf(
                            call(
                                "write",
                                "write_file",
                                buildJsonObject {
                                    put("path", "order.py")
                                    put("content", FIXED_ORDER)
                                },
                            ),
                        ),
                )
            }

            else -> {
                Message(
                    "assistant",
                    toolCalls =
                        listOf(
                            call("files", "list_files", buildJsonObject { put("path", ".") }),
                            call("code", "read_file", buildJsonObject { put("path", "order.py") }),
                            call("tests", "read_file", buildJsonObject { put("path", "test_order.py") }),
                        ),
                )
            }
        }
    }
}
