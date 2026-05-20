package com.universidade.controller;

import com.universidade.dto.StudentDTO;
import com.universidade.exception.StudentNotFoundException;
import com.universidade.service.StudentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller MVC — gerencia a camada de apresentação com Thymeleaf.
 * Padrão Front Controller: ponto único de entrada para requisições web.
 */
@Controller
@RequestMapping("/students")
@RequiredArgsConstructor
public class StudentController {

    private final StudentService studentService;

    @GetMapping
    public String list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            Model model) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("name").ascending());
        Page<StudentDTO> studentPage = (search != null && !search.isBlank())
                ? studentService.search(search, pageable)
                : studentService.findAll(pageable);

        model.addAttribute("students", studentPage.getContent());
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", studentPage.getTotalPages());
        model.addAttribute("totalElements", studentPage.getTotalElements());
        model.addAttribute("search", search);
        return "students/list";
    }

    @GetMapping("/new")
    public String showCreateForm(Model model) {
        model.addAttribute("student", StudentDTO.builder().build());
        model.addAttribute("formAction", "/students");
        model.addAttribute("pageTitle", "Novo Aluno");
        return "students/form";
    }

    @PostMapping
    public String create(
            @Valid @ModelAttribute("student") StudentDTO dto,
            BindingResult result,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (result.hasErrors()) {
            model.addAttribute("formAction", "/students");
            model.addAttribute("pageTitle", "Novo Aluno");
            return "students/form";
        }

        try {
            studentService.save(dto);
            redirectAttributes.addFlashAttribute("successMessage", "Aluno cadastrado com sucesso!");
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("formAction", "/students");
            model.addAttribute("pageTitle", "Novo Aluno");
            return "students/form";
        }

        return "redirect:/students";
    }

    @GetMapping("/{id}/edit")
    public String showEditForm(@PathVariable Long id, Model model) {
        StudentDTO student = studentService.findById(id);
        model.addAttribute("student", student);
        model.addAttribute("formAction", "/students/" + id + "/edit");
        model.addAttribute("pageTitle", "Editar Aluno");
        return "students/form";
    }

    @PostMapping("/{id}/edit")
    public String update(
            @PathVariable Long id,
            @Valid @ModelAttribute("student") StudentDTO dto,
            BindingResult result,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (result.hasErrors()) {
            model.addAttribute("formAction", "/students/" + id + "/edit");
            model.addAttribute("pageTitle", "Editar Aluno");
            return "students/form";
        }

        try {
            studentService.update(id, dto);
            redirectAttributes.addFlashAttribute("successMessage", "Aluno atualizado com sucesso!");
        } catch (IllegalArgumentException e) {
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("formAction", "/students/" + id + "/edit");
            model.addAttribute("pageTitle", "Editar Aluno");
            return "students/form";
        }

        return "redirect:/students";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            studentService.deleteById(id);
            redirectAttributes.addFlashAttribute("successMessage", "Aluno removido com sucesso!");
        } catch (StudentNotFoundException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/students";
    }
}
