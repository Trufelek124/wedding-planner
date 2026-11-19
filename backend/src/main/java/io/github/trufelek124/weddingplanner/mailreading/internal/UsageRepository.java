package io.github.trufelek124.weddingplanner.mailreading.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** What the AI service was used for: counts and tokens per day, step and model, and nothing of any message. */
@Repository
class UsageRepository {

    enum Step {
        TRIAGE,
        EXTRACTION
    }

    /** Tokens of one step over some time. */
    record Tokens(long in, long out) {}

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}");

    private final JdbcClient jdbc;

    UsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts one call that reached the service. A model's name that could not be one is kept as "unknown". */
    void count(LocalDate day, Step step, String model, long inputTokens, long outputTokens) {
        String name = model != null && MODEL_NAME.matcher(model).matches() ? model : "unknown";
        jdbc.sql("""
                        INSERT INTO reading_usage (day, step, model, calls, input_tokens, output_tokens)
                        VALUES (:day, :step, :model, 1, :in, :out)
                        ON CONFLICT (day, step, model) DO UPDATE
                        SET calls = reading_usage.calls + 1,
                            input_tokens = reading_usage.input_tokens + EXCLUDED.input_tokens,
                            output_tokens = reading_usage.output_tokens + EXCLUDED.output_tokens
                        """)
                .param("day", day)
                .param("step", step.name())
                .param("model", name)
                .param("in", inputTokens)
                .param("out", outputTokens)
                .update();
    }

    /** How many triage calls reached the service on this day. */
    long triagedOn(LocalDate day) {
        return jdbc.sql("SELECT coalesce(sum(calls), 0) FROM reading_usage WHERE day = :day AND step = 'TRIAGE'")
                .param("day", day)
                .query(Long.class)
                .single();
    }

    /** The tokens of one step from {@code from} (included) to {@code until} (excluded). */
    Tokens tokens(Step step, LocalDate from, LocalDate until) {
        return jdbc.sql("""
                        SELECT coalesce(sum(input_tokens), 0) AS tokens_in, coalesce(sum(output_tokens), 0) AS tokens_out
                        FROM reading_usage
                        WHERE step = :step AND day >= :from AND day < :until
                        """)
                .param("step", step.name())
                .param("from", from)
                .param("until", until)
                .query((rows, number) -> new Tokens(rows.getLong("tokens_in"), rows.getLong("tokens_out")))
                .single();
    }

    /** The tokens of one step since the beginning. */
    Tokens tokens(Step step) {
        return jdbc.sql("""
                        SELECT coalesce(sum(input_tokens), 0) AS tokens_in, coalesce(sum(output_tokens), 0) AS tokens_out
                        FROM reading_usage
                        WHERE step = :step
                        """)
                .param("step", step.name())
                .query((rows, number) -> new Tokens(rows.getLong("tokens_in"), rows.getLong("tokens_out")))
                .single();
    }

    /** Dollars from tokens and a price per million tokens, exactly. */
    static BigDecimal dollars(Tokens tokens, Rules.Price price) {
        BigDecimal million = BigDecimal.valueOf(1_000_000);
        return BigDecimal.valueOf(tokens.in()).multiply(price.in()).add(BigDecimal.valueOf(tokens.out()).multiply(price.out())).divide(million);
    }
}
