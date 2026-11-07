package ru.opencode.mobile.data

/** Элемент списка сообщений с учётом склейки шагов модели. */
data class DisplayMessage(
    val message: ChatMessage,
    /** id исходных сообщений, вошедших в элемент (для поиска и прокрутки). */
    val sourceIds: List<String>
)

/**
 * OpenCode V2 присылает каждый шаг модели отдельным assistant-сообщением
 * (размышление + один инструмент). Если показывать их по отдельности, чат
 * превращается в стену карточек. Склеиваем подряд идущие assistant-сообщения
 * в один блок — как в десктопном приложении.
 */
object ChatDisplay {

    fun merge(messages: List<ChatMessage>): List<DisplayMessage> {
        val out = mutableListOf<DisplayMessage>()
        val run = mutableListOf<ChatMessage>()

        fun flush() {
            if (run.isEmpty()) return
            val first = run.first()
            val last = run.last()
            out += DisplayMessage(
                message = ChatMessage(
                    // id стабилен с первого шага: блок не «пересоздаётся»,
                    // пока модель добавляет новые шаги.
                    id = "run_" + first.id,
                    role = "assistant",
                    text = join(run.map { it.text }),
                    reasoning = join(run.map { it.reasoning }),
                    tools = run.flatMap { it.tools },
                    created = first.created,
                    completed = run.all { it.completed },
                    modelLabel = run.lastOrNull { it.modelLabel.isNotBlank() }?.modelLabel.orEmpty(),
                    error = join(run.map { it.error })
                ),
                sourceIds = run.map { it.id }
            )
            run.clear()
        }

        for (message in messages) {
            if (message.isHidden) continue
            if (message.isAssistant) {
                run += message
            } else {
                flush()
                out += DisplayMessage(message, listOf(message.id))
            }
        }
        flush()
        return out
    }

    private fun join(values: List<String>): String =
        values.filter { it.isNotBlank() }.joinToString("\n\n").trim()
}
