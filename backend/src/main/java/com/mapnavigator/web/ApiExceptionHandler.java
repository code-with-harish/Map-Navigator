package com.mapnavigator.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.mapnavigator.web.dto.ErrorView;

/**
 * Maps failures to the same JSON error shape the controllers use, so clients
 * never have to parse Spring's default HTML error page. Bad input is a 400
 * with a message that names the offending parameter; anything unexpected is
 * logged with a stack trace and returned as an opaque 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorView> illegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(new ErrorView(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorView> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(new ErrorView(
                "Parameter '" + ex.getName() + "' has an invalid value"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorView> missingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(new ErrorView(
                "Missing required parameter '" + ex.getParameterName() + "'"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorView> unreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(new ErrorView("Request body is not valid JSON"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorView> unexpected(Exception ex) {
        log.error("Unhandled error while serving request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorView("Internal error"));
    }
}
