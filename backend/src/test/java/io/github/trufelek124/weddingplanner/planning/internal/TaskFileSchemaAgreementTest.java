package io.github.trufelek124.weddingplanner.planning.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The published schema of the format (web/public/formats) and the server say the same: the same fields, the same
 * fixed values, the same limits. A field or a value that one side has and the other has not fails here.
 */
class TaskFileSchemaAgreementTest {

    private static final JsonNode SCHEMA = schema();

    private static JsonNode schema() {
        try {
            return JsonMapper.builder().build().readTree(
                    Files.readString(Path.of("..", "web", "public", "formats", "wedding-planner-tasks-1.schema.json")));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static JsonNode task() {
        return SCHEMA.get("$defs").get("task");
    }

    private static JsonNode money() {
        return SCHEMA.get("$defs").get("money");
    }

    private static Set<String> names(JsonNode object) {
        Set<String> names = new LinkedHashSet<>();
        object.propertyNames().forEach(names::add);
        return names;
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.isNull() ? "null" : value.asString()));
        return values;
    }

    private static Set<String> named(Class<? extends Enum<?>> type) {
        return TaskFileReader.names(type);
    }

    private static boolean allowsNull(JsonNode field) {
        if (field.has("enum")) {
            return strings(field.get("enum")).contains("null");
        }
        if (field.has("type") && field.get("type").isArray()) {
            return strings(field.get("type")).contains("null");
        }
        if (field.has("oneOf")) {
            for (JsonNode option : field.get("oneOf")) {
                if (option.has("type") && "null".equals(option.get("type").asString())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void theFileHasTheFieldsTheServerReadsAndNeedsTheSameOnes() {
        assertThat(SCHEMA.get("additionalProperties").asBoolean(true)).isFalse();
        assertThat(names(SCHEMA.get("properties"))).isEqualTo(TaskFileReader.FILE_FIELDS);
        assertThat(strings(SCHEMA.get("required"))).isEqualTo(TaskFileReader.FILE_REQUIRED);
        assertThat(SCHEMA.get("properties").get("format").get("const").asString()).isEqualTo(TaskFileReader.FORMAT);
        assertThat(SCHEMA.get("properties").get("version").get("const").asInt()).isEqualTo(TaskFileReader.VERSION);
        JsonNode tasks = SCHEMA.get("properties").get("tasks");
        assertThat(tasks.get("minItems").asInt()).isEqualTo(1);
        assertThat(tasks.get("maxItems").asInt()).isEqualTo(TaskFileReader.MOST_ROWS);
        assertThat(tasks.get("items").get("$ref").asString()).isEqualTo("#/$defs/task");
    }

    @Test
    void aRowHasTheFieldsTheServerReadsAndNeedsTheSameOnes() {
        assertThat(task().get("additionalProperties").asBoolean(true)).isFalse();
        assertThat(names(task().get("properties"))).isEqualTo(new LinkedHashSet<>(TaskFileReader.ROW_FIELDS));
        assertThat(strings(task().get("required"))).isEqualTo(TaskFileReader.ROW_REQUIRED);
    }

    @Test
    void anAmountHasTheFieldsTheServerReadsAndNeedsBoth() {
        assertThat(money().get("additionalProperties").asBoolean(true)).isFalse();
        assertThat(names(money().get("properties"))).isEqualTo(new LinkedHashSet<>(TaskFileReader.AMOUNT_FIELDS));
        assertThat(strings(money().get("required"))).isEqualTo(new LinkedHashSet<>(TaskFileReader.AMOUNT_FIELDS));
        assertThat(task().get("properties").get("amount").get("oneOf").get(1).get("$ref").asString()).isEqualTo("#/$defs/money");
    }

    @Test
    void theFixedValuesAreTheServers() {
        JsonNode fields = task().get("properties");
        assertThat(strings(fields.get("status").get("enum"))).isEqualTo(named(Status.class));
        Set<String> priorities = named(Priority.class);
        priorities.add("null");
        assertThat(strings(fields.get("priority").get("enum"))).isEqualTo(priorities);
        Set<String> kinds = named(PaymentKind.class);
        kinds.add("null");
        assertThat(strings(fields.get("paymentKind").get("enum"))).isEqualTo(kinds);
    }

    @Test
    void theSameFieldsMayBeNullOnBothSides() {
        Set<String> schemaNullable = new LinkedHashSet<>();
        for (String field : names(task().get("properties"))) {
            if (allowsNull(task().get("properties").get(field))) {
                schemaNullable.add(field);
            }
        }
        assertThat(schemaNullable).isEqualTo(TaskFileReader.ROW_NULLABLE);
    }

    @Test
    void theLengthsAreTheServers() {
        JsonNode fields = task().get("properties");
        assertLength(fields.get("key"), 1, TaskFileReader.KEY_LENGTH);
        assertLength(fields.get("title"), 1, PlanningDtos.TITLE_LENGTH);
        assertThat(fields.get("notes").get("maxLength").asInt()).isEqualTo(PlanningDtos.NOTES_LENGTH);
        assertLength(fields.get("category"), 1, TaskFileReader.CATEGORY_LENGTH);
        assertLength(fields.get("vendor"), 1, TaskFileReader.VENDOR_LENGTH);
        assertThat(fields.get("key").get("pattern").asString()).isEqualTo(TaskFileReader.KEY_PATTERN);
    }

    private static void assertLength(JsonNode field, int least, int most) {
        assertThat(field.get("minLength").asInt()).isEqualTo(least);
        assertThat(field.get("maxLength").asInt()).isEqualTo(most);
    }

    @Test
    void theDaysAreRealDaysAndTheAmountIsWrittenAsTheServerReadsIt() {
        JsonNode fields = task().get("properties");
        assertThat(fields.get("dueDate").get("format").asString()).isEqualTo("date");
        assertThat(fields.get("paidOn").get("format").asString()).isEqualTo("date");
        JsonNode value = money().get("properties").get("value").get("oneOf");
        List<String> kinds = new ArrayList<>();
        value.forEach(option -> kinds.add(option.get("type").asString()));
        assertThat(kinds).containsExactly("string", "number");
        assertThat(value.get(0).get("pattern").asString()).isEqualTo(TaskFileReader.AMOUNT_PATTERN);
        // Above zero: the form refuses an amount of nothing, and so does the file.
        assertThat(value.get(1).get("exclusiveMinimum").asInt()).isZero();
        assertThat(money().get("properties").get("currency").get("pattern").asString()).isEqualTo(TaskFileReader.CURRENCY_PATTERN);
    }

    @Test
    void theLimitOfTheFileIsSaidInTheSchemaAsTheServerHoldsIt() {
        assertThat(SCHEMA.get("description").asString()).contains("at most 1 MB");
        assertThat(TaskFileReader.MOST_BYTES).isEqualTo(1_048_576);
    }
}
