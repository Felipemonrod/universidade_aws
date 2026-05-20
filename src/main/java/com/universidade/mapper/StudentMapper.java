package com.universidade.mapper;

import com.universidade.dto.StudentDTO;
import com.universidade.model.Student;
import org.springframework.stereotype.Component;

/**
 * Padrão Mapper — responsável exclusivamente por converter entre Entity e DTO.
 * Separa a responsabilidade de transformação de dados das demais camadas (SRP).
 */
@Component
public class StudentMapper {

    public StudentDTO toDTO(Student student) {
        return StudentDTO.builder()
                .id(student.getId())
                .name(student.getName())
                .address(student.getAddress())
                .city(student.getCity())
                .state(student.getState())
                .email(student.getEmail())
                .phone(student.getPhone())
                .build();
    }

    public Student toEntity(StudentDTO dto) {
        Student student = new Student();
        applyDTOToEntity(dto, student);
        return student;
    }

    public void updateEntityFromDTO(StudentDTO dto, Student student) {
        applyDTOToEntity(dto, student);
    }

    private void applyDTOToEntity(StudentDTO dto, Student student) {
        student.setName(dto.getName());
        student.setAddress(dto.getAddress());
        student.setCity(dto.getCity());
        student.setState(dto.getState());
        student.setEmail(dto.getEmail());
        student.setPhone(dto.getPhone());
    }
}
