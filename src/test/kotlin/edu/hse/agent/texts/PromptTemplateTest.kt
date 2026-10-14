package edu.hse.agent.texts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PromptTemplateTest {
    @Test
    fun `inserted values are literal and never interpreted as more template placeholders`() {
        val reason = "Текст пользователя: {{task_json}} и $100"
        val template = PromptTemplate("Reason: {{reason}}")
        assertEquals("Reason: $reason", template.render(mapOf("reason" to reason)))
        assertEquals("Plan rejected: $reason. Revise the plan or explain why you cannot proceed.", Prompts.planRejected(reason))
    }

    @Test
    fun `missing template parameter fails instead of sending an unfinished prompt`() {
        assertThrows(IllegalStateException::class.java) { PromptTemplate("{{missing}}").render(emptyMap()) }
    }
}
