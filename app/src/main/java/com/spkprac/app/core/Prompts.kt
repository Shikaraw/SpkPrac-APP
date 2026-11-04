package com.spkprac.app.core

import com.spkprac.app.data.AppSettings
import com.spkprac.app.data.CorrectionPolicy
import com.spkprac.app.data.Languages

object Prompts {

    fun chat(customPrompt: String, speakLang: String): String {
        val base = customPrompt.ifBlank { AppSettings.DEFAULT_CHAT_PROMPT }
        val langLine = if (speakLang == "auto" || speakLang.isBlank())
            "Reply in the same language the user speaks."
        else
            "Always reply in ${Languages.name(speakLang)}."
        return base.trimEnd() + "\n\nStyle: your replies are spoken aloud to the user. " +
            "Keep them concise, natural and conversational. Never use markdown. " + langLine
    }

    fun practice(langCode: String, level: String, topic: String, policy: CorrectionPolicy): String {
        val lang = Languages.name(langCode)
        val topicLine = if (topic.isBlank())
            "free conversation — pick interesting everyday subjects"
        else "\"$topic\""
        val correction = when (policy) {
            CorrectionPolicy.NEVER ->
                "Do NOT correct the learner's mistakes. Just keep the conversation natural and flowing."
            CorrectionPolicy.SOMETIMES ->
                "If the learner makes a notable mistake, briefly correct it inline: say the corrected " +
                    "phrase in one short sentence, then continue the conversation naturally. " +
                    "Do this at most once per learner turn; ignore small slips."
            CorrectionPolicy.ALWAYS ->
                "Actively correct notable mistakes: after the learner's turn, briefly restate the " +
                    "corrected version of what they said, then continue. Keep each correction to one " +
                    "short sentence so the conversation stays flowing."
        }
        return "You are a professional spoken-$lang coach having a live voice conversation with a " +
            "learner (level: about CEFR $level).\n" +
            "Rules:\n" +
            "- Speak ONLY $lang, using vocabulary suited to a $level learner.\n" +
            "- Conversation topic: $topicLine.\n" +
            "- Keep your turns short (1-3 sentences) and usually end with a question to keep the conversation flowing.\n" +
            "- Never switch to another language, except at most a 3-5 word parenthetical clarification when a word is truly unclear.\n" +
            "- Be warm, patient and encouraging.\n" +
            "- Correction policy: $correction"
    }

    fun translateSystem(targetLangName: String): String =
        "You are a translation engine. Translate the user's text into $targetLangName. " +
            "Output ONLY the translation, with no explanations or quotes. Preserve the tone. " +
            "If the text is already in $targetLangName, output it unchanged."

    fun reportUserPrompt(
        langCode: String,
        level: String,
        messages: List<Pair<Boolean, String>> // (isUser, text)
    ): String {
        val lang = Languages.name(langCode)
        val dialog = messages.joinToString("\n") { (isUser, text) ->
            (if (isUser) "[学习者] " else "[教练] ") + text
        }
        return "以下是学习者练习${lang}口语的一段对话记录（学习水平约 CEFR $level）。\n" +
            "请根据学习者的发言，用中文写一份口语练习报告，直接输出 Markdown 正文，包含以下小节：\n" +
            "## 总体表现\n2-3 句整体评价（流利度、内容、互动）。\n" +
            "## 语法与用词问题\n逐条列出：原句 → 建议改法 → 一句话原因。按严重程度排序，最多 8 条；没有明显问题就写「未发现明显问题」。\n" +
            "## 更地道的表达\n2-4 条，针对学习者说过的内容给出母语者更自然的说法。\n" +
            "## 下次练习建议\n2-3 条具体可执行的建议。\n\n" +
            "对话记录：\n$dialog"
    }

    val reportSystem: String =
        "你是一位资深的口语教练，擅长根据对话记录给出具体、可操作、语气友善的改进建议。"
}
