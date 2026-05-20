package com.universidade.repository;

import com.universidade.model.Student;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Padrão Repository — abstrai o acesso ao banco de dados.
 * Extender JpaRepository fornece operações CRUD + paginação sem boilerplate.
 */
@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);

    @Query("SELECT s FROM Student s WHERE " +
           "LOWER(s.name) LIKE LOWER(CONCAT('%', :term, '%')) OR " +
           "LOWER(s.email) LIKE LOWER(CONCAT('%', :term, '%')) OR " +
           "LOWER(s.city) LIKE LOWER(CONCAT('%', :term, '%'))")
    Page<Student> search(@Param("term") String term, Pageable pageable);
}
