package com.akshaynavani.talkburst.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.akshaynavani.talkburst.api.dto.ApiError;
import com.akshaynavani.talkburst.session.SessionFullException;
import com.akshaynavani.talkburst.session.SessionNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ApiError> notFound(SessionNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(SessionFullException.class)
    public ResponseEntity<ApiError> full(SessionFullException e) {
        return respond(HttpStatus.CONFLICT, "SESSION_FULL", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("invalid request");
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status).body(ApiError.of(status.value(), code, msg));
    }
}
