-- What the reading of the mail (S04) used of the AI service: per day, step and model, how many calls reached the
-- service and how many tokens they counted. The cost estimate and the brakes are worked out from this and from
-- nothing else. Nothing of a message or of an answer is here: no sender, no subject, no text, no identifier.
-- Days are UTC days.

CREATE TABLE reading_usage (
    day           date   NOT NULL,
    step          text   NOT NULL CHECK (step IN ('TRIAGE', 'EXTRACTION')),
    -- The model that gave the answer, as the service names it; it can differ from the one asked for.
    model         text   NOT NULL CHECK (char_length(model) BETWEEN 1 AND 100),
    calls         bigint NOT NULL CHECK (calls >= 0),
    input_tokens  bigint NOT NULL CHECK (input_tokens >= 0),
    output_tokens bigint NOT NULL CHECK (output_tokens >= 0),
    PRIMARY KEY (day, step, model)
);
