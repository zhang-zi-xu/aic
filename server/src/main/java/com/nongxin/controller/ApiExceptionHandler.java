package com.nongxin.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed() {
        return ResponseEntity.badRequest().body(Map.of("error", "请求内容格式不正确，请检查字段类型"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "记录已存在或关联数据发生变化，请刷新后重试"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storageFailure(DataAccessException exception) {
        if (exception.getMostSpecificCause() instanceof java.sql.SQLException sql && sql.getErrorCode() == 19) {
            return conflict();
        }
        log.error("数据库操作失败", exception);
        return ResponseEntity.internalServerError().body(Map.of("error", "数据保存服务暂时不可用，请稍后重试"));
    }
}
