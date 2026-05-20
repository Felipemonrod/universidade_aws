package com.universidade.service;

import com.universidade.dto.StudentDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Padrão Service Layer — define o contrato de negócio independente de implementação.
 * Programar para a interface (não para a implementação) facilita testes e troca de impl.
 */
public interface StudentService {

    List<StudentDTO> findAll();

    Page<StudentDTO> findAll(Pageable pageable);

    Page<StudentDTO> search(String term, Pageable pageable);

    StudentDTO findById(Long id);

    StudentDTO save(StudentDTO dto);

    StudentDTO update(Long id, StudentDTO dto);

    void deleteById(Long id);
}
