package com.diafarms.ml.others;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

public class ApiResponse<T> {
    
    private String message;
    private int status;
    private T data;
    private LocalDateTime timestamp;
    private List<String> errors;

    // Constructeur avec errors et timestamp
    public ApiResponse(String message, int status, T data, List<String> errors) {
        this.message = message;
        this.status = status;
        this.data = data;
        this.timestamp = LocalDateTime.now(); // Ajout automatique du timestamp
        this.errors = errors;
    }

    public static <T> ResponseEntity<ApiResponse<T>> createResponse(String message, HttpStatus status, T data, List<String> errors) {
        ApiResponse<T> response = new ApiResponse<>(message, status.value(), data, errors);
        return new ResponseEntity<>(response, status);
    }
    

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }
}