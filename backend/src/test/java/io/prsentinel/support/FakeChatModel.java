package io.prsentinel.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** Faux modèle : renvoie des réponses préparées et enregistre les prompts reçus. */
public class FakeChatModel implements ChatModel {

    public record Reply(String text, int in, int out, String toolName, String toolArgs) {
        public Reply(String text, int in, int out) {
            this(text, in, out, null, null);
        }
    }

    private final Deque<Reply> replies = new ArrayDeque<>();
    public final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());
    /** Réponse par défaut quand la file est vide (null : échec). */
    public volatile Reply fallback;

    public FakeChatModel reply(String text, int in, int out) {
        replies.add(new Reply(text, in, out));
        return this;
    }

    /** Réponse qui demande l'exécution d'un outil (boucle d'outils pilotée par l'agent). */
    public FakeChatModel replyToolCall(String tool, String argsJson, int in, int out) {
        replies.add(new Reply(null, in, out, tool, argsJson));
        return this;
    }

    public static String verdict(String decision, String gravite, String correctif, double confiance) {
        return "{\"decision\":\"" + decision + "\",\"gravite\":\"" + gravite + "\",\"explication\":\"La valeur "
                + "vient de la requête HTTP et atteint la base sans paramètre.\",\"correctif\":"
                + (correctif == null ? "null" : "\"" + correctif.replace("\"", "\\\"").replace("\n", "\\n") + "\"")
                + ",\"confiance\":" + confiance + "}";
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        Reply r = replies.isEmpty() ? fallback : replies.poll();
        if (r == null) {
            throw new IllegalStateException("modèle indisponible");
        }
        AssistantMessage message = r.toolName() == null ? new AssistantMessage(r.text())
                : AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-" + prompts.size(), "function", r.toolName(), r.toolArgs()))).build();
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(r.in(), r.out())).build());
    }

    public String model(int index) {
        return prompts.get(index).getOptions().getModel();
    }
}
