package com.universidade.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO (Data Transfer Object) — padrão que desacopla a camada web da entidade JPA.
 * Usar @Builder (Padrão Builder) facilita a construção imutável do objeto.
 */
@Getter
@Setter
@Builder
public class StudentDTO {

    private Long id;

    @NotBlank(message = "Nome é obrigatório")
    @Size(max = 255, message = "Nome deve ter no máximo 255 caracteres")
    private String name;

    @NotBlank(message = "Endereço é obrigatório")
    @Size(max = 500, message = "Endereço deve ter no máximo 500 caracteres")
    private String address;

    @NotBlank(message = "Cidade é obrigatória")
    @Size(max = 255, message = "Cidade deve ter no máximo 255 caracteres")
    private String city;

    @NotBlank(message = "Estado é obrigatório")
    @Size(max = 100, message = "Estado deve ter no máximo 100 caracteres")
    private String state;

    @NotBlank(message = "Email é obrigatório")
    @Email(message = "Email inválido")
    @Size(max = 255, message = "Email deve ter no máximo 255 caracteres")
    private String email;

    @NotBlank(message = "Telefone é obrigatório")
    @Pattern(regexp = "^[0-9\\-\\+\\s()]{7,20}$", message = "Telefone inválido")
    private String phone;
}
