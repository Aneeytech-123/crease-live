package com.crease.live.web;

import com.crease.live.service.Exceptions.InvalidBallException;
import com.crease.live.service.Exceptions.MatchStateException;
import com.crease.live.service.Exceptions.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/** Maps domain and validation errors to RFC 7807 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(MatchStateException.class)
    public ProblemDetail conflict(MatchStateException e) {
        return problem(HttpStatus.CONFLICT, "Invalid match state", e.getMessage());
    }

    @ExceptionHandler(InvalidBallException.class)
    public ProblemDetail invalidBall(InvalidBallException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid delivery", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", detail);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ProblemDetail badRequest(Exception e) {
        String msg = e instanceof HttpMessageNotReadableException ? "Malformed request body" : e.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "Bad request", msg);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
