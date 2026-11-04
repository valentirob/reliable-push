package io.reliablepush.client;

/**
 * RFC 9457 problem document of an error response. {@code field} is the first invalid field of a 422 (or null).
 * Fields absent in the response are null (status 0 when absent).
 */
public record ProblemDetails(String type, String title, int status, String detail, String field) {
}
