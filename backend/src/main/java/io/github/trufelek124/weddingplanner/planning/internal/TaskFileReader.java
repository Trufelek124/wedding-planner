package io.github.trufelek124.weddingplanner.planning.internal;

import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.CategoryName;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningDtos.NewEntry;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.PaymentKind;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Priority;
import io.github.trufelek124.weddingplanner.planning.internal.PlanningTypes.Status;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileDtos.RowProblem;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.Fields;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.Items;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.Nothing;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.NotJson;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.Text;
import io.github.trufelek124.weddingplanner.planning.internal.TaskFileJson.Value;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads a file of tasks (S06, format version 1) into rows, and says in words for a person what is wrong with the
 * file or with a row. It looks at the file only: what the planner has is for {@link TaskFileService}. The rules a
 * form's fields declare (the title's and the notes' lengths, what an amount may be) are asked of the same declared
 * rules, so the words are the form's own.
 */
final class TaskFileReader {

    static final String FORMAT = "wedding-planner-tasks";
    static final int VERSION = 1;
    static final int MOST_BYTES = 1_048_576;
    static final int MOST_ROWS = 1_000;
    static final int KEY_LENGTH = 100;
    static final int CATEGORY_LENGTH = 60;
    static final int VENDOR_LENGTH = 200;
    /** What the format spells at the top of the file. */
    static final Set<String> FILE_FIELDS = Set.of("format", "version", "tasks");
    static final Set<String> FILE_REQUIRED = FILE_FIELDS;
    /** What the format spells in a row, in the order a person reads them. */
    static final List<String> ROW_FIELDS = List.of(
            "key", "title", "notes", "dueDate", "status", "priority", "category", "vendor", "amount", "paymentKind", "paidOn");
    static final Set<String> ROW_REQUIRED = Set.of("key", "title");
    /** The fields of a row that may be {@code null}: emptied. */
    static final Set<String> ROW_NULLABLE =
            Set.of("dueDate", "priority", "category", "vendor", "amount", "paymentKind", "paidOn");
    static final List<String> AMOUNT_FIELDS = List.of("value", "currency");

    static final String NOT_JSON = "This is not a file of tasks in this format. The file has to be JSON text in UTF-8.";
    static final String NOT_AN_OBJECT =
            "This is not a file of tasks in this format. The file has to be one JSON object with \"format\", \"version\" and \"tasks\".";
    static final String WRONG_FORMAT =
            "This is not a file of tasks in this format. \"format\" has to be \"" + FORMAT + "\".";
    static final String WRONG_VERSION = "This planner reads version 1 of the format only. \"version\" has to be 1.";
    static final String NO_LIST = "The file has no list of tasks. \"tasks\" has to be a list.";
    static final String NO_TASKS = "The file has no tasks. Add at least one.";
    static final String TOO_MANY_TASKS = "The file has more than 1,000 tasks. Split it into several files.";

    /** What the format says a key looks like (a pattern of the schema, as it is written there). */
    static final String KEY_PATTERN = "^[^\\s\\p{Cc}]([^\\p{Cc}]*[^\\s\\p{Cc}])?$";
    static final int MOST_WHOLE_DIGITS = 13;
    /** No currency has more decimals than this; a wedding's own currency may have fewer. */
    static final int MOST_DECIMALS = 3;
    /** What the format says the text of an amount looks like. */
    static final String AMOUNT_PATTERN = "^[0-9]{1," + MOST_WHOLE_DIGITS + "}(\\.[0-9]{1," + MOST_DECIMALS + "})?$";
    static final String CURRENCY_PATTERN = "^[A-Z]{3}$";

    private static final Pattern KEY = Pattern.compile(KEY_PATTERN, Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+(\\.[0-9]+)?");

    private final Validator validator;
    private final String currency;
    private final int decimals;

    TaskFileReader(Validator validator, String currency, int decimals) {
        this.validator = validator;
        this.currency = currency;
        this.decimals = decimals;
    }

    /** A field of a row: whether the row has it, and what it says ({@code null} for JSON null). */
    record Given<T>(boolean present, T value) {
        static <T> Given<T> left() {
            return new Given<>(false, null);
        }

        static <T> Given<T> of(T value) {
            return new Given<>(true, value);
        }

        @Override
        public String toString() {
            return "Given[hidden]";
        }
    }

    /**
     * One row. {@code key} and {@code title} are what could be read as text (for showing), and {@code keyFit} says that
     * the key is one the format allows.
     */
    record Row(
            int number,
            String key,
            boolean keyFit,
            String title,
            Given<String> notes,
            Given<LocalDate> dueDate,
            Given<Status> status,
            Given<Priority> priority,
            Given<String> category,
            Given<String> vendor,
            Given<Long> amount,
            Given<PaymentKind> paymentKind,
            Given<LocalDate> paidOn,
            List<RowProblem> problems) {

        @Override
        public String toString() {
            return "Row[hidden]";
        }
    }

    /**
     * @param problems what is wrong with the file as a whole
     * @param rows the rows; null when the file is not this format at all, so that no row was looked at
     */
    record Reading(List<String> problems, List<Row> rows) {
        @Override
        public String toString() {
            return "Reading[hidden]";
        }
    }

    /** @param text the file's text; null when it was not UTF-8 text at all */
    Reading read(String text) {
        if (text == null) {
            return refused(NOT_JSON);
        }
        Value root;
        try {
            root = TaskFileJson.read(text);
        } catch (NotJson notJson) {
            return refused(NOT_JSON);
        }
        if (!(root instanceof Fields file)) {
            return refused(NOT_AN_OBJECT);
        }
        List<String> formatProblems = new ArrayList<>();
        if (!(file.fields().get("format") instanceof Text format) || !FORMAT.equals(format.value())) {
            formatProblems.add(WRONG_FORMAT);
        }
        if (!(file.fields().get("version") instanceof TaskFileJson.Number version) || !isVersionOne(version.written())) {
            formatProblems.add(WRONG_VERSION);
        }
        List<Value> tasks = null;
        if (!(file.fields().get("tasks") instanceof Items items)) {
            formatProblems.add(NO_LIST);
        } else if (items.items().isEmpty()) {
            formatProblems.add(NO_TASKS);
        } else if (items.items().size() > MOST_ROWS) {
            formatProblems.add(TOO_MANY_TASKS);
        } else {
            tasks = items.items();
        }
        if (!formatProblems.isEmpty()) {
            return new Reading(formatProblems, null);
        }
        List<String> problems = new ArrayList<>();
        for (String name : file.repeated()) {
            problems.add("The name " + quoted(name) + " is used twice at the top of the file.");
        }
        for (String name : file.fields().keySet()) {
            if (!FILE_FIELDS.contains(name)) {
                problems.add("The file has no field called " + quoted(name) + ". The format has \"format\", \"version\" and \"tasks\".");
            }
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            rows.add(row(i + 1, tasks.get(i)));
        }
        return new Reading(problems, rows);
    }

    private static Reading refused(String problem) {
        return new Reading(List.of(problem), null);
    }

    private static boolean isVersionOne(String written) {
        try {
            return !written.contains("e") && !written.contains("E") && new BigDecimal(written).compareTo(BigDecimal.ONE) == 0;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    // ------------------------------------------------------------ a row

    private Row row(int number, Value value) {
        List<RowProblem> problems = new ArrayList<>();
        if (!(value instanceof Fields fields)) {
            problems.add(new RowProblem(null, "This row has to be an object with at least a key and a title."));
            return new Row(number, null, false, null, Given.left(), Given.left(), Given.left(), Given.left(),
                    Given.left(), Given.left(), Given.left(), Given.left(), Given.left(), problems);
        }
        Map<String, Value> given = fields.fields();
        for (String name : fields.repeated()) {
            problems.add(new RowProblem(shortName(name), "The name " + quoted(name) + " is used twice in this row."));
        }
        for (String name : given.keySet()) {
            if (!ROW_FIELDS.contains(name)) {
                problems.add(new RowProblem(shortName(name), "The format has no field called " + quoted(name)
                        + ". The fields are " + String.join(", ", ROW_FIELDS) + "."));
            }
        }

        String key = null;
        boolean keyFit = false;
        if (!given.containsKey("key")) {
            problems.add(new RowProblem("key", "Give the task a key: any text that is yours, 1 to 100 characters."));
        } else if (!(given.get("key") instanceof Text text)) {
            problems.add(new RowProblem("key", "The key has to be text."));
        } else {
            key = text.value();
            keyFit = key.codePointCount(0, key.length()) <= KEY_LENGTH && KEY.matcher(key).matches();
            if (!keyFit) {
                problems.add(new RowProblem("key", "Use 1 to 100 characters for the key, with no line break and no space at "
                        + "its beginning or end."));
            }
        }

        String title = null;
        if (!given.containsKey("title")) {
            problems.add(new RowProblem("title", "Enter a title."));
        } else if (!(given.get("title") instanceof Text text)) {
            problems.add(new RowProblem("title", "The title has to be text."));
        } else {
            title = text.value();
            declared("title", title, "title", problems);
        }

        Given<String> notes = Given.left();
        if (given.containsKey("notes")) {
            if (given.get("notes") instanceof Text text) {
                notes = Given.of(text.value());
                declared("notes", text.value(), "notes", problems);
            } else {
                problems.add(new RowProblem("notes", "The notes have to be text. Use \"\" to empty them."));
            }
        }

        Given<LocalDate> dueDate = day("dueDate", given, problems);
        Given<LocalDate> paidOn = day("paidOn", given, problems);
        Given<Status> status = choice("status", given, Status.class, false,
                "Use one of NOT_STARTED, IN_PROGRESS, DONE or DROPPED.", problems);
        Given<Priority> priority = choice("priority", given, Priority.class, true,
                "Use one of CRITICAL, HIGH, NORMAL or LOW, or null to let the planner set it.", problems);
        Given<PaymentKind> paymentKind = choice("paymentKind", given, PaymentKind.class, true,
                "Use one of DEPOSIT, INSTALMENT or FINAL, or null for none.", problems);
        Given<String> category = category(given, problems);
        Given<String> vendor = vendor(given, problems);
        Given<Long> amount = amount(given, problems);

        return new Row(number, key, keyFit, title, notes, dueDate, status, priority, category, vendor, amount, paymentKind,
                paidOn, problems);
    }

    private Given<LocalDate> day(String field, Map<String, Value> given, List<RowProblem> problems) {
        if (!given.containsKey(field)) {
            return Given.left();
        }
        Value value = given.get(field);
        if (value instanceof Nothing) {
            return Given.of(null);
        }
        if (value instanceof Text text && DAY.matcher(text.value()).matches()) {
            try {
                return Given.of(LocalDate.parse(text.value()));
            } catch (DateTimeParseException notADay) {
                // said below
            }
        }
        problems.add(new RowProblem(field, "Use a real day written YYYY-MM-DD, for example 2027-03-01, or null for none."));
        return Given.left();
    }

    private <E extends Enum<E>> Given<E> choice(
            String field, Map<String, Value> given, Class<E> type, boolean mayBeNull, String expected, List<RowProblem> problems) {
        if (!given.containsKey(field)) {
            return Given.left();
        }
        Value value = given.get(field);
        if (value instanceof Nothing && mayBeNull) {
            return Given.of(null);
        }
        if (value instanceof Text text) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(text.value())) {
                    return Given.of(constant);
                }
            }
        }
        problems.add(new RowProblem(field, expected));
        return Given.left();
    }

    private Given<String> category(Map<String, Value> given, List<RowProblem> problems) {
        if (!given.containsKey("category")) {
            return Given.left();
        }
        Value value = given.get("category");
        if (value instanceof Nothing) {
            return Given.of(null);
        }
        if (!(value instanceof Text text)) {
            problems.add(new RowProblem("category", "The category has to be the name of a category, or null for none."));
            return Given.left();
        }
        List<String> messages = violations(CategoryName.class, "name", text.value());
        messages.forEach(message -> problems.add(new RowProblem("category", message)));
        return messages.isEmpty() ? Given.of(text.value()) : Given.left();
    }

    private Given<String> vendor(Map<String, Value> given, List<RowProblem> problems) {
        if (!given.containsKey("vendor")) {
            return Given.left();
        }
        Value value = given.get("vendor");
        if (value instanceof Nothing) {
            return Given.of(null);
        }
        if (!(value instanceof Text text) || text.value().isBlank()) {
            problems.add(new RowProblem("vendor", "The vendor has to be the name of a vendor the planner has, or null for none."));
            return Given.left();
        }
        if (text.value().length() > VENDOR_LENGTH) {
            problems.add(new RowProblem("vendor", "Use at most 200 characters."));
            return Given.left();
        }
        return Given.of(text.value());
    }

    // ------------------------------------------------------------ an amount

    private Given<Long> amount(Map<String, Value> given, List<RowProblem> problems) {
        if (!given.containsKey("amount")) {
            return Given.left();
        }
        Value value = given.get("amount");
        if (value instanceof Nothing) {
            return Given.of(null);
        }
        String shape = "Give the amount as { \"value\": \"12000.00\", \"currency\": \"" + currency + "\" }, or null for none.";
        if (!(value instanceof Fields fields)) {
            problems.add(new RowProblem("amount", shape));
            return Given.left();
        }
        int before = problems.size();
        for (String name : fields.repeated()) {
            problems.add(new RowProblem("amount", "The name " + quoted(name) + " is used twice in the amount."));
        }
        for (String name : fields.fields().keySet()) {
            if (!AMOUNT_FIELDS.contains(name)) {
                problems.add(new RowProblem("amount", "The amount has no field called " + quoted(name)
                        + ". It has \"value\" and \"currency\"."));
            }
        }
        Value currencyGiven = fields.fields().get("currency");
        if (currencyGiven == null) {
            problems.add(new RowProblem("amount", "The amount needs its currency: \"" + currency + "\"."));
        } else if (!(currencyGiven instanceof Text text) || !currency.equals(text.value())) {
            problems.add(new RowProblem("amount", "The wedding's currency is " + currency
                    + ". An amount in another currency is refused: nothing is converted."));
        }
        Long smallest = null;
        Value valueGiven = fields.fields().get("value");
        if (valueGiven == null) {
            problems.add(new RowProblem("amount", "The amount needs a value, such as \"12000.00\"."));
        } else {
            String written = switch (valueGiven) {
                case Text text -> text.value();
                case TaskFileJson.Number number -> number.written();
                default -> null;
            };
            smallest = written == null ? null : smallestUnits(written, valueGiven instanceof Text, problems);
            if (written == null) {
                problems.add(new RowProblem("amount", "The value of the amount has to be a number, or text such as \"12000.00\"."));
            }
        }
        if (smallest != null) {
            violations(NewEntry.class, "amount", smallest).forEach(message -> problems.add(new RowProblem("amount", message)));
        }
        return problems.size() == before ? Given.of(smallest) : Given.left();
    }

    /** The amount in whole numbers of the currency's smallest unit, exactly; null (with the reason) when it is not one. */
    private Long smallestUnits(String written, boolean asText, List<RowProblem> problems) {
        if (written.startsWith("-")) {
            problems.add(new RowProblem("amount", "The amount cannot be negative."));
            return null;
        }
        if (!asText && (written.contains("e") || written.contains("E"))) {
            problems.add(new RowProblem("amount", "Write the amount without an exponent, for example 12000.50."));
            return null;
        }
        if (!DIGITS.matcher(written).matches()) {
            problems.add(new RowProblem("amount", "Write the amount in digits, for example \"12000\" or \"12000.50\": no signs, "
                    + "no separators, no letters."));
            return null;
        }
        int point = written.indexOf('.');
        String whole = point < 0 ? written : written.substring(0, point);
        int writtenDecimals = point < 0 ? 0 : written.length() - point - 1;
        if (writtenDecimals > decimals) {
            problems.add(new RowProblem("amount", decimals == 0
                    ? "In " + currency + " the amount has no decimals."
                    : "In " + currency + " the amount can have at most " + decimals + " decimals."));
            return null;
        }
        if (whole.replaceFirst("^0+(?=.)", "").length() > MOST_WHOLE_DIGITS) {
            problems.add(new RowProblem("amount", tooLarge()));
            return null;
        }
        return new BigDecimal(written).movePointRight(decimals).longValueExact();
    }

    private String tooLarge() {
        return violations(NewEntry.class, "amount", PlanningDtos.MOST_MONEY + 1).stream().findFirst().orElse("That amount is too large.");
    }

    // ------------------------------------------------------------ the form's declared rules

    private void declared(String field, String value, String property, List<RowProblem> problems) {
        violations(NewEntry.class, property, value).forEach(message -> problems.add(new RowProblem(field, message)));
    }

    /** What the rules declared on a request's field say against this value: the words of the form. */
    private <T> List<String> violations(Class<T> type, String property, Object value) {
        return validator.validateValue(type, property, value).stream()
                .map(ConstraintViolation::getMessage)
                .sorted()
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------ small things

    static String quoted(String name) {
        return "\"" + shortName(name) + "\"";
    }

    /** A name from the file, short enough to be said back. */
    static String shortName(String name) {
        return name.length() <= 60 ? name : name.substring(0, 60) + "…";
    }

    static Set<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
