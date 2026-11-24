package com.foodsafety.exchange.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> api(ApiException e) {
    return ResponseEntity.status(e.status()).body(body(e.code(), e.getMessage()));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MissingServletRequestParameterException.class,
    MissingServletRequestPartException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<Map<String, Object>> badRequest(Exception e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body("BAD_REQUEST", e.getMessage()));
  }

  private Map<String, Object> body(String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("message", message);
    return body;
  }
}
