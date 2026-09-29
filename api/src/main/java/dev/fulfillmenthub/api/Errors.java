package dev.fulfillmenthub.api;

import dev.fulfillmenthub.domain.DomainException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.OptimisticLockingFailureException;

@RestControllerAdvice
public class Errors {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail invalidRequest(Exception ignored) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request could not be read. Check the JSON body and field types.");
        problem.setTitle("Malformed request");
        return problem;
    }
    @ExceptionHandler(DomainException.class) public ProblemDetail domain(DomainException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(422), "Domain rule violated.");
    }
    @ExceptionHandler(OptimisticLockingFailureException.class) public ProblemDetail conflict(OptimisticLockingFailureException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The resource changed concurrently. Try again.");
    }
    @ExceptionHandler(IllegalArgumentException.class) public ProblemDetail invalidArgument(IllegalArgumentException ignored) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request contains an invalid value.");
    }
}
