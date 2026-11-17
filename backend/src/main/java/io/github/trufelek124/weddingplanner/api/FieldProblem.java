package io.github.trufelek124.weddingplanner.api;

/**
 * What is wrong with one field of a request, so that a form can say it next to that field.
 *
 * @param field the field's name as the request spells it
 * @param message what is wrong, in words for the person filling in the form
 */
public record FieldProblem(String field, String message) {}
