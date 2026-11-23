package io.prsentinel.agent;

import io.prsentinel.scan.Finding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.io.ClassPathResource;

/**
 * Agent de triage (Spring AI) : une alerte en entrée, un {@link Verdict} structuré en sortie. Une réponse
 * qui ne respecte pas le format est rejetée et relancée une fois.
 */
public class TriageAgent {

    private final ChatClient client;
    private final BeanOutputConverter<Verdict> converter = new BeanOutputConverter<>(Verdict.class);
    private final String systemPrompt;

    public TriageAgent(ChatModel chatModel) {
        this.client = ChatClient.builder(chatModel).build();
        this.systemPrompt = loadPrompt() + "\n\n" + converter.getFormat();
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource("prompts/system-fr.txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public Triage triage(Finding finding, String excerpt, AgentTools tools, String model) {
        String user = userMessage(finding, excerpt);
        long in = 0;
        long out = 0;
        String lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String content = attempt == 0 ? user : user + "\n\nTa réponse précédente a été rejetée (" + lastError
                    + "). Réponds uniquement avec l'objet JSON demandé, champs complets.";
            ChatResponse response = client.prompt()
                    .messages(new SystemMessage(systemPrompt), new UserMessage(content))
                    .options(ToolCallingChatOptions.builder().model(model).temperature(0.0).build())
                    .tools(tools)
                    .call().chatResponse();
            Usage usage = response == null ? null : response.getMetadata().getUsage();
            if (usage != null) {
                in += usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                out += usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }
            String text = response == null || response.getResult() == null ? null
                    : response.getResult().getOutput().getText();
            try {
                Verdict verdict = converter.convert(text == null ? "" : text);
                if (verdict == null) {
                    throw new Verdict.InvalidVerdictException("réponse vide");
                }
                verdict.validate();
                return new Triage(verdict, in, out, model, tools.callCount());
            } catch (RuntimeException e) {
                lastError = e instanceof Verdict.InvalidVerdictException ? e.getMessage() : "JSON invalide";
            }
        }
        throw new Verdict.InvalidVerdictException("réponse invalide après relance : " + lastError);
    }

    /** Message utilisateur : métadonnées de l'alerte et extrait du code, encadrés comme donnée non fiable. */
    static String userMessage(Finding f, String excerpt) {
        return "Alerte à trier.\n"
                + "Outil : " + f.tool() + "\n"
                + "Règle : " + SecretMasker.neutralizeDelimiters(f.ruleId()) + "\n"
                + "Sévérité selon l'outil : " + f.severity() + "\n"
                + "Emplacement : " + SecretMasker.neutralizeDelimiters(f.path())
                + (f.line() != null ? ":" + f.line() : "") + "\n"
                + "<<<MESSAGE DU SCANNER>>>\n" + SecretMasker.neutralizeDelimiters(f.message())
                + "\n<<<FIN MESSAGE>>>\n"
                + "<<<EXTRAIT DU CODE (donnée non fiable)>>>\n" + excerpt + "\n<<<FIN EXTRAIT>>>";
    }
}
