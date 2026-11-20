package io.github.trufelek124.weddingplanner.planning.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

/**
 * A file of tasks read as a tree of what it says, and nothing more. A number stays the text it was written as (an
 * amount never passes through a floating point number), and a name used twice in one object is noted, not lost.
 * What the file holds is somebody's data: nothing here prints it ({@code toString()} hides it).
 */
final class TaskFileJson {

    private static final JsonFactory FACTORY = new JsonFactory();

    private TaskFileJson() {}

    sealed interface Value permits Text, Number, Flag, Nothing, Items, Fields {}

    record Text(String value) implements Value {
        @Override
        public String toString() {
            return "Text[hidden]";
        }
    }

    /** @param written the number as it stands in the file */
    record Number(String written) implements Value {
        @Override
        public String toString() {
            return "Number[hidden]";
        }
    }

    record Flag(boolean value) implements Value {
        @Override
        public String toString() {
            return "Flag[hidden]";
        }
    }

    /** JSON {@code null}. */
    record Nothing() implements Value {
        @Override
        public String toString() {
            return "Nothing";
        }
    }

    record Items(List<Value> items) implements Value {
        @Override
        public String toString() {
            return "Items[hidden]";
        }
    }

    /** @param repeated the names that stood twice in this object */
    record Fields(Map<String, Value> fields, Set<String> repeated) implements Value {
        @Override
        public String toString() {
            return "Fields[hidden]";
        }
    }

    /** The text is not JSON, or has something after the one value it holds. */
    static final class NotJson extends Exception {
        NotJson() {
            super("not JSON", null, false, false);
        }
    }

    static Value read(String text) throws NotJson {
        try (JsonParser parser = FACTORY.createParser(ObjectReadContext.empty(), text)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new NotJson();
            }
            Value value = value(parser, first);
            if (parser.nextToken() != null) {
                throw new NotJson();
            }
            return value;
        } catch (JacksonException | IllegalStateException broken) {
            throw new NotJson();
        }
    }

    private static Value value(JsonParser parser, JsonToken token) {
        if (token == null) {
            // The text ended inside a value.
            throw new IllegalStateException("not a value");
        }
        return switch (token) {
            case VALUE_STRING -> new Text(parser.getString());
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> new Number(parser.getString());
            case VALUE_TRUE -> new Flag(true);
            case VALUE_FALSE -> new Flag(false);
            case VALUE_NULL -> new Nothing();
            case START_ARRAY -> {
                List<Value> items = new ArrayList<>();
                for (JsonToken next = parser.nextToken(); next != JsonToken.END_ARRAY; next = parser.nextToken()) {
                    items.add(value(parser, next));
                }
                yield new Items(items);
            }
            case START_OBJECT -> {
                Map<String, Value> fields = new LinkedHashMap<>();
                Set<String> repeated = new LinkedHashSet<>();
                for (JsonToken next = parser.nextToken(); next != JsonToken.END_OBJECT; next = parser.nextToken()) {
                    String name = parser.currentName();
                    Value inside = value(parser, parser.nextToken());
                    if (fields.putIfAbsent(name, inside) != null) {
                        repeated.add(name);
                    }
                }
                yield new Fields(fields, repeated);
            }
            default -> throw new IllegalStateException("not a value");
        };
    }
}
