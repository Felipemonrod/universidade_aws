package com.universidade.exception;

public class StudentNotFoundException extends RuntimeException {

    public StudentNotFoundException(Long id) {
        super("Aluno não encontrado com id: " + id);
    }
}
