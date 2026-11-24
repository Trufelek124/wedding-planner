package io.github.trufelek124.weddingplanner.mailreading.internal;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredContentBlock;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import io.github.trufelek124.weddingplanner.mailreading.MailExtraction;
import io.github.trufelek124.weddingplanner.mailreading.MailTriage;
import io.github.trufelek124.weddingplanner.mailreading.Outcome;
import io.github.trufelek124.weddingplanner.mailreading.Used;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two steps, against the AI service through its own Java SDK (D-007). Structured output and no tools: the models
 * are given nothing to act with, and what they answer has the shape of {@link RawTriage} or {@link RawExtraction}, which
 * {@link Checking} then checks again. A request carries nothing but {@link RequestText}, and no request and no answer is
 * kept or logged: the log says which step ran, what it came to, the model and the tokens.
 *
 * <p>Not sent, because the models refuse them (400): thinking turned off or given a budget, a temperature, top_p,
 * top_k, a prefilled answer, a forced tool. The SDK retries twice by itself; nothing here loops.
 */
final class AnthropicSteps implements ReadingSteps {

    private static final Logger log = LoggerFactory.getLogger(AnthropicSteps.class);

    /** Triage's room for answering. Thinking counts into it. */
    static final long TRIAGE_MAX_TOKENS = 2_000;

    static final long EXTRACTION_MAX_TOKENS = 16_000;

    private final ReadingSetup.Config config;
    private final AnthropicClient client;
    private final Instructions instructions;

    AnthropicSteps(ReadingSetup.Config config, AnthropicClient client, Instructions instructions) {
        this.config = config;
        this.client = client;
        this.instructions = instructions;
    }

    @Override
    public Outcome<MailTriage.Triaged> triage(MailTriage.Input message) {
        String model = config.triageModel();
        StructuredMessage<RawTriage> reply;
        try {
            StructuredMessageCreateParams<RawTriage> params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(TRIAGE_MAX_TOKENS)
                    .system(instructions.triage())
                    .addUserMessage(RequestText.triage(message))
                    .outputConfig(StructuredOutputConfig.<RawTriage>builder()
                            .effort(OutputConfig.Effort.LOW)
                            .format(RawTriage.class)
                            .build())
                    .build();
            reply = client.messages().create(params);
        } catch (RuntimeException failure) {
            return failed("triage", model, failure);
        }
        Used used = new Used(reply.model().asString(), reply.usage().inputTokens(), reply.usage().outputTokens());
        Optional<StopReason> stop = reply.stopReason();
        // The stop reason first, the content after it.
        if (stop.isPresent() && StopReason.REFUSAL.equals(stop.get())) {
            return ended("triage", Outcome.Why.DECLINED, used);
        }
        if (stop.isEmpty() || !StopReason.END_TURN.equals(stop.get())) {
            return ended("triage", Outcome.Why.CANNOT_BE_USED, used);
        }
        Optional<MailTriage.Triaged> checked = Checking.triaged(first(() -> reply.content().stream().flatMap(block -> text(block).stream()).toList()));
        if (checked.isEmpty()) {
            return ended("triage", Outcome.Why.CANNOT_BE_USED, used);
        }
        log.info("Mail reading, triage: answered, model {}, {} tokens in, {} out", used.model(), used.inputTokens(), used.outputTokens());
        return new Outcome.Answered<>(checked.get(), used);
    }

    @Override
    public Outcome<MailExtraction.Extracted> extract(MailExtraction.Input message) {
        String model = config.extractionModel();
        RequestText.Fitted fitted = RequestText.fit(message);
        com.anthropic.models.beta.messages.StructuredMessage<RawExtraction> reply;
        try {
            com.anthropic.models.beta.messages.StructuredMessageCreateParams<RawExtraction> params =
                    com.anthropic.models.beta.messages.MessageCreateParams.builder()
                            .model(model)
                            .maxTokens(EXTRACTION_MAX_TOKENS)
                            .system(instructions.extraction())
                            .addUserMessage(RequestText.extraction(message, fitted))
                            .outputConfig(com.anthropic.models.beta.messages.StructuredOutputConfig.<RawExtraction>builder()
                                    .effort(BetaOutputConfig.Effort.MEDIUM)
                                    .format(RawExtraction.class)
                                    .build())
                            // D-007: when the larger model declines, the service's own fallback may answer instead.
                            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                            .fallbacksDefault()
                            .build();
            reply = client.beta().messages().create(params);
        } catch (RuntimeException failure) {
            return failed("extraction", model, failure);
        }
        // The model that gave the answer, and what that attempt counted.
        Used used = new Used(reply.model().asString(), reply.usage().inputTokens(), reply.usage().outputTokens());
        Optional<BetaStopReason> stop = reply.stopReason();
        if (stop.isPresent() && BetaStopReason.REFUSAL.equals(stop.get())) {
            return ended("extraction", Outcome.Why.DECLINED, used);
        }
        if (stop.isEmpty() || !BetaStopReason.END_TURN.equals(stop.get())) {
            return ended("extraction", Outcome.Why.CANNOT_BE_USED, used);
        }
        RawExtraction raw = first(() -> reply.content().stream()
                .flatMap(block -> block.text().stream())
                .map(com.anthropic.models.beta.messages.StructuredTextBlock::text)
                .toList());
        if (raw != null && raw.proposals() != null && raw.proposals().size() > message.mostProposals()) {
            return ended("extraction", Outcome.Why.TOO_MANY_PROPOSALS, used);
        }
        Checking.Sent sent = new Checking.Sent(
                message.text(),
                fitted.kept().stream().map(earlier -> earlier.text()).toList(),
                message.documents().stream().map(document -> document.text()).toList());
        Optional<MailExtraction.Extracted> checked = Checking.extracted(raw, sent, message.mostProposals());
        if (checked.isEmpty()) {
            return ended("extraction", Outcome.Why.CANNOT_BE_USED, used);
        }
        log.info(
                "Mail reading, extraction: answered with {} proposals of {}, model {}, {} tokens in, {} out",
                checked.get().proposals().size(),
                raw.proposals().size(),
                used.model(),
                used.inputTokens(),
                used.outputTokens());
        return new Outcome.Answered<>(new MailExtraction.Extracted(checked.get().proposals(), fitted.leftOut()), used);
    }

    @Override
    public void close() {
        client.close();
    }

    private static <T> Optional<T> text(StructuredContentBlock<T> block) {
        return block.text().map(com.anthropic.models.messages.StructuredTextBlock::text);
    }

    /** The first text of the answer, read as the shape that was asked for; null when there is none or it is not that shape. */
    private static <T> T first(Supplier<List<T>> texts) {
        try {
            List<T> all = texts.get();
            return all.isEmpty() ? null : all.get(0);
        } catch (RuntimeException notTheShape) {
            // Its message may hold what the model said: only that it happened is known.
            return null;
        }
    }

    private static <A> Outcome<A> ended(String step, Outcome.Why why, Used used) {
        log.info("Mail reading, {}: {}, model {}, {} tokens in, {} out", step, why, used.model(), used.inputTokens(), used.outputTokens());
        return new Outcome.Unanswered<>(why, used);
    }

    private static <A> Outcome<A> failed(String step, String model, RuntimeException failure) {
        Outcome.Why why = Failures.why(failure);
        // The kind and the status only: the exception's message may hold what was sent or answered.
        log.warn("Mail reading, {}: the service failed ({}, status {}): {}, model {}", step, failure.getClass().getSimpleName(), Failures.status(failure), why, model);
        return new Outcome.Unanswered<>(why, Used.NOTHING);
    }
}
