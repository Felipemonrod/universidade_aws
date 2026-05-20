package com.universidade.service.impl;

import com.universidade.dto.StudentDTO;
import com.universidade.exception.StudentNotFoundException;
import com.universidade.mapper.StudentMapper;
import com.universidade.model.Student;
import com.universidade.repository.StudentRepository;
import com.universidade.service.StudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Implementação do StudentService.
 * Padrões aplicados:
 *   - Service Layer: lógica de negócio isolada do controller e do repositório
 *   - Dependency Injection: dependências injetadas via construtor (imutável)
 *   - Repository: acesso a dados via StudentRepository
 *   - DTO: retorna sempre DTOs, nunca entidades JPA diretamente
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentServiceImpl implements StudentService {

    private final StudentRepository studentRepository;
    private final StudentMapper studentMapper;

    @Override
    public List<StudentDTO> findAll() {
        return studentRepository.findAll()
                .stream()
                .map(studentMapper::toDTO)
                .toList();
    }

    @Override
    public Page<StudentDTO> findAll(Pageable pageable) {
        return studentRepository.findAll(pageable)
                .map(studentMapper::toDTO);
    }

    @Override
    public Page<StudentDTO> search(String term, Pageable pageable) {
        return studentRepository.search(term, pageable)
                .map(studentMapper::toDTO);
    }

    @Override
    public StudentDTO findById(Long id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new StudentNotFoundException(id));
        return studentMapper.toDTO(student);
    }

    @Override
    @Transactional
    public StudentDTO save(StudentDTO dto) {
        if (studentRepository.existsByEmail(dto.getEmail())) {
            throw new IllegalArgumentException("Email já cadastrado: " + dto.getEmail());
        }
        Student student = studentMapper.toEntity(dto);
        return studentMapper.toDTO(studentRepository.save(student));
    }

    @Override
    @Transactional
    public StudentDTO update(Long id, StudentDTO dto) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new StudentNotFoundException(id));

        if (studentRepository.existsByEmailAndIdNot(dto.getEmail(), id)) {
            throw new IllegalArgumentException("Email já utilizado por outro aluno: " + dto.getEmail());
        }

        studentMapper.updateEntityFromDTO(dto, student);
        return studentMapper.toDTO(studentRepository.save(student));
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        if (!studentRepository.existsById(id)) {
            throw new StudentNotFoundException(id);
        }
        studentRepository.deleteById(id);
    }
}
